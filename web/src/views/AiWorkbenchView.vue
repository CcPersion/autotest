<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'

import AppIcon from '../components/AppIcon.vue'
import AiPatchDiff from '../components/AiPatchDiff.vue'
import { aiApi, type AiMessage, type AiStreamEvent } from '../api/ai'
import { aiPatchApi, type AiPatchPreviewResponse, type AiPatchWrite } from '../api/aiPatch'
import { runApi, type RunRecord } from '../api/run'
import { aiTaskDefinitions, selectAiTask, type AiTaskKey } from '../prototype/state'

const props = defineProps<{ projectId?: string | null }>()
const emit = defineEmits<{ navigate: [page: string] }>()

const taskKeys: AiTaskKey[] = ['requirement', 'curl', 'openapi', 'failure']
const selectedTaskKey = ref<AiTaskKey>('requirement')
const prompt = ref('')
const draftReady = ref(false)
const diffOpen = ref(false)
const accepted = ref(false)
const models = ref<Array<{ id: string; name: string; enabled: boolean }>>([])
const runs = ref<RunRecord[]>([])
const selectedRunId = ref('')
const sessionId = ref<string | null>(null)
const messages = ref<Array<{ role: 'USER' | 'ASSISTANT' | 'TOOL'; content: string; toolName?: string | null }>>([])
const loading = ref(false)
const confirming = ref(false)
const errorMessage = ref('')
const reportEvidence = shallowRef<Record<string, unknown> | null>(null)
const draftPreview = shallowRef<AiPatchPreviewResponse | null>(null)
let abortController: AbortController | undefined

const selectedTask = computed(() => selectAiTask(selectedTaskKey.value))
const tasks = computed(() => taskKeys.map((key) => aiTaskDefinitions[key]))
const reportSteps = computed(() => Array.isArray(reportEvidence.value?.steps) ? reportEvidence.value.steps as Array<Record<string, unknown>> : [])

const draftTitle = computed(() => {
  if (selectedTaskKey.value === 'curl') return '已生成接口用例草稿'
  if (selectedTaskKey.value === 'openapi') return '已生成 OpenAPI 导入预览'
  if (selectedTaskKey.value === 'failure') return '已生成失败分析建议'
  return '已生成测试场景草稿'
})

const draftSubtitle = computed(() => {
  if (selectedTaskKey.value === 'curl') return '基于请求内容生成参数、断言和变量提取'
  if (selectedTaskKey.value === 'openapi') return '识别到 12 个接口，其中 3 个接口发生变更'
  if (selectedTaskKey.value === 'failure') return '从最近一次运行中定位 1 个失败断言和 2 个可能原因'
  return '基于你的描述和项目内 3 个接口用例'
})

const draftFlow = computed(() => {
  if (selectedTaskKey.value === 'curl') return ['解析请求', '整理参数', '补充断言', '保存用例']
  if (selectedTaskKey.value === 'openapi') return ['读取文档', '识别接口', '预览变更', '导入目录']
  if (selectedTaskKey.value === 'failure') return ['读取报告', '定位失败', '分析原因', '生成回归']
  return ['获取访问令牌', '创建签署订单', '查询订单状态', 'JDBC 校验']
})

function chooseTask(key: AiTaskKey) {
  selectedTaskKey.value = key
  prompt.value = ''
  draftReady.value = false
  accepted.value = false
  errorMessage.value = ''
  if (key !== 'failure') reportEvidence.value = null
}

function useExample() {
  prompt.value = selectedTask.value.example
}

function appendAssistant(content = '') {
  messages.value.push({ role: 'ASSISTANT', content })
  return messages.value.length - 1
}

function parseToolResult(event: AiStreamEvent): Record<string, unknown> | null {
  if (event.arguments && typeof event.arguments === 'object') return event.arguments as Record<string, unknown>
  if (!event.content) return null
  try { return JSON.parse(event.content) as Record<string, unknown> } catch { return null }
}

async function handleEvent(event: AiStreamEvent, assistantIndex: number) {
  if (event.type === 'TEXT') messages.value[assistantIndex].content += event.content || ''
  if (event.type === 'ERROR') errorMessage.value = event.content || 'AI 服务暂时不可用'
  if (event.type === 'TOOL_CALL') messages.value.push({ role: 'TOOL', content: '读取结构化项目事实…', toolName: event.toolName })
  if (event.type !== 'TOOL_RESULT') return
  const result = parseToolResult(event)
  if (event.toolName === 'get_run_report') {
    reportEvidence.value = result
    messages.value[assistantIndex].content = '已读取这次运行的脱敏步骤证据，可以据此排查失败原因。'
    return
  }
  if (event.toolName === 'create_draft_patch' && props.projectId && result) {
    const write: AiPatchWrite = {
      title: String(result.title || 'AI 生成草稿'),
      targetType: result.targetType as AiPatchWrite['targetType'],
      targetId: result.targetId ? String(result.targetId) : null,
      parentId: result.parentId ? String(result.parentId) : null,
      baseRevision: typeof result.baseRevision === 'number' ? result.baseRevision : null,
      operations: Array.isArray(result.operations) ? result.operations as AiPatchWrite['operations'] : [],
    }
    try {
      draftPreview.value = await aiPatchApi.preview(props.projectId, write)
      draftReady.value = true
      diffOpen.value = true
      messages.value[assistantIndex].content = '草稿已生成，下面展示字段级差异；确认前不会写入项目资产。'
    } catch (error) {
      errorMessage.value = (error as { message?: string })?.message || '草稿差异预览失败'
    }
  }
}

async function ensureSession() {
  if (!props.projectId) throw new Error('请先选择项目')
  if (sessionId.value) return sessionId.value
  const model = models.value.find((item) => item.enabled)
  if (!model) throw new Error('请先在系统设置中配置并启用模型服务')
  const session = await aiApi.createSession(props.projectId, { modelConfigId: model.id, title: selectedTask.value.title })
  sessionId.value = session.id
  return session.id
}

async function sendPrompt() {
  if (loading.value) return
  if (!prompt.value.trim()) prompt.value = selectedTask.value.example
  errorMessage.value = ''
  accepted.value = false
  loading.value = true
  const text = selectedTaskKey.value === 'failure'
    ? `${prompt.value}\nprojectId=${props.projectId || ''} runId=${selectedRunId.value}`
    : prompt.value
  messages.value.push({ role: 'USER', content: prompt.value })
  const assistantIndex = appendAssistant()
  abortController?.abort()
  abortController = new AbortController()
  try {
    const id = await ensureSession()
    await aiApi.streamMessage(props.projectId || '', id, text, (event) => { void handleEvent(event, assistantIndex) }, abortController.signal)
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || 'AI 请求失败'
  } finally {
    loading.value = false
  }
}

async function confirmPatch() {
  if (!props.projectId || !draftPreview.value) return
  confirming.value = true
  try {
    await aiPatchApi.confirm(props.projectId, draftPreview.value.previewId)
    accepted.value = true
    diffOpen.value = false
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || '确认草稿失败，请刷新后重试'
  } finally {
    confirming.value = false
  }
}

async function loadWorkbench() {
  abortController?.abort()
  sessionId.value = null
  messages.value = []
  runs.value = []
  reportEvidence.value = null
  draftPreview.value = null
  if (!props.projectId) return
  try {
    const [modelItems, sessionItems, runItems] = await Promise.all([aiApi.models(), aiApi.sessions(props.projectId), runApi.list(props.projectId)])
    models.value = modelItems
    runs.value = runItems
    const latest = sessionItems[0]
    if (latest) {
      const detail = await aiApi.getSession(props.projectId, latest.id)
      sessionId.value = detail.id
      messages.value = (detail.messages || []).filter((message: AiMessage) => ['USER', 'ASSISTANT', 'TOOL'].includes(message.role)).map((message) => ({ role: message.role, content: message.content, toolName: message.toolName }))
    }
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || 'AI 工作台数据加载失败'
  }
}

watch(() => props.projectId, () => { void loadWorkbench() }, { immediate: true })
onBeforeUnmount(() => abortController?.abort())
</script>

<template>
  <section class="ai-layout ai-layout-v2">
    <div class="ai-main">
      <div class="hero-copy">
        <span class="hero-kicker"><i></i> 可审阅的测试协作助手</span>
        <h2>先选一个任务，<br /><em>让测试从这里开始。</em></h2>
        <p>AI 只负责整理和建议，所有修改都会先生成差异。你确认后，才会保存到项目资产。</p>
      </div>

      <div class="ai-steps" aria-label="使用流程">
        <div class="ai-step active"><span>1</span><div><strong>选择任务</strong><small>告诉 AI 你要完成什么</small></div></div>
        <i></i>
        <div class="ai-step"><span>2</span><div><strong>提供信息</strong><small>描述需求或粘贴接口资料</small></div></div>
        <i></i>
        <div class="ai-step"><span>3</span><div><strong>审阅保存</strong><small>查看差异后确认写入</small></div></div>
      </div>

      <div class="task-grid" aria-label="AI 任务">
        <button
          v-for="task in tasks"
          :key="task.key"
          class="task-card"
          :class="{ selected: selectedTaskKey === task.key }"
          @click="chooseTask(task.key)"
        >
          <span class="task-icon"><AppIcon :name="task.icon" :size="19" /></span>
          <span class="task-card-copy"><strong>{{ task.title }}</strong><small>{{ task.description }}</small></span>
          <AppIcon name="arrow" :size="15" />
        </button>
      </div>

      <div class="composer composer-v2" :class="{ focused: prompt.length > 0 }">
        <div class="composer-label"><span>{{ selectedTask.inputLabel }}</span><button @click="useExample">使用示例</button></div>
        <textarea v-model="prompt" rows="3" :placeholder="selectedTask.placeholder"></textarea>
        <div v-if="selectedTaskKey === 'failure'" class="failure-context">
          <label>分析哪一次运行<select v-model="selectedRunId" data-testid="ai-run-context"><option value="">请选择运行</option><option v-for="run in runs" :key="run.id" :value="run.id">{{ run.id }} · {{ run.status }} · {{ run.targetType }}</option></select></label>
        </div>
        <div class="composer-footer">
          <div class="context-pills">
            <button><span class="mini-project">{{ props.projectId ? 'P' : '?' }}</span> {{ props.projectId ? '当前项目' : '请先选择项目' }}</button>
            <span v-if="models.some((item) => item.enabled)" class="model-ready"><AppIcon name="check" :size="13" /> 模型已就绪</span>
          </div>
          <button class="send-button" :aria-label="selectedTask.actionLabel" :disabled="loading || !props.projectId" @click="sendPrompt"><AppIcon name="arrow" :size="18" /></button>
        </div>
      </div>

      <div v-if="messages.length" class="ai-conversation" data-testid="ai-conversation">
        <div v-for="(message, index) in messages" :key="`${index}-${message.role}`" class="ai-message" :class="message.role.toLowerCase()">
          <span class="message-role">{{ message.role === 'USER' ? '你' : message.role === 'TOOL' ? '结构化工具' : 'AI' }}</span>
          <p>{{ message.content || (loading && index === messages.length - 1 ? '正在整理…' : '') }}</p>
        </div>
      </div>

      <div v-if="reportEvidence" class="report-explanation" data-testid="ai-report-evidence">
        <header><div><span class="section-overline">REPORT EVIDENCE</span><h3>失败报告证据</h3></div><span class="safe-badge">已脱敏</span></header>
        <div v-for="step in reportSteps" :key="String(step.resultKey)" class="report-evidence-row"><strong>{{ step.resultKey }}</strong><span :class="step.status === 'FAILED' ? 'danger-text' : 'success-text'">{{ step.status }}</span><small>断言 {{ Array.isArray(step.assertions) ? step.assertions.length : 0 }} 项 · {{ step.error ? '有错误摘要' : '无错误摘要' }}</small></div>
      </div>

      <p v-if="errorMessage" class="studio-error" role="alert">{{ errorMessage }}</p>

      <div v-if="draftReady" class="draft-card">
        <div class="draft-head">
          <span class="draft-icon"><AppIcon name="spark" :size="18" /></span>
          <div><strong>{{ draftTitle }}</strong><small>{{ draftSubtitle }}</small></div>
          <span class="safe-badge">校验通过</span>
        </div>
        <div class="draft-flow">
          <template v-for="(item, index) in draftFlow" :key="item">
            <span>{{ item }}</span><i v-if="index < draftFlow.length - 1"></i>
          </template>
        </div>
        <div class="draft-actions">
          <span v-if="accepted" class="accepted-message"><AppIcon name="check" :size="16" /> 已保存到项目草稿</span>
          <span v-else>建议已生成 · 需要你确认后保存</span>
          <div><button class="secondary-button" @click="diffOpen = true">查看差异</button><button class="primary-button" :disabled="!draftPreview" @click="diffOpen = true">{{ selectedTask.actionLabel }}</button></div>
        </div>
      </div>
    </div>

    <aside class="activity-panel ai-guide-panel">
      <div class="section-title-row"><div><span class="section-overline">START HERE</span><h3>第一次使用？</h3></div><AppIcon name="spark" :size="19" /></div>
      <div class="guide-card">
        <div class="guide-number">01</div><div><strong>先选择最接近的任务</strong><p>不同任务会提供不同的输入格式和审阅结果。</p></div>
        <div class="guide-number">02</div><div><strong>给 AI 一份可核对的信息</strong><p>需求、Curl、OpenAPI 或失败报告都可以。</p></div>
        <div class="guide-number">03</div><div><strong>确认差异，再保存</strong><p>AI 不会直接修改已有接口和场景。</p></div>
      </div>

      <div class="recent-heading"><h3>最近任务</h3><button @click="emit('navigate', 'runs')">查看运行中心</button></div>
      <div class="recent-list recent-task-list">
        <button @click="chooseTask('failure')"><span class="asset-symbol report"><AppIcon name="report" /></span><span><strong>分析订单支付回归</strong><small>失败分析 · 1 个待确认建议</small></span><time>刚刚</time></button>
        <button @click="chooseTask('curl')"><span class="asset-symbol"><AppIcon name="terminal" /></span><span><strong>创建个人签署订单</strong><small>Curl 用例 · 已保存草稿</small></span><time>18 分钟</time></button>
        <button @click="emit('navigate', 'scenario')"><span class="asset-symbol flow"><AppIcon name="flow" /></span><span><strong>合同签署核心链路</strong><small>场景自动化 · 8 个步骤</small></span><time>2 小时</time></button>
      </div>

      <div class="guide-tip"><AppIcon name="check" :size="16" /><div><strong>安全边界</strong><small>生成内容会经过结构校验，保存前始终保留差异记录。</small></div></div>
    </aside>

    <div v-if="diffOpen" class="modal-backdrop inner" @click.self="diffOpen = false">
      <section class="diff-panel" data-testid="ai-patch-modal">
        <AiPatchDiff :preview="draftPreview" :loading="confirming" @confirm="confirmPatch" @cancel="diffOpen = false" />
      </section>
    </div>
  </section>
</template>

<style scoped>
.failure-context { padding: 0 17px 8px; }
.failure-context label { display: flex; align-items: center; gap: 10px; color: var(--muted); font-size: 11px; }
.failure-context select { min-width: 230px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 7px; color: var(--ink); background: #fbfcfe; }
.model-ready { display: inline-flex; align-items: center; gap: 4px; color: #168a5a; font-size: 11px; }
.ai-conversation { display: grid; gap: 8px; margin-top: 14px; }
.ai-message { display: grid; grid-template-columns: 58px 1fr; gap: 10px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 9px; background: #fff; }
.ai-message.user { background: #f7f9ff; }
.ai-message.tool { background: #fbfcfe; }
.message-role { color: var(--muted); font-size: 10px; font-weight: 700; }
.ai-message p { margin: 0; white-space: pre-wrap; color: var(--ink); font-size: 12px; line-height: 1.55; }
.report-explanation { margin-top: 14px; padding: 14px; border: 1px solid #d8e3ff; border-radius: 10px; background: #f8faff; }
.report-explanation header { display: flex; align-items: center; justify-content: space-between; }
.report-explanation h3 { margin: 4px 0 10px; font-size: 13px; }
.report-evidence-row { display: grid; grid-template-columns: 1fr auto auto; gap: 10px; align-items: center; padding: 9px 0; border-top: 1px solid #e3e9f8; font-size: 11px; }
.report-evidence-row small { color: var(--muted); }
.send-button:disabled { opacity: .5; cursor: not-allowed; }
</style>

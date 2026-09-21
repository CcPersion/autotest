<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import { runApi, type RunReport, type StepResult } from '../api/run'
import { formatDataRowLabel } from '../utils/reportDataRow'

const props = defineProps<{ projectId?: string | null; runId?: string | null }>()
const detailTab = ref('断言')
const report = shallowRef<RunReport | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const selectedStep = ref(0)
const selectedMemberId = ref('')
const currentStep = shallowRef<StepResult | null>(null)
let reportPollToken = 0
let reportPollTimer: ReturnType<typeof setTimeout> | undefined
const realTabs = ['请求', '响应', '提取', '断言', '日志']
const reportSteps = [
  { title: '获取访问令牌', kind: 'POST', status: 'passed', time: '312 ms' },
  { title: '保存访问令牌', kind: '提取', status: 'passed', time: '2 ms' },
  { title: '创建个人签署订单', kind: 'POST', status: 'failed', time: '428 ms' },
  { title: '订单创建成功时', kind: '条件', status: 'skipped', time: '跳过' },
  { title: '等待异步签署结果', kind: '等待', status: 'skipped', time: '跳过' },
  { title: '删除测试订单', kind: '清理', status: 'passed', time: '116 ms' },
]

const visibleSteps = computed(() => {
  if (!report.value || !selectedMemberId.value) return report.value?.steps ?? []
  return report.value.steps.filter((step) => step.memberId === selectedMemberId.value)
})

async function loadReport() {
  const pollToken = ++reportPollToken
  if (reportPollTimer) {
    clearTimeout(reportPollTimer)
    reportPollTimer = undefined
  }
  report.value = null
  currentStep.value = null
  selectedStep.value = 0
  selectedMemberId.value = ''
  errorMessage.value = ''
  if (!props.projectId || !props.runId) return
  loading.value = true

  const loadOnce = async () => {
    if (pollToken !== reportPollToken || !props.projectId || !props.runId) return
    try {
      const next = await runApi.report(props.projectId, props.runId)
      if (pollToken !== reportPollToken) return
      report.value = next
      selectedStep.value = 0
      currentStep.value = visibleSteps.value[0] ?? null
      loading.value = false
      if (next.status === 'PENDING' || next.status === 'RUNNING') {
        reportPollTimer = setTimeout(() => { void loadOnce() }, 1000)
      }
    } catch (error) {
      if (pollToken !== reportPollToken) return
      errorMessage.value = (error as { message?: string })?.message || '报告加载失败'
      loading.value = false
    }
  }

  await loadOnce()
}

function formatDuration(ms: number) {
  return `${ms} ms`
}

function selectStep(index: number) {
  selectedStep.value = index
  currentStep.value = visibleSteps.value[index] ?? null
}

function filterMember(memberId: string) {
  selectedMemberId.value = memberId
  selectedStep.value = 0
  currentStep.value = visibleSteps.value[0] ?? null
}

function dynamicStepTitle(step: RunReport['steps'][number]) {
  return step.requestSummary && typeof step.requestSummary === 'object' && 'url' in step.requestSummary
    ? String(step.requestSummary.url)
    : `步骤 ${step.sequenceNo + 1}`
}

function requestMethod(step: StepResult) {
  return step.requestSummary && typeof step.requestSummary === 'object' && 'method' in step.requestSummary
    ? String(step.requestSummary.method)
    : 'HTTP'
}

function dataRowLabel(step: StepResult) {
  return formatDataRowLabel(step.resultKey)
}

function cleanupStatusLabel(status: RunReport['cleanupStatus']) {
  return ({ NOT_APPLICABLE: '不适用', PENDING: '执行中', PASSED: '已完成', FAILED: '有失败', NOT_EXECUTED: '未执行' } as Record<RunReport['cleanupStatus'], string>)[status]
}

function evidenceText(value: unknown): string {
  if (value === null || value === undefined || value === '') return ''
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

function assertionField(item: unknown, field: string): string {
  if (!item || typeof item !== 'object') return ''
  return evidenceText((item as Record<string, unknown>)[field])
}

watch(() => [props.projectId, props.runId], () => { void loadReport() }, { immediate: true })
onBeforeUnmount(() => {
  reportPollToken += 1
  if (reportPollTimer) clearTimeout(reportPollTimer)
})
</script>

<template>
  <section v-if="props.runId" class="report-page" data-testid="real-report" :data-run-id="props.runId">
    <header class="report-hero">
      <div class="report-state-mark" :class="report?.status === 'PASSED' ? 'passed' : ''"><AppIcon :name="report?.status === 'PASSED' ? 'check' : 'runs'" :size="24" /></div>
      <div><span class="section-overline">{{ props.runId }}</span><h2>接口运行报告</h2><p v-if="loading">正在加载 Runner 回传的原生步骤证据…</p><p v-else-if="report">运行状态：{{ report.status === 'PASSED' ? '通过' : report.status }}</p><p v-else>{{ errorMessage }}</p></div>
      <div class="report-actions"><button class="secondary-button" disabled><AppIcon name="upload" :size="14" />导出报告（后续）</button></div>
    </header>
    <div v-if="report" class="report-stats"><div><span>最终状态</span><strong :class="report.status === 'PASSED' ? 'success-text' : 'danger-text'">{{ report.status }}</strong></div><div><span>步骤数</span><strong>{{ report.steps.length }}</strong></div><div><span>退出码</span><strong>{{ report.exitCode ?? '—' }}</strong></div><div><span>清理状态</span><strong :class="report.cleanupStatus === 'PASSED' ? 'success-text' : report.cleanupStatus === 'FAILED' || report.cleanupStatus === 'NOT_EXECUTED' ? 'danger-text' : ''">{{ cleanupStatusLabel(report.cleanupStatus) }}</strong></div><div><span>执行器</span><strong>JMeter 5.6.3</strong></div></div>
    <div v-if="report?.suiteMembers?.length" class="suite-report-members"><header><div><span class="section-overline">SUITE MEMBERS</span><h3>集合成员证据</h3></div><label class="suite-member-filter">查看成员<select data-testid="suite-member-filter" :value="selectedMemberId" @change="filterMember(($event.target as HTMLSelectElement).value)"><option value="">全部成员</option><option v-for="member in report.suiteMembers" :key="member.memberId" :value="member.memberId">{{ member.position + 1 }}. {{ member.targetName }}</option></select></label><small>成员顺序来自本次不可变运行计划</small></header><div class="suite-report-member-list"><div v-for="member in report.suiteMembers" :key="member.memberId" class="suite-report-member"><span class="member-index">{{ member.position + 1 }}</span><AppIcon :name="member.targetType === 'SCENARIO' ? 'flow' : 'case'" :size="14" /><div><strong>{{ member.targetName }}</strong><small>{{ member.targetType === 'SCENARIO' ? '场景' : '接口用例' }} · {{ member.targetId }}</small></div><span :class="member.enabled ? 'success-text' : 'muted-text'">{{ member.enabled ? '已启用' : '已停用' }}</span></div></div></div>
    <div v-if="report" class="report-workbench"><aside class="report-tree"><header><span>步骤证据</span><small>{{ visibleSteps.length }} 个节点</small></header><button v-for="(step, index) in visibleSteps" :key="step.resultKey" :class="{ active: selectedStep === index }" @click="selectStep(index)"><i><AppIcon :name="step.status === 'PASSED' ? 'check' : 'close'" :size="13" /></i><span><strong>{{ dynamicStepTitle(step) }}</strong><small><em v-if="dataRowLabel(step)" class="data-row-label">{{ dataRowLabel(step) }}</em>{{ step.status }} · {{ formatDuration(step.durationMs) }}</small></span></button></aside><section v-if="currentStep" class="evidence-panel"><header><div><span class="method-large">HTTP</span><div><h3>{{ dynamicStepTitle(currentStep) }}</h3><small v-if="dataRowLabel(currentStep)" class="data-row-label">{{ dataRowLabel(currentStep) }}</small><code>{{ currentStep.resultKey }}</code></div></div><span class="failed-pill" :class="currentStep.status === 'PASSED' ? 'passed-pill' : ''">{{ currentStep.status }}</span></header><nav class="tabbar evidence-tabs"><button v-for="tab in realTabs" :key="tab" :class="{ active: detailTab === tab }" @click="detailTab = tab">{{ tab }}</button></nav><div v-if="detailTab === '请求'" class="evidence-code"><pre>{{ JSON.stringify(currentStep.requestSummary, null, 2) }}</pre></div><div v-else-if="detailTab === '响应'" class="evidence-code"><pre>{{ JSON.stringify(currentStep.responseSummary, null, 2) }}</pre></div><div v-else-if="detailTab === '提取'" class="extraction-evidence"><div v-if="Array.isArray(currentStep.extractions) && currentStep.extractions.length" v-for="(item, index) in currentStep.extractions" :key="index" class="extraction-row"><code>{{ typeof item === 'object' && item && 'variable' in item ? item.variable : `提取 ${index + 1}` }}</code><span>{{ typeof item === 'object' && item && 'matched' in item && item.matched ? '已命中' : '未命中' }}</span><pre>{{ JSON.stringify(typeof item === 'object' && item && 'value' in item ? item.value : item, null, 2) }}</pre></div><div v-else class="response-placeholder"><AppIcon name="code" /><span>本步骤没有提取结果。</span></div></div><div v-else-if="detailTab === '断言'" class="assertion-evidence"><div v-if="Array.isArray(currentStep.assertions) && currentStep.assertions.length" v-for="(item, index) in currentStep.assertions" :key="index" class="assertion-row" :class="item && typeof item === 'object' && 'passed' in item && item.passed ? 'pass' : 'fail'" data-testid="assertion-evidence-row"><span><AppIcon :name="item && typeof item === 'object' && 'passed' in item && item.passed ? 'check' : 'close'" /></span><div><strong>{{ assertionField(item, 'type') || `断言 ${index + 1}` }}</strong><code>{{ item && typeof item === 'object' && 'passed' in item ? (item.passed ? 'PASSED' : 'FAILED') : '—' }}</code><small v-if="assertionField(item, 'message')">{{ assertionField(item, 'message') }}</small><small v-if="assertionField(item, 'actual')">实际：{{ assertionField(item, 'actual') }}</small><small v-if="assertionField(item, 'expected')">期望：{{ assertionField(item, 'expected') }}</small></div><small>{{ item && typeof item === 'object' && 'passed' in item && item.passed ? '通过' : '失败' }}</small></div><div v-else class="assertion-row" :class="currentStep.status === 'PASSED' ? 'pass' : 'fail'"><span><AppIcon :name="currentStep.status === 'PASSED' ? 'check' : 'close'" /></span><div><strong>JMeter 断言汇总</strong><code>{{ currentStep.status }}</code></div><small>{{ currentStep.status }}</small></div></div><div v-else-if="detailTab === '日志'" class="error-evidence" data-testid="step-error-summary"><pre v-if="currentStep.errorSummary">{{ evidenceText(currentStep.errorSummary) }}</pre><div v-else class="response-placeholder"><AppIcon name="code" /><span>本步骤没有错误摘要。</span></div></div><footer class="evidence-footer"><span>Step ID: {{ currentStep.stepId }}</span><span>{{ formatDuration(currentStep.durationMs) }}</span></footer></section></div>
    <p v-if="errorMessage" class="studio-error" role="alert">{{ errorMessage }}</p>
  </section>
  <section v-else class="report-page">
    <header class="report-hero"><div class="report-state-mark"><AppIcon name="close" :size="24" /></div><div><span class="section-overline">RUN-240908-030</span><h2>订单支付回归</h2><p>原型报告；从运行中心打开真实运行后展示平台 API 证据。</p></div><div class="report-actions"><button class="secondary-button" disabled><AppIcon name="upload" :size="14" />导出报告</button></div></header>
    <div class="report-stats"><div><span>最终状态</span><strong class="danger-text">示例</strong></div><div><span>通过步骤</span><strong>—</strong></div><div><span>总耗时</span><strong>—</strong></div><div><span>环境</span><strong>—</strong></div><div><span>执行器</span><strong>—</strong></div></div>
    <div class="failure-summary"><span><AppIcon name="close" :size="16" /></span><div><strong>请从运行中心选择一次真实运行</strong><p>当前页面没有绑定 runId，因此不会把原型数据误认为真实报告。</p></div></div>
  </section>
</template>

<style scoped>
.success-text { color: #168a5a; }
.passed { color: #168a5a !important; background: #dff6ea !important; }
.passed-pill { color: #168a5a; background: #dff6ea; }
.studio-error { margin: 16px 0; color: var(--red); }
.extraction-evidence { padding: 12px 14px; }
.extraction-row { display: grid; grid-template-columns: 1fr auto 1.5fr; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--line); font-size: 9px; }
.extraction-row code { color: var(--blue); font-family: var(--mono); }
.extraction-row span { color: #168a5a; }
.extraction-row pre { margin: 0; overflow: hidden; color: #566174; font-family: var(--mono); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.data-row-label { color: #6f58b8; font-style: normal; font-weight: 600; }
.suite-report-members { margin: 16px 0; padding: 16px 18px; border: 1px solid var(--line); border-radius: 14px; background: #fff; }
.suite-report-members header { display: flex; align-items: end; justify-content: space-between; gap: 12px; }
.suite-report-members h3 { margin: 4px 0 0; }
.suite-report-members header small { color: var(--muted); }
.suite-member-filter { display: flex; align-items: center; gap: 8px; margin-left: auto; color: var(--muted); font-size: 10px; }
.suite-member-filter select { min-width: 150px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--ink); }
.suite-report-member-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px; margin-top: 12px; }
.suite-report-member { display: grid; grid-template-columns: 24px 20px 1fr auto; align-items: center; gap: 8px; min-width: 0; padding: 10px; border-radius: 10px; background: #f7f9fc; }
.suite-report-member strong, .suite-report-member small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.suite-report-member small { margin-top: 3px; color: var(--muted); font-size: 10px; }
.suite-report-member .member-index { color: var(--blue); font-weight: 700; text-align: center; }
.muted-text { color: var(--muted); }
</style>

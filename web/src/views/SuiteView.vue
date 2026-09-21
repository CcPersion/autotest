<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import { testSuiteApi as defaultApi, type TestSuite, type TestSuiteApi, type TestSuiteMember } from '../api/testSuite'
import { apiCaseApi, type ApiCase } from '../api/apiCase'
import { apiDefinitionApi, type ApiDefinition } from '../api/apiDefinition'
import { scenarioApi, type Scenario } from '../api/scenario'

export interface SuiteAssetCandidate {
  targetType: 'API_CASE' | 'SCENARIO'
  targetId: string
  name: string
  detail: string
}

export interface SuiteAssetApi {
  listDefinitions(projectId: string): Promise<ApiDefinition[]>
  listCases(projectId: string, definitionId: string): Promise<ApiCase[]>
  listScenarios(projectId: string): Promise<Scenario[]>
}

const defaultAssetApi: SuiteAssetApi = {
  listDefinitions: (projectId) => apiDefinitionApi.list(projectId),
  listCases: (projectId, definitionId) => apiCaseApi.list(projectId, definitionId),
  listScenarios: (projectId) => scenarioApi.list(projectId),
}

const props = withDefaults(defineProps<{
  projectId?: string | null
  environmentId?: string | null
  testSuiteApi?: TestSuiteApi
  assetApi?: SuiteAssetApi
}>(), { projectId: null, environmentId: null, testSuiteApi: undefined, assetApi: undefined })

const emit = defineEmits<{ navigate: [page: string]; 'run-created': [runId: string] }>()
const api = computed(() => props.testSuiteApi || defaultApi)
const suites = ref<TestSuite[]>([])
const selectedId = ref<string | null>(null)
const loading = ref(false)
const candidateLoading = ref(false)
const saving = ref(false)
const running = ref(false)
const error = ref('')
const notice = ref('')
const draft = ref<TestSuite | null>(null)
const candidates = ref<SuiteAssetCandidate[]>([])
const pickerOpen = ref(false)
const candidateSearch = ref('')
let requestSerial = 0
let candidateSerial = 0

const selected = computed(() => draft.value)

async function load(projectId: string | null = props.projectId) {
  const serial = ++requestSerial
  suites.value = []
  draft.value = null
  selectedId.value = null
  candidates.value = []
  pickerOpen.value = false
  error.value = ''
  if (!projectId) return
  loading.value = true
  try {
    const items = await api.value.list(projectId, false)
    if (serial !== requestSerial) return
    suites.value = items
    selectedId.value = items[0]?.id || null
    draft.value = items[0] ? cloneSuite(items[0]) : null
    void loadCandidates(projectId)
  } catch (cause) {
    if (serial === requestSerial) error.value = (cause as { message?: string })?.message || '测试集合加载失败'
  } finally {
    if (serial === requestSerial) loading.value = false
  }
}

async function loadCandidates(projectId: string) {
  const serial = ++candidateSerial
  candidateLoading.value = true
  try {
    const [definitions, scenarios] = await Promise.all([
      props.assetApi?.listDefinitions(projectId) ?? defaultAssetApi.listDefinitions(projectId),
      props.assetApi?.listScenarios(projectId) ?? defaultAssetApi.listScenarios(projectId),
    ])
    const activeDefinitions = definitions.filter((item) => !item.archived)
    const caseGroups = await Promise.all(activeDefinitions.map(async (definition) => ({
      definition,
      cases: await (props.assetApi?.listCases(projectId, definition.id) ?? defaultAssetApi.listCases(projectId, definition.id)),
    })))
    if (serial !== candidateSerial) return
    candidates.value = [
      ...caseGroups.flatMap(({ definition, cases }) => cases.filter((item) => !item.archived).map((item) => ({
        targetType: 'API_CASE' as const, targetId: item.id, name: item.name, detail: `${definition.method} · ${definition.name}`,
      }))),
      ...scenarios.filter((item) => !item.archived).map((item) => ({
        targetType: 'SCENARIO' as const, targetId: item.id, name: item.name, detail: '业务场景',
      })),
    ]
  } catch (cause) {
    if (serial === candidateSerial) error.value = (cause as { message?: string })?.message || '可选资产加载失败'
  } finally {
    if (serial === candidateSerial) candidateLoading.value = false
  }
}

function cloneSuite(value: TestSuite): TestSuite {
  return { ...value, members: value.members.map((member) => ({ ...member })) }
}

function selectSuite(value: TestSuite) {
  selectedId.value = value.id
  draft.value = cloneSuite(value)
  notice.value = ''
}

function memberLabel(member: TestSuiteMember) {
  return member.targetType === 'SCENARIO' ? `场景 · ${member.targetId}` : `用例 · ${member.targetId}`
}

function toggleMember(member: TestSuiteMember) {
  member.enabled = !member.enabled
}

const filteredCandidates = computed(() => {
  const keyword = candidateSearch.value.trim().toLowerCase()
  return candidates.value.filter((candidate) => !keyword
    || `${candidate.name} ${candidate.detail} ${candidate.targetId}`.toLowerCase().includes(keyword))
})

function hasMember(candidate: SuiteAssetCandidate) {
  return Boolean(draft.value?.members.some((member) => member.targetType === candidate.targetType && member.targetId === candidate.targetId))
}

function addCandidate(candidate: SuiteAssetCandidate) {
  if (!draft.value || hasMember(candidate)) return
  draft.value.members = [...draft.value.members, {
    id: crypto.randomUUID(), position: draft.value.members.length, targetType: candidate.targetType,
    targetId: candidate.targetId, enabled: true,
  }]
}

function removeMember(member: TestSuiteMember) {
  if (!draft.value) return
  draft.value.members = draft.value.members.filter((item) => item.id !== member.id)
    .map((item, position) => ({ ...item, position }))
}

async function createSuite() {
  if (!props.projectId || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const baseName = suites.value.some((item) => item.name === '新集合') ? `新集合 ${suites.value.length + 1}` : '新集合'
    const created = await api.value.create(props.projectId, {
      name: baseName, description: '', environmentId: props.environmentId, members: [],
    })
    suites.value = [...suites.value, created]
    selectSuite(created)
    notice.value = '已创建空集合，请添加成员'
  } catch (cause) {
    error.value = (cause as { message?: string })?.message || '测试集合创建失败'
  } finally {
    saving.value = false
  }
}

function moveMember(member: TestSuiteMember, offset: number) {
  if (!draft.value) return
  const index = draft.value.members.findIndex((item) => item.id === member.id)
  const next = index + offset
  if (index < 0 || next < 0 || next >= draft.value.members.length) return
  const members = [...draft.value.members]
  const [item] = members.splice(index, 1)
  members.splice(next, 0, item)
  draft.value.members = members.map((value, position) => ({ ...value, position }))
}

async function saveSuite() {
  if (!props.projectId || !draft.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const updated = await api.value.update(props.projectId, draft.value.id, {
      name: draft.value.name, description: draft.value.description || '',
      environmentId: props.environmentId || draft.value.environmentId,
      members: draft.value.members.map((member, position) => ({ ...member, position })), revision: draft.value.revision,
    })
    suites.value = suites.value.map((item) => item.id === updated.id ? updated : item)
    draft.value = cloneSuite(updated)
    notice.value = '测试集合已保存'
  } catch (cause) {
    error.value = (cause as { message?: string })?.message || '测试集合保存失败'
  } finally {
    saving.value = false
  }
}

async function runSuite() {
  if (!props.projectId || !draft.value || running.value) return
  const environmentId = props.environmentId || draft.value.environmentId
  if (!environmentId) { error.value = '请先选择运行环境'; return }
  running.value = true
  error.value = ''
  try {
    const run = await api.value.run(props.projectId, draft.value.id, environmentId, `suite-${draft.value.id}-${Date.now()}`)
    notice.value = '集合已提交运行'
    emit('run-created', run.id)
    emit('navigate', 'report')
  } catch (cause) {
    error.value = (cause as { message?: string })?.message || '测试集合运行失败'
  } finally {
    running.value = false
  }
}

watch(() => props.projectId, (projectId) => { void load(projectId) }, { immediate: true })
</script>

<template>
  <section class="suite-layout">
    <aside class="suite-list panel-surface">
      <div class="panel-heading between"><div><span class="section-overline">SUITES</span><h3>测试集合</h3></div><button class="icon-button" aria-label="新建集合" :disabled="!props.projectId || saving" @click="createSuite"><AppIcon name="plus" /></button></div>
      <div class="small-search"><AppIcon name="search" :size="14" /><input placeholder="搜索集合" /></div>
      <p v-if="!props.projectId" class="empty-hint">请先选择项目</p>
      <p v-else-if="loading" class="empty-hint">加载中…</p>
      <p v-else-if="!suites.length" class="empty-hint">暂无测试集合</p>
      <button v-for="item in suites" :key="item.id" class="suite-list-item" :class="{ active: item.id === selectedId }" @click="selectSuite(item)">
        <span><strong>{{ item.name }}</strong><small>{{ item.members.length }} 个成员 · {{ item.environmentId || '未选环境' }}</small></span><i class="health-good"></i>
      </button>
    </aside>
    <div class="suite-content panel-surface">
      <template v-if="selected">
        <header class="content-header"><div><span class="draft-pill">回归集合</span><h2>{{ selected.name }}</h2><p>{{ selected.description || '按固定顺序运行接口用例和场景。' }}</p></div><div><button class="secondary-button" data-action="add-member" :disabled="candidateLoading" @click="pickerOpen = true">添加成员</button><button class="secondary-button" data-action="save-suite" :disabled="saving" @click="saveSuite">{{ saving ? '保存中…' : '保存' }}</button><button class="primary-button" data-action="run-suite" :disabled="running" @click="runSuite"><span v-if="running" class="spinner"></span><AppIcon v-else name="play" :size="15" />{{ running ? '启动中' : '运行集合' }}</button></div></header>
        <div v-if="error" class="form-error" role="alert">{{ error }}</div><div v-if="notice" class="form-notice" role="status">{{ notice }}</div>
        <div class="suite-summary"><div><span>成员</span><strong>{{ selected.members.length }}</strong></div><div><span>执行方式</span><strong>串行</strong></div><div><span>启用成员</span><strong>{{ selected.members.filter((member) => member.enabled).length }}</strong></div><div><span>运行环境</span><strong>{{ props.environmentId || selected.environmentId || '未选择' }}</strong></div></div>
        <div class="members-header"><div><h3>执行顺序</h3><span>成员按 position 从小到大串行运行</span></div></div>
        <div class="member-list">
          <div v-for="(member, index) in selected.members" :key="member.id" class="member-row" data-testid="suite-member" :class="{ 'member-disabled': !member.enabled }">
            <span class="step-drag">⠿</span><span class="member-index">{{ index + 1 }}</span><span class="asset-symbol" :class="{ flow: member.targetType === 'SCENARIO' }"><AppIcon :name="member.targetType === 'SCENARIO' ? 'flow' : 'case'" /></span><div><strong>{{ member.targetId }}</strong><small>{{ memberLabel(member) }}</small></div><span><small>状态</small><strong :class="member.enabled ? 'success-text' : 'danger-text'">{{ member.enabled ? '已启用' : '已停用' }}</strong></span><span><small>顺序</small><strong>{{ member.position + 1 }}</strong></span><div class="member-actions"><button class="icon-button" data-action="toggle-member" @click="toggleMember(member)">{{ member.enabled ? '停用' : '启用' }}</button><button class="icon-button" :disabled="index === 0" @click="moveMember(member, -1)">↑</button><button class="icon-button" :disabled="index === selected.members.length - 1" @click="moveMember(member, 1)">↓</button><button class="icon-button" data-action="remove-member" @click="removeMember(member)">移除</button></div>
          </div>
        </div>
        <button class="text-link" @click="emit('navigate', 'runs')">查看运行记录 <AppIcon name="arrow" :size="14" /></button>
      </template>
      <div v-else class="empty-state"><strong>开始编排一组回归用例</strong><p>选择项目后，测试集合会把接口用例和场景按顺序串行执行。</p></div>
    </div>
    <div v-if="pickerOpen" class="suite-picker-mask" role="presentation" @click.self="pickerOpen = false">
      <section class="suite-picker panel-surface" role="dialog" aria-label="选择集合成员">
        <header class="panel-heading between"><div><span class="section-overline">ASSETS</span><h3>添加集合成员</h3></div><button class="icon-button" aria-label="关闭成员选择器" @click="pickerOpen = false">×</button></header>
        <div class="small-search"><AppIcon name="search" :size="14" /><input v-model="candidateSearch" placeholder="搜索接口用例或场景" /></div>
        <p v-if="candidateLoading" class="empty-hint">正在加载可选资产…</p>
        <p v-else-if="!filteredCandidates.length" class="empty-hint">没有可添加的活动接口用例或场景</p>
        <div v-else class="candidate-list"><div v-for="candidate in filteredCandidates" :key="`${candidate.targetType}-${candidate.targetId}`" class="candidate-row"><span class="asset-symbol" :class="{ flow: candidate.targetType === 'SCENARIO' }"><AppIcon :name="candidate.targetType === 'SCENARIO' ? 'flow' : 'case'" /></span><div><strong>{{ candidate.name }}</strong><small>{{ candidate.detail }} · {{ candidate.targetId }}</small></div><button class="secondary-button" :data-action="`pick-${candidate.targetId}`" :disabled="hasMember(candidate)" @click="addCandidate(candidate)">{{ hasMember(candidate) ? '已添加' : '添加' }}</button></div></div>
        <footer class="picker-footer"><button class="secondary-button" @click="pickerOpen = false">完成</button></footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.suite-picker-mask { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 24px; background: rgba(18, 23, 33, .38); }
.suite-picker { width: min(720px, 100%); max-height: min(680px, 90vh); overflow: auto; padding: 22px; box-shadow: 0 24px 80px rgba(18, 23, 33, .18); }
.candidate-list { display: grid; gap: 8px; margin-top: 14px; }
.candidate-row { display: grid; grid-template-columns: 32px 1fr auto; align-items: center; gap: 12px; padding: 11px 12px; border: 1px solid var(--line); border-radius: 10px; background: #fff; }
.candidate-row strong, .candidate-row small { display: block; }
.candidate-row small { margin-top: 3px; color: var(--muted); font-size: 10px; }
.picker-footer { display: flex; justify-content: flex-end; margin-top: 18px; }
</style>

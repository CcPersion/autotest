<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import AppIcon from '../components/AppIcon.vue'
import { scenarioApi, type Scenario as ApiScenario, type ScenarioStepInput, type ScenarioWrite } from '../api/scenario'
import type { JsonObject } from '../api/apiDefinition'
import { apiDefinitionApi, type ApiDefinition } from '../api/apiDefinition'
import { apiCaseApi, type ApiCase } from '../api/apiCase'
import { runApi } from '../api/run'
import { jdbcDataSourceApi, type JdbcDataSource } from '../api/jdbcDataSource'
import { redisDataSourceApi, type RedisDataSource } from '../api/redisDataSource'
import {
  advanceRunStatus,
  completeRunSteps,
  scenarioStepPresentation,
  setScenarioReferenceMode,
  toggleScenarioStepExpanded,
  type PrototypeRunStatus,
  type ScenarioReferenceMode,
} from '../prototype/state'

const emit = defineEmits<{ navigate: [page: string]; runCreated: [runId: string] }>()
const props = defineProps<{ projectId?: string | null; environmentId?: string | null }>()

type ScenarioTab = 'steps' | 'variables' | 'hooks' | 'settings'

interface ScenarioStep {
  id: string
  kind: string
  title: string
  detail: string
  accent: string
  status: 'passed' | 'running' | 'pending'
  section: 'main' | 'cleanup'
  depth: number
  parentId?: string
  expanded: boolean
  referenceMode?: ScenarioReferenceMode
  apiCaseId?: string | null
  failureStrategy?: 'STOP' | 'CONTINUE' | 'RETRY'
  httpMethod?: string
  httpPath?: string
  httpBodyType?: 'NONE' | 'JSON' | 'TEXT'
  httpBody?: string
  sqlDataSourceId?: string | null
  sqlText?: string
  sqlAssertionExpected?: string
  redisDataSourceId?: string | null
  redisCommand?: 'GET' | 'SET' | 'DEL' | 'EXISTS'
  redisKey?: string
  redisValue?: string
  redisAssertionExpected?: string
  conditionLeft?: string
  conditionOperator?: 'EQUALS' | 'NOT_EQUALS' | 'CONTAINS' | 'NOT_CONTAINS' | 'GREATER_THAN' | 'LESS_THAN' | 'EXISTS' | 'NOT_EXISTS'
  conditionRight?: string
  branch?: 'THEN' | 'ELSE' | 'BODY'
  loopMode?: 'FIXED' | 'LIST' | 'WHILE'
  loopCount?: number
  loopItems?: string
  loopItemVariable?: string
  loopMaxIterations?: number
  waitMillis?: number
  retryMaxAttempts?: number
  retryIntervalMillis?: number
}

const steps = ref<ScenarioStep[]>([
  { id: 'step-01', kind: '引用接口用例', title: '获取访问令牌', detail: 'POST /oauth/token', accent: 'blue', status: 'passed', section: 'main', depth: 0, expanded: true, referenceMode: 'reference' },
  { id: 'step-02', kind: '提取', title: '保存访问令牌', detail: '$.data.accessToken → token', accent: 'violet', status: 'passed', section: 'main', depth: 0, expanded: false },
  { id: 'step-03', kind: '引用接口用例', title: '创建个人签署订单', detail: 'POST /v2/orders/person', accent: 'blue', status: 'running', section: 'main', depth: 0, expanded: true, referenceMode: 'reference' },
  { id: 'step-04', kind: '条件', title: '订单创建成功时', detail: '${createCode} == 0', accent: 'amber', status: 'pending', section: 'main', depth: 0, expanded: true },
  { id: 'step-05', kind: '自定义 HTTP', title: '查询订单状态', detail: 'GET /v2/orders/${orderId}', accent: 'blue', status: 'pending', section: 'main', depth: 1, parentId: 'step-04', expanded: false },
  { id: 'step-06', kind: 'SQL', title: '校验订单入库状态', detail: 'contract_order · 2 个断言', accent: 'green', status: 'pending', section: 'main', depth: 1, parentId: 'step-04', expanded: false },
  { id: 'step-07', kind: '等待', title: '等待异步签署结果', detail: '1,500 ms', accent: 'slate', status: 'pending', section: 'main', depth: 0, expanded: false },
  { id: 'step-08', kind: '清理', title: '删除测试订单', detail: '始终尝试执行', accent: 'red', status: 'pending', section: 'cleanup', depth: 0, expanded: false },
])

const selectedId = ref('step-03')
const activeTab = ref<ScenarioTab>('steps')
const addMenuOpen = ref(false)
const runStatus = ref<PrototypeRunStatus>('idle')
const scenarioItems = ref<ApiScenario[]>([])
const selectedScenarioId = ref<string | null>(null)
const scenarioName = ref('合同签署核心链路')
const scenarioLoading = ref(false)
const scenarioSaving = ref(false)
const scenarioError = ref('')
const backendMode = computed(() => Boolean(props.projectId))
const caseCatalog = ref<Array<{ id: string; label: string; definitionName: string; method: string; url: string }>>([])
const jdbcSources = ref<JdbcDataSource[]>([])
const redisSources = ref<RedisDataSource[]>([])
const dragStepId = ref<string | null>(null)
const selected = computed(() => steps.value.find((item) => item.id === selectedId.value) ?? steps.value[0]!)
const mainSteps = computed(() => steps.value.filter((step) => step.section === 'main'))
const cleanupSteps = computed(() => steps.value.filter((step) => step.section === 'cleanup'))

const components = [
  { icon: 'case', label: '引用接口用例', color: 'blue', kind: '引用接口用例' },
  { icon: 'api', label: '自定义 HTTP', color: 'blue', kind: '自定义 HTTP' },
  { icon: 'database', label: 'SQL', color: 'green', kind: 'SQL' },
  { icon: 'database', label: 'Redis', color: 'red', kind: 'Redis' },
  { icon: 'flow', label: '条件', color: 'amber', kind: '条件' },
  { icon: 'runs', label: '循环', color: 'indigo', kind: '循环' },
  { icon: 'clock', label: '等待', color: 'slate', kind: '等待' },
  { icon: 'close', label: '清理', color: 'red', kind: '清理' },
]

const kindToApi: Record<string, ScenarioStepInput['kind']> = {
  '引用接口用例': 'API_CASE', '自定义 HTTP': 'HTTP', 'SQL': 'SQL', 'Redis': 'REDIS',
  '条件': 'CONDITION', '循环': 'LOOP', '等待': 'WAIT', '清理': 'CLEANUP', '提取': 'HTTP',
}

function apiKindToUi(kind: ApiScenario['steps'][number]['kind']) {
  return ({ API_CASE: '引用接口用例', HTTP: '自定义 HTTP', SQL: 'SQL', REDIS: 'Redis', CONDITION: '条件', LOOP: '循环', WAIT: '等待', CLEANUP: '清理' } as Record<string, string>)[kind] || kind
}

function stepAccent(kind: string) {
  return components.find((item) => item.kind === kind)?.color || 'blue'
}

function loadScenarioDraft(item: ApiScenario) {
  scenarioName.value = item.name
  selectedScenarioId.value = item.id
  steps.value = item.steps.map((step) => ({
    id: step.id,
    kind: apiKindToUi(step.kind),
    title: step.title,
    detail: typeof step.stepConfig.detail === 'string' ? step.stepConfig.detail : '等待配置',
    accent: stepAccent(apiKindToUi(step.kind)),
    status: 'pending',
    section: step.section === 'CLEANUP' ? 'cleanup' : 'main',
    depth: 0,
    parentId: step.parentId || undefined,
    expanded: false,
    referenceMode: step.referenceMode === 'COPY' ? 'copy' : step.referenceMode === 'REFERENCE' ? 'reference' : undefined,
    apiCaseId: step.apiCaseId,
    failureStrategy: step.failureStrategy,
    ...(step.kind === 'HTTP' ? httpFields(step.stepConfig) : {}),
    ...(step.kind === 'SQL' ? sqlFields(step.stepConfig) : {}),
    ...(step.kind === 'REDIS' ? redisFields(step.stepConfig) : {}),
    ...(step.kind === 'CONDITION' ? conditionFields(step.stepConfig) : {}),
    ...(step.kind === 'LOOP' ? loopFields(step.stepConfig) : {}),
    ...(step.kind === 'WAIT' ? { waitMillis: typeof step.stepConfig.waitMillis === 'number' ? step.stepConfig.waitMillis : 0 } : {}),
    branch: step.stepConfig.branch === 'ELSE' || step.stepConfig.branch === 'BODY' ? step.stepConfig.branch : 'THEN',
    ...(step.stepConfig.retry && typeof step.stepConfig.retry === 'object' && !Array.isArray(step.stepConfig.retry) ? retryFields(step.stepConfig.retry as JsonObject) : {}),
  }))
  const byId = new Map(steps.value.map((step) => [step.id, step]))
  steps.value.forEach((step) => {
    let parent = step.parentId ? byId.get(step.parentId) : undefined
    let depth = 0
    while (parent && depth < 10) { depth += 1; parent = parent.parentId ? byId.get(parent.parentId) : undefined }
    step.depth = depth
  })
  selectedId.value = steps.value[0]?.id || ''
}

function sqlFields(stepConfig: JsonObject) {
  return { sqlDataSourceId: typeof stepConfig.dataSourceId === 'string' ? stepConfig.dataSourceId : null, sqlText: typeof stepConfig.sql === 'string' ? stepConfig.sql : 'SELECT 1', sqlAssertionExpected: typeof stepConfig.assertions === 'object' && Array.isArray(stepConfig.assertions) && stepConfig.assertions[0] && typeof stepConfig.assertions[0] === 'object' && typeof (stepConfig.assertions[0] as JsonObject).expected === 'string' ? (stepConfig.assertions[0] as JsonObject).expected as string : '1' }
}

function redisFields(stepConfig: JsonObject) {
  return { redisDataSourceId: typeof stepConfig.dataSourceId === 'string' ? stepConfig.dataSourceId : null, redisCommand: (typeof stepConfig.command === 'string' && ['GET', 'SET', 'DEL', 'EXISTS'].includes(stepConfig.command) ? stepConfig.command : 'GET') as 'GET' | 'SET' | 'DEL' | 'EXISTS', redisKey: typeof stepConfig.key === 'string' ? stepConfig.key : '', redisValue: typeof stepConfig.value === 'string' ? stepConfig.value : '', redisAssertionExpected: typeof stepConfig.assertions === 'object' && Array.isArray(stepConfig.assertions) && stepConfig.assertions[0] && typeof stepConfig.assertions[0] === 'object' ? String((stepConfig.assertions[0] as JsonObject).expected ?? '') : '' }
}

function conditionFields(stepConfig: JsonObject) {
  const operators = ['EQUALS', 'NOT_EQUALS', 'CONTAINS', 'NOT_CONTAINS', 'GREATER_THAN', 'LESS_THAN', 'EXISTS', 'NOT_EXISTS'] as const
  return {
    conditionLeft: typeof stepConfig.left === 'string' ? stepConfig.left : '${status}',
    conditionOperator: (typeof stepConfig.operator === 'string' && operators.includes(stepConfig.operator as typeof operators[number]) ? stepConfig.operator : 'EQUALS') as typeof operators[number],
    conditionRight: stepConfig.right == null ? '' : String(stepConfig.right),
  }
}

function loopFields(stepConfig: JsonObject) {
  const mode = stepConfig.mode === 'LIST' || stepConfig.mode === 'WHILE' ? stepConfig.mode : 'FIXED'
  return {
    loopMode: mode as 'FIXED' | 'LIST' | 'WHILE',
    loopCount: typeof stepConfig.count === 'number' ? stepConfig.count : 1,
    loopItems: typeof stepConfig.items === 'string' ? stepConfig.items : '${ids}',
    loopItemVariable: typeof stepConfig.itemVariable === 'string' ? stepConfig.itemVariable : 'item',
    loopMaxIterations: typeof stepConfig.maxIterations === 'number' ? stepConfig.maxIterations : 100,
    conditionLeft: typeof stepConfig.left === 'string' ? stepConfig.left : '${ready}',
    conditionOperator: typeof stepConfig.operator === 'string' ? stepConfig.operator as ScenarioStep['conditionOperator'] : 'EQUALS',
    conditionRight: stepConfig.right == null ? 'true' : String(stepConfig.right),
  }
}

function retryFields(retry: JsonObject) {
  return {
    retryMaxAttempts: typeof retry.maxAttempts === 'number' ? retry.maxAttempts : 2,
    retryIntervalMillis: typeof retry.intervalMillis === 'number' ? retry.intervalMillis : 500,
  }
}

function httpFields(stepConfig: JsonObject) {
  const plan = stepConfig.plan && typeof stepConfig.plan === 'object' && !Array.isArray(stepConfig.plan)
    ? stepConfig.plan as JsonObject : {} as JsonObject
  const body = plan.body && typeof plan.body === 'object' && !Array.isArray(plan.body)
    ? plan.body as JsonObject : {} as JsonObject
  return {
    httpMethod: typeof plan.method === 'string' ? plan.method : 'GET',
    httpPath: typeof plan.urlTemplate === 'string' ? plan.urlTemplate : '/actuator/health',
    httpBodyType: (body.type === 'JSON' || body.type === 'TEXT' ? body.type : 'NONE') as 'NONE' | 'JSON' | 'TEXT',
    httpBody: typeof body.value === 'string' ? body.value : body.value == null ? '' : JSON.stringify(body.value, null, 2),
  }
}

async function loadScenarios(projectId: string | null | undefined) {
  scenarioItems.value = []
  selectedScenarioId.value = null
  scenarioError.value = ''
  if (!projectId) return
  scenarioLoading.value = true
  try {
    const [items] = await Promise.all([scenarioApi.list(projectId), loadCaseCatalog(projectId), loadJdbcCatalog(projectId), loadRedisCatalog(projectId)])
    scenarioItems.value = items
    if (items[0]) loadScenarioDraft(items[0])
    else {
      scenarioName.value = '新建场景'
      steps.value = [{ id: crypto.randomUUID(), kind: '等待', title: '等待配置', detail: '等待配置', accent: 'slate', status: 'pending', section: 'main', depth: 0, expanded: true, failureStrategy: 'STOP' }]
      selectedId.value = steps.value[0].id
    }
  } catch (error) {
    scenarioError.value = (error as { message?: string })?.message || '场景加载失败'
  } finally {
    scenarioLoading.value = false
  }
}

async function loadJdbcCatalog(projectId: string) {
  jdbcSources.value = props.environmentId ? await jdbcDataSourceApi.list(projectId, props.environmentId, false) : []
}

async function loadRedisCatalog(projectId: string) {
  redisSources.value = props.environmentId ? await redisDataSourceApi.list(projectId, props.environmentId, false) : []
}

async function loadCaseCatalog(projectId: string) {
  const definitions: ApiDefinition[] = await apiDefinitionApi.list(projectId, null, false)
  const grouped = await Promise.all(definitions.map(async (definition) => {
    const cases: ApiCase[] = await apiCaseApi.list(projectId, definition.id, false)
    return cases.map((item) => ({ id: item.id, label: `${item.name} · ${definition.method} ${definition.urlTemplate}`, definitionName: definition.name, method: definition.method, url: definition.urlTemplate }))
  }))
  caseCatalog.value = grouped.flat()
}

function scenarioWrite(): ScenarioWrite {
  const idMap = new Map<string, string>()
  steps.value.forEach((step) => idMap.set(step.id, /^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(step.id) ? step.id : crypto.randomUUID()))
  const scenario = scenarioItems.value.find((item) => item.id === selectedScenarioId.value)
  const retryConfig = (step: ScenarioStep) => step.failureStrategy === 'RETRY'
    ? { retry: { maxAttempts: Math.max(2, Math.min(5, Number(step.retryMaxAttempts || 2))), intervalMillis: Math.max(0, Math.min(60000, Number(step.retryIntervalMillis || 0))), retryOn: ['CONNECT', 'TIMEOUT'] } }
    : {}
  return {
    name: scenarioName.value.trim(), description: '', variables: {} as JsonObject, settings: {} as JsonObject, revision: scenario?.revision,
    steps: steps.value.map((step, index) => ({
      id: idMap.get(step.id)!, parentId: step.parentId ? (idMap.get(step.parentId) || null) : null, position: index,
      kind: step.kind === '清理' ? 'HTTP' : (kindToApi[step.kind] || 'HTTP'), title: step.title.trim(), enabled: true,
      section: step.section === 'cleanup' ? 'CLEANUP' : 'MAIN', referenceMode: step.kind === '引用接口用例' ? (step.referenceMode === 'copy' ? 'COPY' : 'REFERENCE') : null,
      apiCaseId: step.kind === '引用接口用例' ? (step.apiCaseId || null) : null,
      failureStrategy: step.failureStrategy || (step.section === 'cleanup' ? 'CONTINUE' : 'STOP'),
      stepConfig: (step.kind === '等待'
        ? { detail: step.detail, waitMillis: Math.max(0, Number(step.waitMillis ?? (Number.parseInt(step.detail.replace(/[^0-9]/g, ''), 10) || 0))), branch: step.branch || 'THEN', ...retryConfig(step) }
        : step.kind === '自定义 HTTP' || step.kind === '清理'
          ? { detail: `${step.httpMethod || 'GET'} ${step.httpPath || '/actuator/health'}`, plan: {
              method: step.httpMethod || 'GET', urlTemplate: step.httpPath || '/actuator/health', query: [], headers: [], cookies: [],
              body: { type: step.httpBodyType || 'NONE', ...(step.httpBodyType && step.httpBodyType !== 'NONE' ? { value: step.httpBody || '' } : {}) },
              variables: {}, variableScopes: {}, extractors: [], assertions: [], options: {},
            }, branch: step.branch || 'THEN', ...retryConfig(step) }
          : step.kind === 'SQL'
            ? { detail: `${step.sqlDataSourceId ? 'JDBC' : '选择数据源'} · SQL`, dataSourceId: step.sqlDataSourceId || '', sql: step.sqlText || 'SELECT 1', parameters: {}, extractors: [], assertions: [{ type: 'ROW_COUNT', operator: 'EQUALS', expected: Number(step.sqlAssertionExpected || 1) }], allowWrite: false, confirmed: false, branch: step.branch || 'THEN', ...retryConfig(step) }
            : step.kind === 'Redis'
              ? { detail: `${step.redisDataSourceId ? 'Redis' : '选择数据源'} · ${step.redisCommand || 'GET'}`, dataSourceId: step.redisDataSourceId || '', command: step.redisCommand || 'GET', key: step.redisKey || '', ...(step.redisCommand === 'SET' ? { value: step.redisValue || '' } : {}), extractors: [], assertions: step.redisAssertionExpected ? [{ type: 'EQUALS', expected: step.redisAssertionExpected }] : [], allowWrite: false, confirmed: false, branch: step.branch || 'THEN', ...retryConfig(step) }
          : step.kind === '条件'
            ? { detail: `${step.conditionLeft || '${status}'} ${step.conditionOperator || 'EQUALS'} ${step.conditionRight || ''}`.trim(), left: step.conditionLeft || '${status}', operator: step.conditionOperator || 'EQUALS', branch: step.branch || 'THEN', ...(step.conditionOperator !== 'EXISTS' && step.conditionOperator !== 'NOT_EXISTS' ? { right: step.conditionRight || '' } : {}), ...retryConfig(step) }
          : step.kind === '循环'
            ? { detail: `${step.loopMode || 'FIXED'} · ${step.loopMode === 'LIST' ? step.loopItems || '${ids}' : step.loopMode === 'WHILE' ? `${step.conditionLeft || '${ready}'} ${step.conditionOperator || 'EQUALS'} ${step.conditionRight || 'true'}` : `${step.loopCount || 1} 次`}`, mode: step.loopMode || 'FIXED', branch: 'BODY', ...(step.loopMode === 'LIST' ? { items: step.loopItems || '${ids}', itemVariable: step.loopItemVariable || 'item' } : step.loopMode === 'WHILE' ? { left: step.conditionLeft || '${ready}', operator: step.conditionOperator || 'EQUALS', right: step.conditionRight || 'true' } : { count: Math.max(1, Number(step.loopCount || 1)) }), maxIterations: Math.max(1, Number(step.loopMaxIterations || 100)), ...retryConfig(step) }
          : { detail: step.detail, branch: step.branch || 'THEN', ...retryConfig(step) }) as JsonObject,
    })),
  }
}

async function saveScenario() {
  if (!props.projectId || scenarioSaving.value) return
  scenarioError.value = ''
  scenarioSaving.value = true
  try {
    const input = scenarioWrite()
    const saved = selectedScenarioId.value
      ? await scenarioApi.update(props.projectId, selectedScenarioId.value, input)
      : await scenarioApi.create(props.projectId, input)
    scenarioItems.value = scenarioItems.value.some((item) => item.id === saved.id)
      ? scenarioItems.value.map((item) => item.id === saved.id ? saved : item)
      : [saved, ...scenarioItems.value]
    loadScenarioDraft(saved)
  } catch (error) {
    scenarioError.value = (error as { message?: string })?.message || '场景保存失败'
  } finally {
    scenarioSaving.value = false
  }
}

watch(() => props.projectId, (projectId) => { void loadScenarios(projectId) }, { immediate: true })

function stepIcon(step: ScenarioStep) {
  return scenarioStepPresentation(step.kind).icon
}

function isVisible(step: ScenarioStep) {
  return !step.parentId || steps.value.find((parent) => parent.id === step.parentId)?.expanded
}

function addStep(component: typeof components[number]) {
  const id = `step-${String(steps.value.length + 1).padStart(2, '0')}`
  const isCleanup = component.kind === '清理'
  const parent = !isCleanup && (selected.value.kind === '条件' || selected.value.kind === '循环') ? selected.value : undefined
  steps.value.push({
    id,
    kind: component.kind,
    title: `新建${component.label}步骤`,
    detail: '等待配置',
    accent: component.color,
    status: 'pending',
    section: isCleanup ? 'cleanup' : 'main',
    depth: parent ? parent.depth + 1 : 0,
    parentId: parent?.id,
    expanded: false,
    ...(component.kind === '引用接口用例' ? { referenceMode: 'reference' as const } : {}),
    ...(component.kind === '自定义 HTTP' || component.kind === '清理' ? httpFields({}) : {}),
    ...(component.kind === 'SQL' ? sqlFields({}) : {}),
    ...(component.kind === 'Redis' ? redisFields({}) : {}),
    ...(component.kind === '条件' ? conditionFields({}) : {}),
    ...(component.kind === '循环' ? loopFields({}) : {}),
    ...(component.kind === '等待' ? { waitMillis: 1000 } : {}),
    ...(component.kind === '条件' || component.kind === '循环' || component.kind === '等待' || component.kind === '自定义 HTTP' || component.kind === 'SQL' || component.kind === 'Redis' ? retryFields({}) : {}),
  })
  selectedId.value = id
  addMenuOpen.value = false
}

function toggleExpanded(step: ScenarioStep) {
  steps.value = toggleScenarioStepExpanded(steps.value, step.id) as ScenarioStep[]
}

function changeReferenceMode(mode: ScenarioReferenceMode) {
  steps.value = setScenarioReferenceMode(steps.value, selected.value.id, mode) as ScenarioStep[]
}

async function runScenario() {
  if (backendMode.value) {
    if (!props.projectId || !props.environmentId || !selectedScenarioId.value) {
      scenarioError.value = '请先保存场景并选择运行环境'
      return
    }
    if (runStatus.value === 'pending' || runStatus.value === 'running') return
    scenarioError.value = ''
    runStatus.value = 'pending'
    try {
      const run = await scenarioApi.run(props.projectId, selectedScenarioId.value, props.environmentId, `scenario-${crypto.randomUUID()}`)
      emit('runCreated', run.id)
      runStatus.value = 'running'
      for (let attempt = 0; attempt < 60; attempt += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 500))
        const latest = await runApi.get(props.projectId, run.id)
        if (['PASSED', 'FAILED', 'CANCELED', 'INTERRUPTED'].includes(latest.status)) {
          runStatus.value = latest.status === 'PASSED' ? 'passed' : 'failed'
          emit('navigate', 'report')
          return
        }
      }
      scenarioError.value = '运行仍在队列中，可到运行中心查看进度'
    } catch (error) {
      scenarioError.value = (error as { message?: string })?.message || '场景运行创建失败'
      runStatus.value = 'failed'
    }
    return
  }
  runStatus.value = advanceRunStatus(runStatus.value)
  if (runStatus.value === 'pending') {
    window.setTimeout(() => { runStatus.value = 'running' }, 500)
    window.setTimeout(() => {
      runStatus.value = 'passed'
      steps.value = completeRunSteps(steps.value) as ScenarioStep[]
    }, 1700)
  }
}

function moveStep(target: ScenarioStep) {
  const sourceId = dragStepId.value
  dragStepId.value = null
  if (!sourceId || sourceId === target.id) return
  const source = steps.value.find((step) => step.id === sourceId)
  if (!source || source.section !== target.section) return
  const sourceIndex = steps.value.findIndex((step) => step.id === sourceId)
  const targetIndex = steps.value.findIndex((step) => step.id === target.id)
  if (sourceIndex < 0 || targetIndex < 0) return
  const reordered = [...steps.value]
  const [removed] = reordered.splice(sourceIndex, 1)
  reordered.splice(reordered.findIndex((step) => step.id === target.id), 0, removed)
  steps.value = reordered
}
</script>

<template>
  <section class="scenario-shell scenario-shell-v2">
    <aside class="component-palette">
      <div class="panel-heading"><span class="section-overline">SCENARIO BUILDER</span><h3>添加步骤</h3></div>
      <p class="palette-description">按执行顺序编排步骤；条件和循环会自动形成父子层级。</p>
      <div class="component-list">
        <button v-for="component in components" :key="component.label" @click="addStep(component)">
          <span class="component-icon" :class="component.color"><AppIcon :name="component.icon" :size="17" /></span><span>{{ component.label }}</span><AppIcon name="plus" :size="15" />
        </button>
      </div>
      <div class="palette-tip"><AppIcon name="case" :size="17" /><p><strong>引用还是复制？</strong><small>公共登录和造数流程建议引用已有用例，源用例更新后会同步。</small></p></div>
    </aside>

    <div class="scenario-canvas">
      <header class="scenario-toolbar">
        <div><span class="scenario-state">{{ backendMode ? (scenarioLoading ? '加载中' : '已接入') : '草稿' }}</span><input v-if="backendMode" v-model="scenarioName" class="scenario-name-input" aria-label="场景名称" /><strong v-else>合同签署核心链路</strong><small>{{ steps.length }} 个步骤 · {{ backendMode ? '当前项目场景' : '更新于 3 分钟前' }}</small></div>
        <div><select v-if="backendMode" v-model="selectedScenarioId" aria-label="当前场景" @change="selectedScenarioId && loadScenarioDraft(scenarioItems.find((item) => item.id === selectedScenarioId)!)"><option value="">新建场景</option><option v-for="item in scenarioItems" :key="item.id" :value="item.id">{{ item.name }}</option></select><button class="secondary-button">校验场景</button><button class="secondary-button" :disabled="scenarioSaving" @click="saveScenario">{{ scenarioSaving ? '保存中…' : '保存' }}</button><button class="primary-button" @click="runScenario"><span v-if="runStatus === 'running'" class="spinner"></span><AppIcon v-else name="play" :size="16" />{{ runStatus === 'idle' ? '运行场景' : runStatus === 'pending' ? '排队中' : runStatus === 'running' ? '运行中' : '再次运行' }}</button></div>
      </header>
      <p v-if="scenarioError" class="scenario-error" role="alert">{{ scenarioError }}</p>

      <nav class="scenario-tabs" aria-label="场景配置区域">
        <button :class="{ active: activeTab === 'steps' }" @click="activeTab = 'steps'">步骤 <span>{{ steps.length }}</span></button>
        <button :class="{ active: activeTab === 'variables' }" @click="activeTab = 'variables'">场景变量 <span>4</span></button>
        <button :class="{ active: activeTab === 'hooks' }" @click="activeTab = 'hooks'">前后置 <span>2</span></button>
        <button :class="{ active: activeTab === 'settings' }" @click="activeTab = 'settings'">设置</button>
      </nav>

      <div v-if="runStatus !== 'idle'" class="run-strip" :class="runStatus">
        <span class="run-indicator"><i></i></span><div><strong>{{ runStatus === 'passed' ? '场景运行通过' : runStatus === 'running' ? '正在执行：创建个人签署订单' : '正在等待 Runner' }}</strong><small>{{ runStatus === 'passed' ? `${steps.length} / ${steps.length} 步骤通过 · 2.34 秒` : runStatus === 'running' ? '当前 3 / 8 · 已运行 1.12 秒' : '队列前方没有任务' }}</small></div>
        <button v-if="runStatus === 'passed'" @click="emit('navigate', 'report')">查看报告 <AppIcon name="arrow" :size="15" /></button><button v-else>取消</button>
      </div>

      <div v-if="activeTab === 'steps'" class="scenario-steps-pane">
        <div class="canvas-meta"><div><span>主流程</span><strong>{{ mainSteps.length }}</strong></div><div><span>清理流程</span><strong>{{ cleanupSteps.length }}</strong></div><div><span>场景变量</span><strong>4</strong></div><button><AppIcon name="more" /></button></div>

        <div class="step-track step-tree">
          <div class="tree-section"><div class="tree-section-heading"><span class="tree-dot main"></span><strong>主流程</strong><small>按顺序执行</small></div>
            <template v-for="(step, index) in mainSteps" :key="step.id">
              <template v-if="isVisible(step)">
                <div class="scenario-step" role="button" tabindex="0" draggable="true" :class="[{ selected: selectedId === step.id, indent: step.depth > 0 }, step.status]" @click="selectedId = step.id" @keydown.enter="selectedId = step.id" @dragstart="dragStepId = step.id" @dragover.prevent @drop="moveStep(step)">
                  <span class="step-drag">⠿</span><span class="step-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="component-icon" :class="step.accent"><AppIcon :name="stepIcon(step)" :size="17" /></span>
                  <span class="step-info"><span><small>{{ step.kind }}</small><strong>{{ step.title }}</strong></span><code>{{ step.detail }}</code></span>
                  <span v-if="step.status === 'passed'" class="step-result passed"><AppIcon name="check" :size="15" />428 ms</span><span v-else-if="step.status === 'running'" class="step-result running"><i></i>执行中</span><span v-else class="step-result pending">待执行</span>
                  <button class="step-expand" :aria-label="step.expanded ? '收起步骤配置' : '展开步骤配置'" @click.stop="toggleExpanded(step)"><AppIcon name="chevron" :size="15" /></button>
                </div>
                <div v-if="step.expanded" class="scenario-step-detail" :class="{ indent: step.depth > 0 }">
                  <span><b>请求</b>{{ step.detail }}</span><span><b>前置</b>{{ step.kind === '引用接口用例' ? '继承用例配置' : '无' }}</span><span><b>后置</b>无</span><span><b>提取</b>{{ step.kind === '提取' ? '$.data.accessToken → token' : step.kind === '引用接口用例' ? 'token' : '未配置' }}</span><span><b>断言</b>{{ step.kind === 'SQL' ? '2 个断言' : '状态码 200' }}</span>
                </div>
                <span v-if="index < mainSteps.length - 1" class="step-connector" :class="{ indent: mainSteps[index + 1]?.depth > 0 }"></span>
              </template>
            </template>
          </div>

          <div class="tree-section cleanup-section"><div class="tree-section-heading"><span class="tree-dot cleanup"></span><strong>清理流程</strong><small>主流程结束后执行</small></div>
            <template v-for="step in cleanupSteps" :key="step.id">
              <div class="scenario-step" role="button" tabindex="0" draggable="true" :class="[{ selected: selectedId === step.id }, step.status]" @click="selectedId = step.id" @keydown.enter="selectedId = step.id" @dragstart="dragStepId = step.id" @dragover.prevent @drop="moveStep(step)"><span class="step-drag">⠿</span><span class="step-index">CL</span><span class="component-icon" :class="step.accent"><AppIcon :name="stepIcon(step)" :size="17" /></span><span class="step-info"><span><small>{{ step.kind }}</small><strong>{{ step.title }}</strong></span><code>{{ step.detail }}</code></span><span class="step-result pending">始终执行</span><button class="step-expand" :aria-label="step.expanded ? '收起步骤配置' : '展开步骤配置'" @click.stop="toggleExpanded(step)"><AppIcon name="chevron" :size="15" /></button></div>
              <div v-if="step.expanded" class="scenario-step-detail"><span><b>请求</b>{{ step.detail }}</span><span><b>前置</b>无</span><span><b>后置</b>清理资源</span><span><b>提取</b>未配置</span><span><b>断言</b>状态码 200</span></div>
            </template>
          </div>
          <div class="add-step-wrap"><button class="canvas-add" @click="addMenuOpen = !addMenuOpen"><AppIcon name="plus" :size="16" />添加步骤 <AppIcon name="chevron" :size="14" /></button><div v-if="addMenuOpen" class="add-step-menu"><button v-for="component in components" :key="component.kind" @click="addStep(component)"><span class="component-icon" :class="component.color"><AppIcon :name="component.icon" :size="15" /></span>{{ component.label }}</button></div></div>
        </div>
      </div>

      <div v-else class="scenario-tab-panel">
        <div v-if="activeTab === 'variables'" class="scenario-config-grid"><div><span class="section-overline">VARIABLES</span><h3>场景变量</h3><p>变量在步骤之间传递，运行时由 Runner 解析。</p></div><div class="config-list"><div><code>token</code><span>从获取访问令牌提取</span><b>已配置</b></div><div><code>orderId</code><span>从创建个人签署订单提取</span><b>已配置</b></div><div><code>createCode</code><span>订单创建响应字段</span><b>已配置</b></div></div></div>
        <div v-else-if="activeTab === 'hooks'" class="scenario-config-grid"><div><span class="section-overline">HOOKS</span><h3>前置与后置</h3><p>在主流程前准备数据，在场景结束后清理环境。</p></div><div class="config-list"><div><code>前置 SQL</code><span>清理上一次测试订单</span><b>运行前</b></div><div><code>后置 HTTP</code><span>删除本次创建的订单</span><b>始终执行</b></div></div></div>
        <div v-else class="scenario-config-grid"><div><span class="section-overline">SETTINGS</span><h3>运行设置</h3><p>控制失败策略、超时和场景运行环境。</p></div><div class="config-list"><div><code>失败策略</code><span>失败后停止主流程，继续执行清理</span><b>推荐</b></div><div><code>超时</code><span>单步骤最多等待 30 秒</span><b>30s</b></div></div></div>
      </div>
    </div>

    <aside class="property-panel property-panel-v2">
      <header><div><span class="section-overline">SELECTED STEP</span><h3>当前步骤</h3></div><button class="icon-button"><AppIcon name="more" /></button></header>
      <div class="property-id"><span class="component-icon" :class="selected.accent"><AppIcon :name="stepIcon(selected)" :size="18" /></span><div><small>{{ selected.kind }}</small><strong>{{ selected.title }}</strong><span>{{ selected.detail }}</span></div></div>

      <div v-if="selected.kind === '引用接口用例'" class="reference-mode"><div class="property-label">复用方式</div><button :class="{ active: selected.referenceMode === 'reference' }" @click="changeReferenceMode('reference')"><strong>引用</strong><small>始终使用源用例最新内容</small></button><button :class="{ active: selected.referenceMode === 'copy' }" @click="changeReferenceMode('copy')"><strong>复制</strong><small>生成独立副本，后续互不影响</small></button></div>
      <label v-if="selected.kind === '引用接口用例'" class="field-label">接口用例
        <select v-if="caseCatalog.length" v-model="selected.apiCaseId" aria-label="引用接口用例">
          <option :value="null">请选择接口用例</option>
          <option v-for="item in caseCatalog" :key="item.id" :value="item.id">{{ item.label }}</option>
        </select>
        <input v-else v-model="selected.apiCaseId" placeholder="先创建接口用例后选择" />
      </label>
      <template v-if="selected.kind === '自定义 HTTP' || selected.kind === '清理'">
        <label class="field-label">请求方法
          <select v-model="selected.httpMethod" aria-label="自定义 HTTP 方法">
            <option v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']" :key="method" :value="method">{{ method }}</option>
          </select>
        </label>
        <label class="field-label">请求路径
          <input v-model="selected.httpPath" aria-label="自定义 HTTP 路径" placeholder="/actuator/health" />
        </label>
        <label class="field-label">请求体
          <select v-model="selected.httpBodyType" aria-label="自定义 HTTP 请求体类型">
            <option value="NONE">无请求体</option><option value="JSON">JSON</option><option value="TEXT">文本</option>
          </select>
          <textarea v-if="selected.httpBodyType !== 'NONE'" v-model="selected.httpBody" aria-label="自定义 HTTP 请求体" rows="4" placeholder="请求体内容" />
        </label>
      </template>
      <template v-if="selected.kind === 'SQL'">
        <label class="field-label">JDBC 数据源
          <select v-model="selected.sqlDataSourceId" aria-label="SQL 数据源"><option :value="null">请选择当前环境数据源</option><option v-for="source in jdbcSources" :key="source.id" :value="source.id">{{ source.name }} · {{ source.databaseType }}</option></select>
        </label>
        <label class="field-label">SQL 查询<textarea v-model="selected.sqlText" aria-label="SQL 查询" rows="6" placeholder="SELECT status FROM orders WHERE id = ${orderId}" /></label>
        <label class="field-label">行数断言期望<input v-model="selected.sqlAssertionExpected" aria-label="SQL 行数断言期望" type="number" min="0" /></label>
        <small class="sql-hint">默认只允许 SELECT；写 SQL 需要在保存前明确勾选允许写入并二次确认。</small>
      </template>
      <template v-if="selected.kind === 'Redis'">
        <label class="field-label">Redis 数据源
          <select v-model="selected.redisDataSourceId" aria-label="Redis 数据源"><option :value="null">请选择当前环境数据源</option><option v-for="source in redisSources" :key="source.id" :value="source.id">{{ source.name }} · DB {{ source.databaseNumber }}</option></select>
        </label>
        <label class="field-label">命令
          <select v-model="selected.redisCommand" aria-label="Redis 命令"><option value="GET">GET</option><option value="EXISTS">EXISTS</option><option value="SET">SET（需确认）</option><option value="DEL">DEL（需确认）</option></select>
        </label>
        <label class="field-label">Key<input v-model="selected.redisKey" aria-label="Redis Key" placeholder="user:${userId}" /></label>
        <label v-if="selected.redisCommand === 'SET'" class="field-label">Value<input v-model="selected.redisValue" aria-label="Redis Value" /></label>
        <label class="field-label">值断言（可选）<input v-model="selected.redisAssertionExpected" aria-label="Redis 断言期望" placeholder="expected value" /></label>
        <small class="sql-hint">仅支持白名单命令；SET/DEL 必须在后端计划中显式 allowWrite + confirmed，页面不会默认执行写操作。</small>
      </template>
      <template v-if="selected.kind === '条件'">
        <label class="field-label">左值<input v-model="selected.conditionLeft" aria-label="条件左值" placeholder="${status}" /></label>
        <label class="field-label">运算符<select v-model="selected.conditionOperator" aria-label="条件运算符"><option value="EQUALS">等于</option><option value="NOT_EQUALS">不等于</option><option value="CONTAINS">包含</option><option value="NOT_CONTAINS">不包含</option><option value="GREATER_THAN">大于</option><option value="LESS_THAN">小于</option><option value="EXISTS">存在</option><option value="NOT_EXISTS">不存在</option></select></label>
        <label v-if="selected.conditionOperator !== 'EXISTS' && selected.conditionOperator !== 'NOT_EXISTS'" class="field-label">右值<input v-model="selected.conditionRight" aria-label="条件右值" /></label>
        <label class="field-label">命中分支<select v-model="selected.branch" aria-label="条件分支"><option value="THEN">满足时执行 THEN 子步骤</option><option value="ELSE">不满足时执行 ELSE 子步骤</option></select></label>
        <small class="sql-hint">子步骤通过父步骤和 branch 字段归属，条件只比较标量，不执行脚本。</small>
      </template>
      <template v-if="selected.kind === '循环'">
        <label class="field-label">循环模式<select v-model="selected.loopMode" aria-label="循环模式"><option value="FIXED">固定次数</option><option value="LIST">列表遍历</option><option value="WHILE">条件循环</option></select></label>
        <label v-if="selected.loopMode === 'FIXED'" class="field-label">循环次数<input v-model.number="selected.loopCount" aria-label="循环次数" type="number" min="1" max="1000" /></label>
        <template v-if="selected.loopMode === 'LIST'"><label class="field-label">列表变量<input v-model="selected.loopItems" aria-label="列表变量" placeholder="${ids}" /></label><label class="field-label">当前项变量<input v-model="selected.loopItemVariable" aria-label="当前项变量" placeholder="item" /></label></template>
        <template v-if="selected.loopMode === 'WHILE'"><label class="field-label">条件左值<input v-model="selected.conditionLeft" aria-label="循环条件左值" /></label><label class="field-label">条件运算符<select v-model="selected.conditionOperator" aria-label="循环条件运算符"><option value="EQUALS">等于</option><option value="NOT_EQUALS">不等于</option><option value="GREATER_THAN">大于</option><option value="LESS_THAN">小于</option><option value="EXISTS">存在</option><option value="NOT_EXISTS">不存在</option></select></label><label v-if="selected.conditionOperator !== 'EXISTS' && selected.conditionOperator !== 'NOT_EXISTS'" class="field-label">条件右值<input v-model="selected.conditionRight" aria-label="循环条件右值" /></label></template>
        <label class="field-label">最大循环次数<input v-model.number="selected.loopMaxIterations" aria-label="最大循环次数" type="number" min="1" max="1000" /></label>
        <small class="sql-hint">达到上限会安全停止，循环子步骤必须归属于 BODY。</small>
      </template>
      <template v-if="selected.kind === '等待'">
        <label class="field-label">等待时间（毫秒）<input v-model.number="selected.waitMillis" aria-label="等待时间" type="number" min="0" max="86400000" /></label>
        <small class="sql-hint">等待期间仍响应取消请求，最长不超过 24 小时。</small>
      </template>
      <label v-if="selected.parentId && steps.find((item) => item.id === selected.parentId)?.kind === '条件'" class="field-label">条件分支<select v-model="selected.branch" aria-label="子步骤条件分支"><option value="THEN">满足时执行</option><option value="ELSE">不满足时执行</option></select></label>
      <label class="field-label">步骤名称<input :value="selected.title" /></label>
      <div class="property-section"><div><strong>失败策略</strong><select v-model="selected.failureStrategy" aria-label="失败策略"><option value="STOP">停止主流程</option><option value="CONTINUE">记录失败并继续</option><option value="RETRY">重试后停止</option></select></div><small>清理流程仍会继续执行</small></div>
      <template v-if="selected.failureStrategy === 'RETRY'">
        <label class="field-label">最大尝试次数<input v-model.number="selected.retryMaxAttempts" aria-label="最大尝试次数" type="number" min="2" max="5" /></label>
        <label class="field-label">重试间隔（毫秒）<input v-model.number="selected.retryIntervalMillis" aria-label="重试间隔" type="number" min="0" max="60000" /></label>
        <small class="sql-hint">首片仅对连接错误和超时重试；服务端仍会限制次数和间隔。</small>
      </template>
      <div class="property-section"><div><strong>重试</strong><label class="switch"><input type="checkbox" /><i></i></label></div><small>仅连接错误、超时和指定状态码</small></div>
      <div class="property-section"><div><strong>执行条件</strong><button>添加条件</button></div><small>{{ selected.kind === '条件' ? selected.detail : '当前步骤始终执行' }}</small></div>
      <div class="resolved-preview"><span>解析后预览</span><code>{{ selected.detail }}</code><small>Authorization: Bearer ••••••••</small></div>
    </aside>
  </section>
</template>

export type PageKey =
  | 'ai'
  | 'apis'
  | 'cases'
  | 'scenario'
  | 'suites'
  | 'environments'
  | 'runs'
  | 'report'
  | 'settings'

export type PrototypeRunStatus = 'idle' | 'pending' | 'running' | 'passed' | 'failed'

export type AiTaskKey = 'requirement' | 'curl' | 'openapi' | 'failure'

export interface AiTaskDefinition {
  key: AiTaskKey
  title: string
  description: string
  inputLabel: string
  placeholder: string
  example: string
  actionLabel: string
  icon: string
}

export type ScenarioReferenceMode = 'reference' | 'copy'

export interface ScenarioStepPresentation {
  kind: string
  icon: string
  accent: string
}

export interface ScenarioStepState {
  id: string
  expanded?: boolean
  referenceMode?: ScenarioReferenceMode
  [key: string]: unknown
}

export const aiTaskDefinitions: Readonly<Record<AiTaskKey, AiTaskDefinition>> = {
  requirement: {
    key: 'requirement',
    title: '从需求创建测试',
    description: '描述业务目标，生成接口步骤、提取和断言草稿',
    inputLabel: '描述要验证的业务流程',
    placeholder: '例如：用户登录后创建订单，校验订单状态并检查数据库记录…',
    example: '从“合同签署”需求中生成登录、创建订单和数据库校验',
    actionLabel: '生成测试草稿',
    icon: 'spark',
  },
  curl: {
    key: 'curl',
    title: '从 Curl 创建用例',
    description: '粘贴请求，自动整理参数、响应断言和变量提取',
    inputLabel: '粘贴 Curl 命令或补充用例意图',
    placeholder: 'curl --request POST https://api.example.com/orders …',
    example: '解析创建订单 Curl，补充状态码和 $.data.id 断言',
    actionLabel: '生成接口用例',
    icon: 'terminal',
  },
  openapi: {
    key: 'openapi',
    title: '导入 OpenAPI',
    description: '导入接口描述，生成目录并识别新增或变更接口',
    inputLabel: '粘贴 OpenAPI 地址、JSON 或 YAML',
    placeholder: '粘贴 OpenAPI URL，或拖入 JSON / YAML 内容…',
    example: '导入 https://test-api.example.com/openapi.json 并预览变更',
    actionLabel: '分析 OpenAPI',
    icon: 'api',
  },
  failure: {
    key: 'failure',
    title: '分析失败报告',
    description: '定位失败断言和响应差异，生成修复建议与回归用例',
    inputLabel: '粘贴失败报告或选择最近一次运行',
    placeholder: '粘贴失败报告、运行编号或响应片段…',
    example: '分析失败报告 RUN-240908-030，找出 paymentStatus 不一致原因',
    actionLabel: '生成分析建议',
    icon: 'report',
  },
}

export interface PrototypeState {
  activePage: PageKey
  selectedScenarioStep: string
  runStatus: PrototypeRunStatus
}

export interface PrototypeRun {
  id: string
  status: Exclude<PrototypeRunStatus, 'idle' | 'pending'>
}

export type RunFilter = 'all' | PrototypeRun['status']

export function createPrototypeState(): PrototypeState {
  return {
    activePage: 'ai',
    selectedScenarioStep: 'step-02',
    runStatus: 'idle',
  }
}

export function navigateTo(state: PrototypeState, page: PageKey): PrototypeState {
  return { ...state, activePage: page }
}

export function selectScenarioStep(state: PrototypeState, stepId: string): PrototypeState {
  return { ...state, selectedScenarioStep: stepId }
}

export function advanceRunStatus(status: PrototypeRunStatus): PrototypeRunStatus {
  const transitions: Record<PrototypeRunStatus, PrototypeRunStatus> = {
    idle: 'pending',
    pending: 'running',
    running: 'passed',
    passed: 'idle',
    failed: 'idle',
  }
  return transitions[status]
}

export function filterRuns<T extends PrototypeRun>(runs: T[], filter: RunFilter): T[] {
  return filter === 'all' ? runs : runs.filter((run) => run.status === filter)
}

export function completeRunSteps<T extends { status?: unknown }>(steps: T[]): Array<T & { status: 'passed' }> {
  return steps.map((step) => ({ ...step, status: 'passed' }))
}

export function firstFailedStepIndex(steps: Array<{ status: string }>): number {
  const index = steps.findIndex((step) => step.status === 'failed')
  return index < 0 ? 0 : index
}

export function selectAiTask(task: AiTaskKey): AiTaskDefinition {
  return aiTaskDefinitions[task]
}

export function toggleScenarioStepExpanded<T extends ScenarioStepState>(steps: T[], stepId: string): T[] {
  return steps.map((step) => step.id === stepId ? { ...step, expanded: !step.expanded } : { ...step })
}

export function setScenarioReferenceMode<T extends ScenarioStepState>(steps: T[], stepId: string, mode: ScenarioReferenceMode): T[] {
  return steps.map((step) => step.id === stepId ? { ...step, referenceMode: mode } : { ...step })
}

export function scenarioStepPresentation(kind: string): ScenarioStepPresentation {
  const presentations: Record<string, ScenarioStepPresentation> = {
    '引用接口用例': { kind: '引用接口用例', icon: 'case', accent: 'blue' },
    '提取': { kind: '提取', icon: 'code', accent: 'violet' },
    'SQL': { kind: 'SQL', icon: 'database', accent: 'green' },
    'Redis': { kind: 'Redis', icon: 'database', accent: 'red' },
    '条件': { kind: '条件', icon: 'flow', accent: 'amber' },
    '循环': { kind: '循环', icon: 'runs', accent: 'indigo' },
    '等待': { kind: '等待', icon: 'clock', accent: 'slate' },
    '清理': { kind: '清理', icon: 'close', accent: 'red' },
    '自定义 HTTP': { kind: '自定义 HTTP', icon: 'api', accent: 'blue' },
  }
  return presentations[kind] ?? { kind, icon: 'api', accent: 'blue' }
}

import type { PageKey, PrototypeRun } from './state'

export interface NavigationItem {
  key: PageKey
  label: string
  icon: string
}

export const navigation: NavigationItem[] = [
  { key: 'ai', label: 'AI 工作台', icon: 'spark' },
  { key: 'apis', label: '接口管理', icon: 'api' },
  { key: 'cases', label: '接口用例', icon: 'case' },
  { key: 'scenario', label: '场景自动化', icon: 'flow' },
  { key: 'suites', label: '测试集合', icon: 'suite' },
  { key: 'environments', label: '环境配置', icon: 'env' },
  { key: 'runs', label: '运行中心', icon: 'runs' },
  { key: 'report', label: '报告详情', icon: 'report' },
  { key: 'settings', label: '系统设置', icon: 'settings' },
]

export const pageTitles: Record<PageKey, { title: string; eyebrow: string }> = {
  ai: { title: 'AI 工作台', eyebrow: '今天从哪里开始？' },
  apis: { title: '接口管理', eyebrow: 'API DESIGN' },
  cases: { title: '接口用例', eyebrow: 'CASE DESIGN' },
  scenario: { title: '场景自动化', eyebrow: 'SCENARIO' },
  suites: { title: '测试集合', eyebrow: 'REGRESSION' },
  environments: { title: '环境配置', eyebrow: 'CONTEXT' },
  runs: { title: '运行中心', eyebrow: 'EXECUTION' },
  report: { title: '报告详情', eyebrow: 'EVIDENCE' },
  settings: { title: '系统设置', eyebrow: 'SYSTEM' },
}

export const prototypeRuns: (PrototypeRun & {
  target: string
  trigger: string
  duration: string
  time: string
  passRate: string
})[] = [
  { id: 'RUN-240908-031', status: 'running', target: '合同签署核心链路', trigger: '手动执行', duration: '01:42', time: '22:46', passRate: '6 / 8' },
  { id: 'RUN-240908-030', status: 'failed', target: '订单支付回归', trigger: 'CI / main', duration: '02:18', time: '21:30', passRate: '11 / 12' },
  { id: 'RUN-240908-029', status: 'passed', target: '用户鉴权接口', trigger: '定时任务', duration: '00:38', time: '20:00', passRate: '5 / 5' },
  { id: 'RUN-240908-028', status: 'passed', target: '合同模板管理', trigger: '手动执行', duration: '01:06', time: '18:17', passRate: '9 / 9' },
  { id: 'RUN-240908-027', status: 'failed', target: '发票申请流程', trigger: 'API 触发', duration: '00:54', time: '17:02', passRate: '3 / 5' },
]

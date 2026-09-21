import { describe, expect, it } from 'vitest'

import {
  advanceRunStatus,
  completeRunSteps,
  createPrototypeState,
  filterRuns,
  firstFailedStepIndex,
  navigateTo,
  scenarioStepPresentation,
  selectAiTask,
  selectScenarioStep,
  setScenarioReferenceMode,
  toggleScenarioStepExpanded,
  type PrototypeRun,
} from './state'

describe('页面原型状态', () => {
  it('默认进入 AI 工作台并能切换功能页', () => {
    const state = createPrototypeState()

    expect(state.activePage).toBe('ai')
    expect(navigateTo(state, 'scenario').activePage).toBe('scenario')
  })

  it('选择场景步骤时保留当前页面并更新步骤', () => {
    const state = navigateTo(createPrototypeState(), 'scenario')
    const next = selectScenarioStep(state, 'step-03')

    expect(next.activePage).toBe('scenario')
    expect(next.selectedScenarioStep).toBe('step-03')
    expect(state.selectedScenarioStep).not.toBe('step-03')
  })

  it('模拟运行按待执行、运行中、通过推进', () => {
    expect(advanceRunStatus('idle')).toBe('pending')
    expect(advanceRunStatus('pending')).toBe('running')
    expect(advanceRunStatus('running')).toBe('passed')
    expect(advanceRunStatus('passed')).toBe('idle')
  })

  it('运行中心可按状态筛选并保留全部选项', () => {
    const runs: PrototypeRun[] = [
      { id: 'RUN-1', status: 'passed' },
      { id: 'RUN-2', status: 'failed' },
      { id: 'RUN-3', status: 'running' },
    ]

    expect(filterRuns(runs, 'all')).toHaveLength(3)
    expect(filterRuns(runs, 'failed').map((run) => run.id)).toEqual(['RUN-2'])
  })

  it('场景运行通过后所有步骤状态同步完成且不修改原数组', () => {
    const steps = [{ id: '1', status: 'running' as const }, { id: '2', status: 'pending' as const }]

    const completed = completeRunSteps(steps)

    expect(completed.map((step) => step.status)).toEqual(['passed', 'passed'])
    expect(steps.map((step) => step.status)).toEqual(['running', 'pending'])
  })

  it('报告默认选中首个失败步骤', () => {
    expect(firstFailedStepIndex([{ status: 'passed' }, { status: 'failed' }, { status: 'failed' }])).toBe(1)
    expect(firstFailedStepIndex([{ status: 'passed' }])).toBe(0)
  })

  it('选择 AI 任务时返回对应的输入提示和确认动作', () => {
    expect(selectAiTask('requirement')).toMatchObject({
      key: 'requirement',
      title: '从需求创建测试',
      actionLabel: '生成测试草稿',
    })
    expect(selectAiTask('openapi').placeholder).toContain('OpenAPI')
    expect(selectAiTask('failure').example).toContain('失败报告')
  })

  it('切换场景步骤展开状态时不修改原步骤数组', () => {
    const steps = [
      { id: 'step-01', expanded: false },
      { id: 'step-02', expanded: true },
    ]

    const next = toggleScenarioStepExpanded(steps, 'step-01')

    expect(next).toEqual([
      { id: 'step-01', expanded: true },
      { id: 'step-02', expanded: true },
    ])
    expect(steps[0]?.expanded).toBe(false)
  })

  it('设置引用模式只更新目标步骤并保留引用或复制语义', () => {
    const steps = [
      { id: 'step-01', referenceMode: 'reference' as const },
      { id: 'step-02', referenceMode: 'copy' as const },
    ]

    const next = setScenarioReferenceMode(steps, 'step-01', 'copy')

    expect(next[0]?.referenceMode).toBe('copy')
    expect(next[1]?.referenceMode).toBe('copy')
    expect(steps[0]?.referenceMode).toBe('reference')
  })

  it('将提取步骤映射为提取语义、代码图标和紫色标识', () => {
    expect(scenarioStepPresentation('提取')).toEqual({
      kind: '提取',
      icon: 'code',
      accent: 'violet',
    })
  })
})

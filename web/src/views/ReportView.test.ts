// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  report: vi.fn(),
}))

vi.mock('../api/run', () => ({ runApi: { report: mocks.report } }))

import ReportView from './ReportView.vue'

describe('集合运行报告', () => {
  it('展示不可变运行计划中的成员顺序、名称和启停状态', async () => {
    mocks.report.mockResolvedValue({
      runId: 'run-1', status: 'PASSED', startedAt: null, finishedAt: null, exitCode: 0,
      cleanupStatus: 'NOT_APPLICABLE',
      suiteMembers: [
        { memberId: 'member-1', position: 0, targetType: 'API_CASE', targetId: 'case-1', targetName: '登录用例', enabled: true },
        { memberId: 'member-2', position: 1, targetType: 'SCENARIO', targetId: 'scenario-1', targetName: '业务回归场景', enabled: false },
      ],
      steps: [{
        id: 'step-result-1', runId: 'run-1', stepId: 'step-1', resultKey: 'member-1/step-1', memberId: 'member-1', sequenceNo: 0,
        status: 'PASSED', durationMs: 12, requestSummary: {}, responseSummary: {}, assertions: [], extractions: [],
        errorSummary: null, startedAt: null, finishedAt: null, createdAt: '',
      }, {
        id: 'step-result-2', runId: 'run-1', stepId: 'step-2', resultKey: 'member-2/step-2', memberId: 'member-2', sequenceNo: 1,
        status: 'FAILED', durationMs: 18, requestSummary: {}, responseSummary: {}, assertions: [], extractions: [],
        errorSummary: null, startedAt: null, finishedAt: null, createdAt: '',
      }],
    })

    const wrapper = mount(ReportView, { props: { projectId: 'p1', runId: 'run-1' } })
    await flushPromises()

    const panel = wrapper.get('.suite-report-members')
    expect(panel.text()).toContain('登录用例')
    expect(panel.text()).toContain('业务回归场景')
    expect(panel.text()).toContain('已启用')
    expect(panel.text()).toContain('已停用')
    expect(panel.findAll('.member-index').map((item) => item.text())).toEqual(['1', '2'])

    const filter = wrapper.get('[data-testid="suite-member-filter"]')
    await filter.setValue('member-2')
    expect(wrapper.findAll('.report-tree button')).toHaveLength(1)
    expect(wrapper.find('.evidence-panel').text()).toContain('步骤 2')
  })

  it('逐条展示成功断言的类型和实际/期望证据', async () => {
    mocks.report.mockResolvedValueOnce({
      runId: 'run-success', status: 'PASSED', startedAt: null, finishedAt: null, exitCode: 0,
      cleanupStatus: 'NOT_APPLICABLE', suiteMembers: [],
      steps: [{ id: 's1', runId: 'run-success', stepId: 'step-1', resultKey: 'step-1', memberId: null,
        sequenceNo: 0, status: 'PASSED', durationMs: 10, requestSummary: {}, responseSummary: {},
        assertions: [{ type: 'STATUS', passed: true, actual: 200, expected: 200 },
          { type: 'BODY', passed: true, actual: 'ok', expected: 'ok' }], extractions: [], errorSummary: null,
        startedAt: null, finishedAt: null, createdAt: '' }],
    })

    const wrapper = mount(ReportView, { props: { projectId: 'p1', runId: 'run-success' } })
    await flushPromises()

    expect(wrapper.findAll('[data-testid="assertion-evidence-row"]')).toHaveLength(2)
    expect(wrapper.find('.assertion-evidence').text()).toContain('STATUS')
    expect(wrapper.find('.assertion-evidence').text()).toContain('200')
    expect(wrapper.find('.assertion-evidence').text()).toContain('BODY')
  })

  it('展示断言失败详情和连接失败 errorSummary', async () => {
    mocks.report.mockResolvedValueOnce({
      runId: 'run-failure', status: 'FAILED', startedAt: null, finishedAt: null, exitCode: 1,
      cleanupStatus: 'NOT_APPLICABLE', suiteMembers: [],
      steps: [{ id: 's1', runId: 'run-failure', stepId: 'step-1', resultKey: 'step-1', memberId: null,
        sequenceNo: 0, status: 'FAILED', durationMs: 10, requestSummary: {}, responseSummary: {},
        assertions: [{ type: 'JSON_PATH', passed: false, actual: 'false', expected: 'true', message: '业务断言失败' }],
        extractions: [], errorSummary: { category: 'CONNECTION', message: 'Connection refused', detail: '127.0.0.1:9' },
        startedAt: null, finishedAt: null, createdAt: '' }],
    })

    const wrapper = mount(ReportView, { props: { projectId: 'p1', runId: 'run-failure' } })
    await flushPromises()
    expect(wrapper.find('.assertion-evidence').text()).toContain('业务断言失败')

    await wrapper.get('.evidence-tabs button:nth-child(5)').trigger('click')
    expect(wrapper.get('[data-testid="step-error-summary"]').text()).toContain('Connection refused')
    expect(wrapper.get('[data-testid="step-error-summary"]').text()).toContain('127.0.0.1:9')
  })
})

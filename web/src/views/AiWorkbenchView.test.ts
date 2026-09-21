// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AiWorkbenchView from './AiWorkbenchView.vue'

const { aiApi, aiPatchApi, runApi } = vi.hoisted(() => ({
  aiApi: { models: vi.fn(), sessions: vi.fn(), createSession: vi.fn(), getSession: vi.fn(), streamMessage: vi.fn() },
  aiPatchApi: { preview: vi.fn(), confirm: vi.fn() },
  runApi: { list: vi.fn() },
}))

vi.mock('../api/ai', () => ({ aiApi }))
vi.mock('../api/aiPatch', () => ({ aiPatchApi }))
vi.mock('../api/run', () => ({ runApi }))

const preview = {
  previewId: 'patch-1', projectId: 'p1', targetType: 'API_DEFINITION', title: '从 Curl 生成接口定义',
  targetId: null, parentId: null, baseRevision: null, currentRevision: 0,
  changes: [{ path: '/name', changeType: 'ADDED', oldValue: null, newValue: 'AI 生成接口', dangerous: false }],
  warnings: [], errors: [], canConfirm: true, expiresAt: '2099-01-01T00:00:00Z',
}

describe('AI 工作台真实交互', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    aiApi.models.mockResolvedValue([{ id: 'm1', name: '本地模型', enabled: true }])
    aiApi.sessions.mockResolvedValue([])
    aiApi.createSession.mockResolvedValue({ id: 's1' })
    aiApi.getSession.mockResolvedValue({ messages: [] })
    runApi.list.mockResolvedValue([])
    aiPatchApi.preview.mockResolvedValue(preview)
    aiPatchApi.confirm.mockResolvedValue({ revision: 1 })
  })

  it('Curl 生成事件会打开字段差异，并且确认调用 Patch API', async () => {
    aiApi.streamMessage.mockImplementation(async (_project: string, _session: string, _content: string, onEvent: (event: unknown) => void) => {
      onEvent({ type: 'TOOL_RESULT', toolName: 'create_draft_patch', content: null, arguments: {
        targetType: 'API_DEFINITION', title: '从 Curl 生成接口定义', operations: [{ op: 'add', path: '/name', value: 'AI 生成接口' }],
      } })
      onEvent({ type: 'DONE', toolName: null, content: null, arguments: null })
    })
    const wrapper = mount(AiWorkbenchView, { props: { projectId: 'p1' } })
    await flushPromises()
    await wrapper.get('button.task-card:nth-child(2)').trigger('click')
    await wrapper.get('textarea').setValue('curl https://example.test/health')
    await wrapper.get('button.send-button').trigger('click')
    await flushPromises()
    expect(aiApi.createSession).toHaveBeenCalledWith('p1', expect.objectContaining({ modelConfigId: 'm1' }))
    expect(aiPatchApi.preview).toHaveBeenCalledWith('p1', expect.objectContaining({ targetType: 'API_DEFINITION' }))
    expect(wrapper.get('[data-testid="ai-patch-modal"]').text()).toContain('从 Curl 生成接口定义')
    await wrapper.get('[data-action="confirm-ai-patch"]').trigger('click')
    await flushPromises()
    expect(aiPatchApi.confirm).toHaveBeenCalledWith('p1', 'patch-1')
  })

  it('失败分析携带运行上下文并展示脱敏报告证据卡片', async () => {
    runApi.list.mockResolvedValue([{ id: 'r1', status: 'FAILED', targetType: 'API_CASE' }])
    aiApi.streamMessage.mockImplementation(async (_project: string, _session: string, content: string, onEvent: (event: unknown) => void) => {
      expect(content).toContain('runId=r1')
      onEvent({ type: 'TOOL_RESULT', toolName: 'get_run_report', content: null, arguments: { runId: 'r1', status: 'FAILED', steps: [{ resultKey: 'orders.create', status: 'FAILED', assertions: [], error: {} }] } })
      onEvent({ type: 'DONE', toolName: null, content: null, arguments: null })
    })
    const wrapper = mount(AiWorkbenchView, { props: { projectId: 'p1' } })
    await flushPromises()
    await wrapper.get('button.task-card:nth-child(4)').trigger('click')
    await wrapper.get('[data-testid="ai-run-context"]').setValue('r1')
    await wrapper.get('button.send-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="ai-report-evidence"]').text()).toContain('orders.create')
  })
})

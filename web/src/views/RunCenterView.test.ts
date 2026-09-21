// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  definition: {
    id: 'd1', projectId: 'p1', moduleId: null, name: '健康检查', method: 'GET', urlTemplate: '/health',
    requestSpec: {
      pathParams: [], query: [],
      headers: [{ name: 'X-Request', value: 'definition', enabled: true }], cookies: [],
      body: { type: 'NONE' }, options: {},
    }, revision: 1, archived: false, createdAt: '', updatedAt: '',
  },
  apiCase: {
    id: 'c1', projectId: 'p1', apiDefinitionId: 'd1', name: '默认用例',
    caseSpec: {
      pathParams: {}, query: {}, headers: { 'x-request': 'case' }, cookies: {}, body: { type: 'NONE' },
      dataRows: [{ id: 'row-1', enabled: true, values: { tenant: 'qa' } }],
      dataRowOptions: { continueOnFailure: false },
    },
    variables: {}, assertions: [], revision: 1, archived: false, createdAt: '', updatedAt: '',
  },
  apiDefinitionApi: { list: vi.fn().mockResolvedValue([]) },
  apiCaseApi: { list: vi.fn().mockResolvedValue([]) },
  environmentApi: { get: vi.fn() },
  runApi: { create: vi.fn(), get: vi.fn() },
}))

vi.mock('../api/apiDefinition', () => ({ apiDefinitionApi: mocks.apiDefinitionApi }))
vi.mock('../api/apiCase', () => ({ apiCaseApi: mocks.apiCaseApi }))
vi.mock('../api/environment', () => ({ environmentApi: mocks.environmentApi }))
vi.mock('../api/run', () => ({ runApi: mocks.runApi }))

import RunCenterView from './RunCenterView.vue'

describe('运行中心环境默认请求配置', () => {
  it('将环境默认 Header 与代理合入最终执行计划，接口和用例值优先', async () => {
    mocks.apiDefinitionApi.list.mockResolvedValue([mocks.definition])
    mocks.apiCaseApi.list.mockResolvedValue([mocks.apiCase])
    mocks.environmentApi.get.mockResolvedValue({
      id: 'e1', projectId: 'p1', name: '测试', baseUrl: 'https://api.example.com', variables: {},
      requestOptions: {
        defaultHeaders: [
          { name: 'X-Env', value: 'environment', enabled: true },
          { name: 'X-Request', value: 'environment', enabled: true },
        ],
        followRedirects: false,
        responseTimeoutMillis: 9000,
        proxy: { scheme: 'http', host: 'proxy.example', port: 8080 },
      },
      revision: 1, archived: false, createdAt: '', updatedAt: '',
    })
    mocks.runApi.create.mockResolvedValue({ id: 'r1', status: 'PENDING' })
    mocks.runApi.get.mockResolvedValue({ id: 'r1', status: 'PASSED' })

    const wrapper = mount(RunCenterView, { props: { projectId: 'p1', environmentId: 'e1' } })
    await flushPromises()
    await wrapper.get('button[data-action="run-case"]').trigger('click')
    await flushPromises()

    const plan = mocks.runApi.create.mock.calls[0][1].executionPlan
    expect(plan.headers).toEqual([
      { name: 'X-Env', value: 'environment', enabled: true },
      { name: 'X-Request', value: 'case', enabled: true },
    ])
    expect(plan.followRedirects).toBe(false)
    expect(plan.responseTimeoutMillis).toBe(9000)
    expect(plan.proxy).toEqual({ scheme: 'http', host: 'proxy.example', port: 8080 })
    expect(plan.dataRows).toEqual([{ id: 'row-1', enabled: true, values: { tenant: 'qa' } }])
    expect(plan.dataRowOptions).toEqual({ continueOnFailure: false })
  })

  it('请求预览展示最终 URL 和环境 Header，并固定掩码敏感值', async () => {
    mocks.apiDefinitionApi.list.mockResolvedValue([mocks.definition])
    mocks.apiCaseApi.list.mockResolvedValue([mocks.apiCase])
    mocks.environmentApi.get.mockResolvedValue({
      id: 'e1', projectId: 'p1', name: '测试', baseUrl: 'https://api.example.com', variables: {},
      requestOptions: {
        defaultHeaders: [
          { name: 'X-Env', value: 'qa', enabled: true },
          { name: 'Authorization', value: 'Bearer ${secret:token}', enabled: true },
        ],
      },
      revision: 1, archived: false, createdAt: '', updatedAt: '',
    })

    const wrapper = mount(RunCenterView, { props: { projectId: 'p1', environmentId: 'e1' } })
    await flushPromises()
    await wrapper.get('button[data-action="preview-request"]').trigger('click')
    await flushPromises()

    const preview = wrapper.get('[data-testid="request-preview"]')
    expect(preview.text()).toContain('https://api.example.com/health')
    expect(preview.text()).toContain('X-Env: qa')
    expect(preview.text()).toContain('Authorization: ••••••••')
    expect(preview.text()).not.toContain('${secret:token}')
  })
})

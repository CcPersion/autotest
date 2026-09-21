// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import SuiteView from './SuiteView.vue'
import type { TestSuite, TestSuiteApi } from '../api/testSuite'
import type { ApiCase } from '../api/apiCase'
import type { ApiDefinition } from '../api/apiDefinition'
import type { Scenario } from '../api/scenario'

const suite: TestSuite = {
  id: 'suite-1', projectId: 'p1', name: '核心回归', description: '登录链路', environmentId: 'e1',
  revision: 2, archived: false, createdAt: '', updatedAt: '',
  members: [
    { id: 'm-1', position: 0, targetType: 'API_CASE', targetId: 'case-login', enabled: true },
    { id: 'm-2', position: 1, targetType: 'SCENARIO', targetId: 'scenario-order', enabled: true },
  ],
}

function api(): TestSuiteApi {
  return {
    list: vi.fn().mockResolvedValue([suite]),
    get: vi.fn(), create: vi.fn(), update: vi.fn().mockResolvedValue({ ...suite, revision: 3 }),
    archive: vi.fn(), run: vi.fn().mockResolvedValue({ id: 'run-1', status: 'PENDING' }),
  }
}

function assets() {
  const definition = { id: 'def-1', projectId: 'p1', moduleId: null, name: '登录接口', method: 'POST', urlTemplate: '/login', requestSpec: { query: [], pathParams: [], headers: [], cookies: [], body: { type: 'NONE', value: null }, options: {} }, revision: 0, archived: false, createdAt: '', updatedAt: '' } as unknown as ApiDefinition
  const item = { id: 'case-new', projectId: 'p1', apiDefinitionId: 'def-1', name: '新登录用例', caseSpec: {}, variables: {}, assertions: [], revision: 0, archived: false, createdAt: '', updatedAt: '' } as unknown as ApiCase
  const scenario = { id: 'scenario-new', projectId: 'p1', name: '新订单场景', description: '', variables: {}, settings: {}, revision: 0, archived: false, createdAt: '', updatedAt: '', steps: [] } as unknown as Scenario
  return { listDefinitions: vi.fn().mockResolvedValue([definition]), listCases: vi.fn().mockResolvedValue([item]), listScenarios: vi.fn().mockResolvedValue([scenario]) }
}

describe('测试集合页面', () => {
  it('加载真实集合成员并保持顺序', async () => {
    const testSuiteApi = api()
    const wrapper = mount(SuiteView, { props: { projectId: 'p1', environmentId: 'e1', testSuiteApi } })
    await flushPromises()

    expect(testSuiteApi.list).toHaveBeenCalledWith('p1', false)
    expect(wrapper.text()).toContain('核心回归')
    const rows = wrapper.findAll('[data-testid="suite-member"]')
    expect(rows[0].text()).toContain('case-login')
    expect(rows[1].text()).toContain('scenario-order')
  })

  it('切换成员启用状态并保存当前顺序', async () => {
    const testSuiteApi = api()
    const wrapper = mount(SuiteView, { props: { projectId: 'p1', environmentId: 'e1', testSuiteApi } })
    await flushPromises()
    await wrapper.get('button[data-action="toggle-member"]').trigger('click')
    await wrapper.get('button[data-action="save-suite"]').trigger('click')

    expect(testSuiteApi.update).toHaveBeenCalledWith('p1', 'suite-1', expect.objectContaining({ revision: 2 }))
    expect(testSuiteApi.update.mock.calls[0][2].members[0]).toMatchObject({ id: 'm-1', enabled: false })
  })

  it('按当前环境运行集合并回传运行编号', async () => {
    const testSuiteApi = api()
    const wrapper = mount(SuiteView, { props: { projectId: 'p1', environmentId: 'e1', testSuiteApi } })
    await flushPromises()
    await wrapper.get('button[data-action="run-suite"]').trigger('click')
    await flushPromises()

    expect(testSuiteApi.run).toHaveBeenCalledWith('p1', 'suite-1', 'e1', expect.any(String))
    expect(wrapper.emitted('run-created')?.[0]).toEqual(['run-1'])
    expect(wrapper.emitted('navigate')?.[0]).toEqual(['report'])
  })

  it('创建集合并从项目资产选择成员', async () => {
    const testSuiteApi = api()
    const created = { ...suite, id: 'suite-2', name: '新集合', members: [], revision: 0 }
    vi.mocked(testSuiteApi.create).mockResolvedValue(created)
    const wrapper = mount(SuiteView, { props: { projectId: 'p1', environmentId: 'e1', testSuiteApi, assetApi: assets() } })
    await flushPromises()
    await wrapper.get('button[aria-label="新建集合"]').trigger('click')
    await flushPromises()
    expect(testSuiteApi.create).toHaveBeenCalledWith('p1', expect.objectContaining({ name: '新集合' }))
    await wrapper.get('button[data-action="add-member"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('新登录用例')
    await wrapper.get('button[data-action="pick-case-new"]').trigger('click')
    expect(wrapper.findAll('[data-testid="suite-member"]').map((row) => row.text()).join(' ')).toContain('case-new')
  })
})

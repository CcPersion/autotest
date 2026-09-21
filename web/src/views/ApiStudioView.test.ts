// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import ApiStudioView from './ApiStudioView.vue'

const moduleTree = [{ id: 'm1', projectId: 'p1', parentId: null, name: '鉴权', sortOrder: 0, revision: 1, children: [] }]
const definition = {
  id: 'd1', projectId: 'p1', moduleId: 'm1', name: '登录接口', method: 'POST', urlTemplate: '/login',
  requestSpec: { pathParams: [], query: [{ name: 'tenant', value: 'demo' }], headers: [{ name: 'Accept', value: 'application/json' }], body: { type: 'JSON', value: { username: '${username}' } } },
  revision: 2, archived: false, createdAt: '', updatedAt: '',
}

function definitionApis() {
  return {
    list: vi.fn().mockResolvedValue([definition]),
    get: vi.fn().mockResolvedValue(definition),
    create: vi.fn().mockResolvedValue({ ...definition, id: 'd2', name: '新接口', revision: 1 }),
    update: vi.fn().mockResolvedValue({ ...definition, revision: 3 }),
    archive: vi.fn().mockResolvedValue({ ...definition, archived: true, revision: 3 }),
  }
}

function caseApis() {
  return {
    list: vi.fn().mockResolvedValue([]),
    get: vi.fn(),
    create: vi.fn().mockResolvedValue({ id: 'c1', revision: 1 }),
    update: vi.fn(),
    archive: vi.fn(),
  }
}

describe('真实接口工作台', () => {
  it('加载真实模块和接口定义，编辑 JSON 请求并保存后刷新列表', async () => {
    const definitionApi = definitionApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'api', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis() },
    })
    await flushPromises()

    expect(definitionApi.list).toHaveBeenCalledWith('p1', undefined, false)
    expect(wrapper.text()).toContain('鉴权')
    expect(wrapper.text()).toContain('登录接口')

    await wrapper.get('button[data-action="create-definition"]').trigger('click')
    await wrapper.get('input[name="definition-name"]').setValue('新接口')
    await wrapper.get('select[name="definition-method"]').setValue('POST')
    await wrapper.get('input[name="definition-url"]').setValue('/orders')
    await wrapper.get('select[name="definition-body-type"]').setValue('JSON')
    await wrapper.get('textarea[name="definition-body"]').setValue('{"orderId":"${orderId}"}')
    await wrapper.get('form[data-form="definition"]').trigger('submit')
    await flushPromises()

    expect(definitionApi.create).toHaveBeenCalledWith('p1', expect.objectContaining({
      moduleId: null, name: '新接口', method: 'POST', urlTemplate: '/orders',
      requestSpec: expect.objectContaining({ body: { type: 'JSON', value: { orderId: '${orderId}' } } }),
    }))
    expect(definitionApi.list).toHaveBeenCalledTimes(2)
  })

  it('接口定义可以立即渲染新增 Query 参数行', async () => {
    const definitionApi = definitionApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'api', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis() },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-definition"]').trigger('click')
    const queryTab = wrapper.findAll('button').find((button) => button.text().trim() === 'Query')
    expect(queryTab).toBeDefined()
    await queryTab!.trigger('click')
    await wrapper.get('button[data-action="add-query"]').trigger('click')

    expect(wrapper.findAll('.kv-row')).toHaveLength(1)
    expect(wrapper.get('input[placeholder="参数名"]').exists()).toBe(true)
    expect(wrapper.get('input[placeholder="值或变量"]').exists()).toBe(true)
  })

  it('URL 实时同步合法 Path 参数，保留编辑值并清理删除或变量 token', async () => {
    const definitionApi = definitionApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'api', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis() },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-definition"]').trigger('click')
    await wrapper.get('input[name="definition-url"]').setValue('/orders/{orderId}/${variable}/${secret:name}')
    await wrapper.findAll('button').find((button) => button.text().trim() === 'Path')!.trigger('click')
    expect(wrapper.findAll('.path-row')).toHaveLength(1)
    expect(wrapper.get('.path-row input[readonly]').element.value).toBe('orderId')

    await wrapper.get('.path-row input[placeholder="路径值或变量"]').setValue('1001')
    await wrapper.get('input[name="definition-url"]').setValue('/orders/{orderId}/items/{itemId}')
    expect(wrapper.findAll('.path-row')).toHaveLength(2)
    expect(wrapper.findAll('.path-row input[placeholder="路径值或变量"]')[0].element.value).toBe('1001')

    await wrapper.get('input[name="definition-url"]').setValue('/orders/{itemId}/${variable}')
    expect(wrapper.findAll('.path-row')).toHaveLength(1)
    expect(wrapper.get('.path-row input[readonly]').element.value).toBe('itemId')
    expect(wrapper.get('.path-row input[placeholder="路径值或变量"]').element.value).toBe('')

    await wrapper.get('form[data-form="definition"]').trigger('submit')
    await flushPromises()
    expect(definitionApi.create).toHaveBeenCalledWith('p1', expect.objectContaining({
      urlTemplate: '/orders/{itemId}/${variable}',
      requestSpec: expect.objectContaining({ pathParams: [{ name: 'itemId', value: '' }] }),
    }))
  })

  it('支持编辑和删除 Header 参数', async () => {
    const definitionApi = definitionApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'api', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis() },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-definition"]').trigger('click')
    await wrapper.findAll('button').find((button) => button.text().trim() === 'Header')!.trigger('click')
    await wrapper.get('button[data-action="add-header"]').trigger('click')
    await wrapper.get('input[placeholder="参数名"]').setValue('X-Trace-Id')
    await wrapper.get('input[placeholder="值或变量"]').setValue('trace-1')
    expect(wrapper.get('input[placeholder="参数名"]').element.value).toBe('X-Trace-Id')
    expect(wrapper.get('input[placeholder="值或变量"]').element.value).toBe('trace-1')
    await wrapper.get('button[aria-label="删除参数"]').trigger('click')
    expect(wrapper.findAll('.kv-row')).toHaveLength(0)
  })

  it('根据方法切换请求 Body 类型，GET 为 NONE 且 POST 可编辑 JSON', async () => {
    const definitionApi = definitionApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'api', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis() },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-definition"]').trigger('click')
    expect(wrapper.get('select[name="definition-method"]').element.value).toBe('GET')
    await wrapper.get('select[name="definition-method"]').setValue('POST')
    expect(wrapper.get('select[name="definition-body-type"]').element.value).toBe('JSON')
    await wrapper.get('textarea[name="definition-body"]').setValue('{"ok":true}')
    await wrapper.get('select[name="definition-method"]').setValue('GET')
    expect(wrapper.get('select[name="definition-body-type"]').element.value).toBe('NONE')
    expect(wrapper.find('textarea[name="definition-body"]').exists()).toBe(false)
  })

  it('拒绝无效 JSON Body 且不提交接口定义', async () => {
    const definitionApi = definitionApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'api', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis() },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-definition"]').trigger('click')
    await wrapper.get('select[name="definition-method"]').setValue('POST')
    await wrapper.get('textarea[name="definition-body"]').setValue('{invalid')
    await wrapper.get('form[data-form="definition"]').trigger('submit')
    await flushPromises()

    expect(definitionApi.create).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('JSON Body 格式不正确')
  })

  it('使用结构化 URL Encoded 和 Multipart 编辑器保存有序字段', async () => {
    const definitionApi = definitionApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'api', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis() },
    })
    await flushPromises()
    await wrapper.get('button[data-action="create-definition"]').trigger('click')
    await wrapper.get('select[name="definition-method"]').setValue('POST')
    await wrapper.findAll('button').find((button) => button.text().trim() === 'Body')!.trigger('click')
    await wrapper.get('select[name="definition-body-type"]').setValue('URLENCODED')
    await wrapper.get('button[data-action="add-urlencoded"]').trigger('click')
    await wrapper.get('.body-rows input[placeholder="字段名"]').setValue('q')
    await wrapper.get('.body-rows input[placeholder="值或变量"]').setValue('a&b')
    await wrapper.get('form[data-form="definition"]').trigger('submit')
    await flushPromises()
    expect(definitionApi.create).toHaveBeenCalledWith('p1', expect.objectContaining({
      requestSpec: expect.objectContaining({ body: { type: 'URLENCODED', value: [{ name: 'q', value: 'a&b', enabled: true }] } }),
    }))
  })

  it('发送通过 Platform API 创建 Runner 运行并跳转真实报告，不在浏览器直连目标', async () => {
    const definitionApi = definitionApis()
    const environmentApi = { get: vi.fn().mockResolvedValue({
      id: 'e1', projectId: 'p1', name: '测试', baseUrl: 'http://target.local', variables: {}, requestOptions: {},
    }) }
    const runApi = {
      create: vi.fn().mockResolvedValue({ id: 'run-1', status: 'PASSED' }),
      debug: vi.fn().mockResolvedValue({ id: 'run-1', status: 'PASSED' }),
      preview: vi.fn(),
      get: vi.fn(),
    }
    const wrapper = mount(ApiStudioView, {
      props: {
        mode: 'api', projectId: 'p1', environmentId: 'e1',
        moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: caseApis(),
        environmentApi, runApi,
      },
    })
    await flushPromises()

    await wrapper.get('button[data-action="send-request"]').trigger('click')
    await flushPromises()

    expect(runApi.debug).toHaveBeenCalledWith('p1', expect.objectContaining({
      environmentId: 'e1', targetType: 'SAVED_DEFINITION', definitionId: 'd1',
    }))
    expect(wrapper.emitted('runCreated')).toEqual([['run-1']])
    expect(wrapper.emitted('navigate')).toEqual([['report']])
  })

  it('保存接口用例使用当前定义的真实 revision，刷新重新加载且冲突显示统一错误', async () => {
    const definitionApi = definitionApis()
    const apiCase = caseApis()
    apiCase.list.mockResolvedValue([{ id: 'c1', projectId: 'p1', apiDefinitionId: 'd1', name: '正常用例', caseSpec: definition.requestSpec, variables: {}, assertions: [], revision: 4, archived: false, createdAt: '', updatedAt: '' }])
    apiCase.update.mockRejectedValue({ status: 409, code: 'REVISION_CONFLICT', message: 'stale' })
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'case', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: apiCase },
    })
    await flushPromises()

    expect(apiCase.list).toHaveBeenCalledWith('p1', 'd1', false)
    await wrapper.get('button[data-action="edit-case"]').trigger('click')
    await wrapper.get('form[data-form="case"]').trigger('submit')
    await flushPromises()
    expect(apiCase.update).toHaveBeenCalledWith('p1', 'd1', 'c1', expect.objectContaining({ revision: 4 }))
    expect(wrapper.text()).toContain('版本已变化，请刷新后重试')

    await wrapper.get('button[data-action="refresh-cases"]').trigger('click')
    await flushPromises()
    expect(apiCase.list).toHaveBeenCalledTimes(2)
  })

  it('使用结构化编辑器保存提取器和断言，而不是手写 JSON', async () => {
    const definitionApi = definitionApis()
    const apiCase = caseApis()
    apiCase.create.mockResolvedValue({
      id: 'c2', projectId: 'p1', apiDefinitionId: 'd1', name: '登录成功',
      caseSpec: { pathParams: {}, query: {}, headers: {}, cookies: {}, body: { type: 'NONE' }, extractors: [] },
      variables: {}, assertions: [], revision: 1, archived: false, createdAt: '', updatedAt: '',
    })
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'case', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: apiCase },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-case"]').trigger('click')
    await wrapper.get('input[name="case-name"]').setValue('登录成功')
    await wrapper.get('button[data-tab="提取器"]').trigger('click')
    await wrapper.get('button[data-action="add-extractor"]').trigger('click')
    await wrapper.get('select[name="extractor-type-0"]').setValue('JSON_PATH')
    await wrapper.get('input[name="extractor-expression-0"]').setValue('$.token')
    await wrapper.get('input[name="extractor-variable-0"]').setValue('accessToken')
    await wrapper.get('button[data-tab="断言"]').trigger('click')
    await wrapper.get('button[data-action="add-assertion"]').trigger('click')
    await wrapper.get('select[name="assertion-type-0"]').setValue('STATUS')
    await wrapper.get('select[name="assertion-operator-0"]').setValue('EQUALS')
    await wrapper.get('input[name="assertion-expected-0"]').setValue('200')
    await wrapper.get('form[data-form="case"]').trigger('submit')
    await flushPromises()

    expect(apiCase.create).toHaveBeenCalledWith('p1', 'd1', expect.objectContaining({
      caseSpec: expect.objectContaining({
        extractors: [{ type: 'JSON_PATH', expression: '$.token', variable: 'accessToken', failIfMissing: true }],
      }),
      assertions: [{ type: 'STATUS', operator: 'EQUALS', expected: 200 }],
    }))
  })

  it('按断言合同提供 VARIABLE、JSON/JMES 数值比较和 Header/Cookie 目标表达式并保存', async () => {
    const definitionApi = definitionApis()
    const apiCase = caseApis()
    const caseApi = apiCase
    apiCase.create.mockResolvedValue({
      id: 'c3', projectId: 'p1', apiDefinitionId: 'd1', name: '合同断言',
      caseSpec: { pathParams: {}, query: {}, headers: {}, cookies: {}, body: { type: 'NONE' }, extractors: [] },
      variables: {}, assertions: [], revision: 1, archived: false, createdAt: '', updatedAt: '',
    })
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'case', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-case"]').trigger('click')
    await wrapper.get('button[data-tab="断言"]').trigger('click')
    await wrapper.get('button[data-action="add-assertion"]').trigger('click')
    await wrapper.get('select[name="assertion-type-0"]').setValue('VARIABLE')
    expect(wrapper.get('select[name="assertion-operator-0"] option[value="GREATER_THAN"]').exists()).toBe(true)
    await wrapper.get('select[name="assertion-operator-0"]').setValue('GREATER_THAN')
    await wrapper.get('input[name="assertion-expression-0"]').setValue('attempts')
    await wrapper.get('input[name="assertion-expected-0"]').setValue('2')

    await wrapper.get('button[data-action="add-assertion"]').trigger('click')
    await wrapper.get('select[name="assertion-type-1"]').setValue('HEADER')
    expect(wrapper.get('input[name="assertion-expression-1"]').exists()).toBe(true)
    await wrapper.get('select[name="assertion-operator-1"]').setValue('EQUALS')
    await wrapper.get('input[name="assertion-expression-1"]').setValue('X-Trace-Id')
    await wrapper.get('input[name="assertion-expected-1"]').setValue('"trace-1"')

    await wrapper.get('form[data-form="case"]').trigger('submit')
    await flushPromises()

    expect(apiCase.create).toHaveBeenCalledWith('p1', 'd1', expect.objectContaining({
      assertions: [
        { type: 'VARIABLE', operator: 'GREATER_THAN', expression: 'attempts', expected: 2 },
        { type: 'HEADER', operator: 'EQUALS', expression: 'X-Trace-Id', expected: 'trace-1' },
      ],
    }))
  })

  it('可用响应样本试算提取结果并显示对象/数组类型', async () => {
    const definitionApi = definitionApis()
    const apiCase = caseApis()
    const extractorTrialApi = {
      run: vi.fn().mockResolvedValue({ results: [{
        ruleIndex: 0, type: 'JSON_PATH', expression: '$.profile', variable: 'profile',
        matched: true, usedDefault: false, value: { id: 7 }, valueType: 'object',
        failed: false, errorCode: '', message: '已命中',
      }] }),
    }
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'case', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: apiCase, extractorTrialApi },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-case"]').trigger('click')
    await wrapper.get('button[data-tab="提取器"]').trigger('click')
    await wrapper.get('button[data-action="add-extractor"]').trigger('click')
    await wrapper.get('input[name="extractor-expression-0"]').setValue('$.profile')
    await wrapper.get('input[name="extractor-variable-0"]').setValue('profile')
    await wrapper.get('button[data-action="run-extractor-trial"]').trigger('click')
    await flushPromises()

    expect(extractorTrialApi.run).toHaveBeenCalledWith('p1', expect.objectContaining({
      response: expect.objectContaining({ statusCode: 200, body: expect.stringContaining('token') }),
    }))
    expect(wrapper.get('[data-testid="extractor-trial-results"]').text()).toContain('object')
    expect(wrapper.get('[data-testid="extractor-trial-results"]').text()).toContain('profile')
  })

  it('支持编辑数据行并随用例保存', async () => {
    const definitionApi = definitionApis()
    const apiCase = caseApis()
    const wrapper = mount(ApiStudioView, {
      props: { mode: 'case', projectId: 'p1', moduleApi: { tree: vi.fn().mockResolvedValue(moduleTree) }, definitionApi, caseApi: apiCase },
    })
    await flushPromises()

    await wrapper.get('button[data-action="create-case"]').trigger('click')
    await wrapper.get('button[data-tab="数据行"]').trigger('click')
    await wrapper.get('button[data-action="add-data-column"]').trigger('click')
    await wrapper.get('input[name="data-row-0-field1"]').setValue('张三,一号')
    await wrapper.get('form[data-form="case"]').trigger('submit')
    await flushPromises()

    expect(apiCase.create).toHaveBeenCalledWith('p1', 'd1', expect.objectContaining({
      caseSpec: expect.objectContaining({
        dataRows: [expect.objectContaining({ enabled: true, values: { field1: '张三,一号' } })],
        dataRowOptions: { continueOnFailure: true },
      }),
    }))
  })
})

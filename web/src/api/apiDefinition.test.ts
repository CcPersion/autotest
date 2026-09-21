import { describe, expect, it, vi } from 'vitest'

import { apiDefinitionApi } from './apiDefinition'
import { apiRequest } from './http'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('接口定义 API 客户端', () => {
  it('覆盖列表、详情、创建、更新和归档，并携带 module/includeArchived/revision', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ id: 'd1', revision: 2 })

    await apiDefinitionApi.list('p1', 'm1')
    await apiDefinitionApi.get('p1', 'd1')
    await apiDefinitionApi.create('p1', {
      moduleId: 'm1', name: '登录', method: 'POST', urlTemplate: '/login',
      requestSpec: { pathParams: [], query: [], headers: [], body: { type: 'JSON', value: { username: '${username}' } } },
    })
    await apiDefinitionApi.update('p1', 'd1', {
      moduleId: 'm1', name: '登录改', method: 'POST', urlTemplate: '/login', revision: 1,
      requestSpec: { pathParams: [], query: [], headers: [], body: { type: 'JSON', value: {} } },
    })
    await apiDefinitionApi.archive('p1', 'd1', 2)

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/v1/projects/p1/api-definitions?moduleId=m1&includeArchived=false')
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/v1/projects/p1/api-definitions/d1')
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/v1/projects/p1/api-definitions', expect.objectContaining({ method: 'POST' }))
    expect(apiRequest).toHaveBeenNthCalledWith(4, '/api/v1/projects/p1/api-definitions/d1', expect.objectContaining({
      method: 'PUT',
      body: expect.objectContaining({ revision: 1 }),
    }))
    expect(apiRequest).toHaveBeenNthCalledWith(5, '/api/v1/projects/p1/api-definitions/d1/archive', {
      method: 'POST', body: { revision: 2 },
    })
  })

  it('可显式加载归档定义，并对项目与定义 ID 编码', async () => {
    vi.mocked(apiRequest).mockResolvedValue([])

    await apiDefinitionApi.list('project/1', undefined, true)

    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/project%2F1/api-definitions?includeArchived=true')
  })
})

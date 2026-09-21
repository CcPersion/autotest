import { describe, expect, it, vi } from 'vitest'

import { apiCaseApi } from './apiCase'
import { apiRequest } from './http'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('接口用例 API 客户端', () => {
  it('覆盖列表、详情、创建、更新和归档，并保持 JSON caseSpec/variables/assertions', async () => {
    const caseSpec = { pathParams: {}, query: { page: '1' }, headers: { Accept: 'application/json' }, body: { type: 'NONE' } }
    const variables = { username: 'tester' }
    const assertions = [{ type: 'STATUS', operator: 'EQUALS', expected: 200 }]
    vi.mocked(apiRequest).mockResolvedValue({ id: 'c1', revision: 2 })

    await apiCaseApi.list('p1', 'd1')
    await apiCaseApi.get('p1', 'd1', 'c1')
    await apiCaseApi.create('p1', 'd1', { name: '正常登录', caseSpec, variables, assertions })
    await apiCaseApi.update('p1', 'd1', 'c1', { name: '正常登录改', caseSpec, variables, assertions, revision: 1 })
    await apiCaseApi.archive('p1', 'd1', 'c1', 2)

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/v1/projects/p1/api-definitions/d1/cases?includeArchived=false')
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/v1/projects/p1/api-definitions/d1/cases/c1')
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/v1/projects/p1/api-definitions/d1/cases', {
      method: 'POST', body: { name: '正常登录', caseSpec, variables, assertions },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(4, '/api/v1/projects/p1/api-definitions/d1/cases/c1', {
      method: 'PUT', body: { name: '正常登录改', caseSpec, variables, assertions, revision: 1 },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(5, '/api/v1/projects/p1/api-definitions/d1/cases/c1/archive', {
      method: 'POST', body: { revision: 2 },
    })
  })
})

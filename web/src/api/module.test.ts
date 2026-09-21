import { describe, expect, it, vi } from 'vitest'

import { moduleApi } from './module'
import { apiRequest } from './http'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('模块 API 客户端', () => {
  it('覆盖树查询、建子模块、改名、移动和空模块软删除', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ id: 'm2', name: '支付' })

    await moduleApi.tree('p1')
    await moduleApi.create('p1', { name: '支付', parentId: 'm1', position: 0 })
    await moduleApi.rename('p1', 'm2', { name: '支付 API', revision: 1 })
    await moduleApi.move('p1', 'm2', { parentId: null, position: 1, revision: 2 })
    await moduleApi.remove('p1', 'm2', 3)

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/v1/projects/p1/modules/tree')
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/v1/projects/p1/modules', {
      method: 'POST',
      body: { name: '支付', parentId: 'm1', position: 0 },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/v1/projects/p1/modules/m2', {
      method: 'PUT',
      body: { name: '支付 API', revision: 1 },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(4, '/api/v1/projects/p1/modules/m2/move', {
      method: 'POST',
      body: { parentId: null, position: 1, revision: 2 },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(5, '/api/v1/projects/p1/modules/m2?revision=3', { method: 'DELETE' })
  })
})

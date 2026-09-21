import { describe, expect, it, vi } from 'vitest'

import { projectApi } from './project'
import { apiRequest } from './http'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('项目 API 客户端', () => {
  it('按合同加载活动项目并支持创建、编辑、归档和恢复', async () => {
    vi.mocked(apiRequest)
      .mockResolvedValueOnce([{ id: 'p1', name: '订单', archived: false }])
      .mockResolvedValueOnce({ id: 'p2', name: '支付' })
      .mockResolvedValueOnce({ id: 'p2', name: '支付改名', revision: 2 })
      .mockResolvedValueOnce({ id: 'p2', name: '支付改名', revision: 3, archived: true })
      .mockResolvedValueOnce({ id: 'p2', name: '支付改名', revision: 4, archived: false })

    await expect(projectApi.list()).resolves.toHaveLength(1)
    await projectApi.create({ name: '支付' })
    await projectApi.update('p2', { name: '支付改名', revision: 1 })
    await projectApi.archive('p2', 2)
    await projectApi.restore('p2', 3)

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/v1/projects?includeArchived=false')
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/v1/projects', { method: 'POST', body: { name: '支付' } })
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/v1/projects/p2', {
      method: 'PUT',
      body: { name: '支付改名', revision: 1 },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(4, '/api/v1/projects/p2/archive', { method: 'POST', body: { revision: 2 } })
    expect(apiRequest).toHaveBeenNthCalledWith(5, '/api/v1/projects/p2/restore', { method: 'POST', body: { revision: 3 } })
  })

  it('可显式查询包含归档项目', async () => {
    vi.mocked(apiRequest).mockResolvedValue([])
    await projectApi.list(true)
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects?includeArchived=true')
  })
})

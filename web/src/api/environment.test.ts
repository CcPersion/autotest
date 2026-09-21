import { describe, expect, it, vi } from 'vitest'

import { apiRequest } from './http'
import { environmentApi } from './environment'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('环境 API 客户端', () => {
  it('按合同支持列表、创建、编辑、归档和恢复', async () => {
    const variables = { timeout: 5000, enabled: true, token: '${secret:appSecret}' }
    vi.mocked(apiRequest)
      .mockResolvedValueOnce([{ id: 'e1', projectId: 'p1', name: '测试', variables }])
      .mockResolvedValueOnce({ id: 'e2', projectId: 'p1', name: '预发布' })
      .mockResolvedValueOnce({ id: 'e2', projectId: 'p1', name: '预发布 V2' })
      .mockResolvedValueOnce({ id: 'e2', archived: true })
      .mockResolvedValueOnce({ id: 'e2', archived: false })

    await expect(environmentApi.list('p1')).resolves.toHaveLength(1)
    await environmentApi.create('p1', { name: '预发布', baseUrl: 'https://staging.example.com', variables })
    await environmentApi.update('p1', 'e2', { name: '预发布 V2', baseUrl: 'https://staging.example.com', variables, revision: 1 })
    await environmentApi.archive('p1', 'e2', 2)
    await environmentApi.restore('p1', 'e2', 3)

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/v1/projects/p1/environments?includeArchived=false')
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/v1/projects/p1/environments', {
      method: 'POST',
      body: { name: '预发布', baseUrl: 'https://staging.example.com', variables },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/v1/projects/p1/environments/e2', {
      method: 'PUT',
      body: { name: '预发布 V2', baseUrl: 'https://staging.example.com', variables, revision: 1 },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(4, '/api/v1/projects/p1/environments/e2/archive', { method: 'POST', body: { revision: 2 } })
    expect(apiRequest).toHaveBeenNthCalledWith(5, '/api/v1/projects/p1/environments/e2/restore', { method: 'POST', body: { revision: 3 } })
  })

  it('支持显式查询归档环境', async () => {
    vi.mocked(apiRequest).mockResolvedValue([])
    await environmentApi.list('p1', true)
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/p1/environments?includeArchived=true')
  })
})

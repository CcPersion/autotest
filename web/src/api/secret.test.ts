import { describe, expect, it, vi } from 'vitest'

import { apiRequest } from './http'
import { secretApi } from './secret'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('密钥 API 客户端', () => {
  it('只按固定掩码合同支持列表、创建、替换和归档', async () => {
    vi.mocked(apiRequest)
      .mockResolvedValueOnce([{ id: 's1', projectId: 'p1', name: 'appSecret', mask: '••••••••', revision: 1, archived: false }])
      .mockResolvedValueOnce({ id: 's2', name: 'clientSecret', mask: '••••••••' })
      .mockResolvedValueOnce({ id: 's2', name: 'clientSecret', mask: '••••••••', revision: 2 })
      .mockResolvedValueOnce({ id: 's2', archived: true })

    await expect(secretApi.list('p1')).resolves.toHaveLength(1)
    await secretApi.create('p1', { name: 'clientSecret', value: 'sentinel-value' })
    await secretApi.replace('p1', 's2', { value: 'replacement-value', revision: 1 })
    await secretApi.archive('p1', 's2', 2)

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/v1/projects/p1/secrets?includeArchived=false')
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/v1/projects/p1/secrets', {
      method: 'POST', body: { name: 'clientSecret', value: 'sentinel-value' },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/v1/projects/p1/secrets/s2', {
      method: 'PUT', body: { value: 'replacement-value', revision: 1 },
    })
    expect(apiRequest).toHaveBeenNthCalledWith(4, '/api/v1/projects/p1/secrets/s2/archive', {
      method: 'POST', body: { revision: 2 },
    })
  })
})

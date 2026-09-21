import { describe, expect, it, vi } from 'vitest'

import { redisDataSourceApi } from './redisDataSource'

describe('Redis 数据源 API', () => {
  it('使用环境范围和密钥引用路径', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ id: 'r1' }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await redisDataSourceApi.create('p 1', 'e/1', { name: 'cache', host: 'redis', port: 6379, databaseNumber: 2, secretRef: 'redis-pass', options: { tls: true } })
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/p%201/environments/e%2F1/redis-data-sources', expect.objectContaining({ method: 'POST' }))
    expect(JSON.parse(String((fetchMock.mock.calls[0]?.[1] as RequestInit).body))).toMatchObject({ databaseNumber: 2, secretRef: 'redis-pass' })
    fetchMock.mockRestore()
  })
})

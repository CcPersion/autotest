import { describe, expect, it, vi } from 'vitest'

import { apiRequest } from './http'
import { runApi } from './run'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('运行 API 客户端', () => {
  it('创建、查询运行并读取原生报告', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ id: 'r1', status: 'PASSED', steps: [] })
    const input = {
      environmentId: 'e1', targetType: 'API_CASE' as const, targetId: 'c1',
      executionPlan: { planId: 'p1' }, idempotencyKey: 'k1',
    }

    await runApi.create('project/1', input)
    await runApi.get('project/1', 'r1')
    await runApi.report('project/1', 'r1')

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/v1/projects/project%2F1/runs', { method: 'POST', body: input })
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/v1/projects/project%2F1/runs/r1')
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/v1/projects/project%2F1/runs/r1/report')
  })
})

import { describe, expect, it, vi } from 'vitest'

import { testSuiteApi } from './testSuite'
import { apiRequest } from './http'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('test suite api', () => {
  it('uses versioned test-suite routes and keeps ordered members in writes', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ id: 'suite-1' })
    await testSuiteApi.update('p/1', 'suite/1', {
      name: '回归', description: '', environmentId: 'e1', revision: 2,
      members: [{ id: 'm1', position: 0, targetType: 'API_CASE', targetId: 'c1', enabled: true }],
    })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/p%2F1/test-suites/suite%2F1', {
      method: 'PUT', body: expect.objectContaining({ revision: 2 }),
    })
  })

  it('starts a suite run with the selected environment and idempotency key', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ id: 'run-1' })
    await testSuiteApi.run('p/1', 'suite-1', 'env-1', 'suite-run-key')
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/p%2F1/test-suites/suite-1/runs', {
      method: 'POST', body: { environmentId: 'env-1', idempotencyKey: 'suite-run-key' },
    })
  })
})

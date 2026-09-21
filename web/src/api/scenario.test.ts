import { describe, expect, it, vi } from 'vitest'

import { newScenarioStep, scenarioApi } from './scenario'
import { apiRequest } from './http'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('scenario api', () => {
  it('uses the versioned scenario routes and query', async () => {
    vi.mocked(apiRequest).mockResolvedValue([])
    await scenarioApi.list('p/1', true)
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/p%2F1/scenarios?includeArchived=true')
  })

  it('creates an enabled reference step with a stable client id', () => {
    const step = newScenarioStep('API_CASE', 0)
    expect(step.id).toMatch(/^[0-9a-f-]{36}$/)
    expect(step.referenceMode).toBe('REFERENCE')
    expect(step.enabled).toBe(true)
    expect(step.section).toBe('MAIN')
  })

  it('starts a scenario run with an environment and idempotency key', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ id: 'run-1' })
    await scenarioApi.run('p/1', 'scenario-1', 'env-1', 'scenario-run-key')
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/p%2F1/scenarios/scenario-1/runs', {
      method: 'POST', body: { environmentId: 'env-1', idempotencyKey: 'scenario-run-key' },
    })
  })
})

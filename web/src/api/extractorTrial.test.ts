import { afterEach, describe, expect, it, vi } from 'vitest'

import { extractorTrialApi } from './extractorTrial'

describe('提取器试算 API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('向 Platform 试算端点发送响应样本和提取规则', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(
      JSON.stringify({ results: [{ ruleIndex: 0, variable: 'profile', matched: true, valueType: 'object' }] }),
      { status: 200, headers: { 'content-type': 'application/json' } },
    ))

    const result = await extractorTrialApi.run('project/1', {
      response: { statusCode: 200, durationMs: 42, body: '{"profile":{"id":7}}', headers: {}, cookies: {} },
      extractors: [{ type: 'JSON_PATH', expression: '$.profile', variable: 'profile', failIfMissing: true }],
    })

    expect(result.results[0].valueType).toBe('object')
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/project%2F1/extractor-trials', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        response: { statusCode: 200, durationMs: 42, body: '{"profile":{"id":7}}', headers: {}, cookies: {} },
        extractors: [{ type: 'JSON_PATH', expression: '$.profile', variable: 'profile', failIfMissing: true }],
      }),
    }))
  })
})

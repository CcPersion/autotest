import { describe, expect, it } from 'vitest'

import { runExtractorTrial } from './extractorTrial'

describe('响应提取试算', () => {
  it('保留 JSON 对象和数组类型，并返回命中结果', () => {
    const results = runExtractorTrial([
      { type: 'JSON_PATH', expression: '$.profile', variable: 'profile', failIfMissing: true },
      { type: 'JMESPATH', expression: 'profile.roles', variable: 'roles', failIfMissing: true },
    ], {
      status: 200,
      durationMs: 42,
      bodyText: '{"profile":{"name":"Ada","roles":["qa","dev"]}}',
      headers: {},
      cookies: {},
    })

    expect(results).toEqual([
      expect.objectContaining({ variable: 'profile', matched: true, valueType: 'object', value: { name: 'Ada', roles: ['qa', 'dev'] } }),
      expect.objectContaining({ variable: 'roles', matched: true, valueType: 'array', value: ['qa', 'dev'] }),
    ])
  })

  it('支持响应头、Cookie、正则和未命中默认值', () => {
    const results = runExtractorTrial([
      { type: 'HEADER', expression: 'X-Trace-Id', variable: 'traceId', failIfMissing: true },
      { type: 'COOKIE', expression: 'sid', variable: 'sid', failIfMissing: true },
      { type: 'REGEX', expression: 'order-(\\d+)', variable: 'orderId', failIfMissing: true },
      { type: 'JSON_PATH', expression: '$.missing', variable: 'fallback', defaultValue: 'N/A', failIfMissing: true },
    ], {
      status: 200,
      durationMs: 42,
      bodyText: 'created order-90210',
      headers: { 'X-Trace-Id': 'trace-1' },
      cookies: { sid: 'cookie-1' },
    })

    expect(results.map((item) => [item.variable, item.matched, item.value])).toEqual([
      ['traceId', true, 'trace-1'],
      ['sid', true, 'cookie-1'],
      ['orderId', true, '90210'],
      ['fallback', false, 'N/A'],
    ])
    expect(results[3].failure).toBe(true)
  })
})

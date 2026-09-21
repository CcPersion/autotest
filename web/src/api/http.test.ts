// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiRequest } from './http'

describe('认证 HTTP 客户端', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  })

  it('使用相对 API 路径和同源凭据，并从 XSRF-TOKEN Cookie 添加请求头', async () => {
    document.cookie = 'XSRF-TOKEN=token%2Fvalue; path=/'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 })))

    await apiRequest('/api/v1/example', { method: 'POST', body: { value: 1 } })

    const [path, options] = vi.mocked(fetch).mock.calls[0] || []
    const headers = new Headers((options as RequestInit)?.headers)
    expect(path).toBe('/api/v1/example')
    expect((options as RequestInit)?.method).toBe('POST')
    expect((options as RequestInit)?.credentials).toBe('same-origin')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('X-XSRF-TOKEN')).toBe('token/value')
    expect((options as RequestInit)?.body).toBe(JSON.stringify({ value: 1 }))
  })

  it('将统一错误响应解析为带状态和追踪号的 ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'AUTHENTICATION_REQUIRED',
      message: '请先登录',
      details: null,
      traceId: 'trace-1',
    }), { status: 401, headers: { 'Content-Type': 'application/json' } })))

    const result = apiRequest('/api/v1/auth/me')

    await expect(result).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
      code: 'AUTHENTICATION_REQUIRED',
      message: '请先登录',
      traceId: 'trace-1',
    } satisfies Partial<ApiError>)
  })
})

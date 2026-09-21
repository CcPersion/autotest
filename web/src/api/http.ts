export interface ApiErrorPayload {
  code?: string
  message?: string
  details?: unknown
  traceId?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: unknown
  readonly traceId?: string

  constructor(status: number, payload: ApiErrorPayload = {}) {
    super(payload.message || `请求失败（${status}）`)
    this.name = 'ApiError'
    this.status = status
    this.code = payload.code || 'REQUEST_FAILED'
    this.details = payload.details ?? null
    this.traceId = payload.traceId
  }
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
}

function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined
  const prefix = `${name}=`
  const value = document.cookie
    .split(';')
    .map((cookie) => cookie.trim())
    .find((cookie) => cookie.startsWith(prefix))
    ?.slice(prefix.length)
  return value ? decodeURIComponent(value) : undefined
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  let body: BodyInit | undefined

  if (options.body !== undefined) {
    if (typeof options.body === 'string' || options.body instanceof FormData || options.body instanceof Blob) {
      body = options.body
    } else {
      headers.set('Content-Type', 'application/json')
      body = JSON.stringify(options.body)
    }
  }

  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrfToken = readCookie('XSRF-TOKEN')
    if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken)
  }

  const response = await fetch(path, {
    ...options,
    method,
    credentials: 'same-origin',
    headers,
    body,
  })

  const contentType = response.headers.get('content-type') || ''
  const payload = contentType.includes('application/json')
    ? await response.json().catch(() => undefined)
    : undefined

  if (!response.ok) {
    throw new ApiError(response.status, payload || {})
  }

  if (response.status === 204) return undefined as T
  return (payload === undefined ? await response.json().catch(() => undefined) : payload) as T
}

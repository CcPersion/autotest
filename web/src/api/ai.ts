import { apiRequest } from './http'

export interface AiModelConfig {
  id: string
  name: string
  baseUrl: string
  modelName: string
  apiKeySecretRef: string | null
  enabled: boolean
  revision: number
  providerType: string
}

export interface AiMessage {
  id: string
  sessionId: string
  role: 'USER' | 'ASSISTANT' | 'TOOL'
  eventType: string
  content: string
  toolName: string | null
  createdAt: string
}

export interface AiSession {
  id: string
  projectId: string
  modelConfigId: string
  title: string
  createdAt: string
  updatedAt: string
  messages?: AiMessage[]
}

export interface AiStreamEvent {
  type: 'TEXT' | 'TOOL_CALL' | 'TOOL_RESULT' | 'ERROR' | 'DONE'
  content: string | null
  toolName: string | null
  arguments: unknown
}

export interface AiApi {
  models(): Promise<AiModelConfig[]>
  sessions(projectId: string): Promise<AiSession[]>
  createSession(projectId: string, input: { modelConfigId: string; title: string }): Promise<AiSession>
  getSession(projectId: string, sessionId: string): Promise<AiSession>
  streamMessage(projectId: string, sessionId: string, content: string, onEvent: (event: AiStreamEvent) => void, signal?: AbortSignal): Promise<void>
}

function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined
  const prefix = `${name}=`
  const value = document.cookie.split(';').map((cookie) => cookie.trim()).find((cookie) => cookie.startsWith(prefix))?.slice(prefix.length)
  return value ? decodeURIComponent(value) : undefined
}

async function readSse(response: Response, onEvent: (event: AiStreamEvent) => void) {
  if (!response.ok) throw new Error(`AI 流式请求失败（${response.status}）`)
  if (!response.body) throw new Error('AI 流式响应为空')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const consume = (block: string) => {
    const data = block.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n')
    if (!data) return
    try { onEvent(JSON.parse(data) as AiStreamEvent) } catch { onEvent({ type: 'ERROR', content: 'AI 返回的事件格式不正确', toolName: null, arguments: null }) }
  }
  while (true) {
    const next = await reader.read()
    buffer += decoder.decode(next.value || new Uint8Array(), { stream: !next.done })
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    blocks.forEach(consume)
    if (next.done) break
  }
  if (buffer.trim()) consume(buffer)
}

export const aiApi: AiApi = {
  models: () => apiRequest<AiModelConfig[]>('/api/v1/ai/models'),
  sessions: (projectId) => apiRequest<AiSession[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/ai/sessions`),
  createSession: (projectId, input) => apiRequest<AiSession>(`/api/v1/projects/${encodeURIComponent(projectId)}/ai/sessions`, { method: 'POST', body: input }),
  getSession: (projectId, sessionId) => apiRequest<AiSession>(`/api/v1/projects/${encodeURIComponent(projectId)}/ai/sessions/${encodeURIComponent(sessionId)}`),
  async streamMessage(projectId, sessionId, content, onEvent, signal) {
    const headers = new Headers({ Accept: 'text/event-stream', 'Content-Type': 'application/json' })
    const csrf = readCookie('XSRF-TOKEN')
    if (csrf) headers.set('X-XSRF-TOKEN', csrf)
    const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/ai/sessions/${encodeURIComponent(sessionId)}/messages`, {
      method: 'POST', credentials: 'same-origin', headers, body: JSON.stringify({ content }), signal,
    })
    await readSse(response, onEvent)
  },
}

export { readSse }

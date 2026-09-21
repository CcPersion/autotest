import { apiRequest } from './http'

export type AiPatchTargetType = 'API_DEFINITION' | 'API_CASE' | 'SCENARIO'
export type AiPatchOperationType = 'add' | 'replace' | 'remove'

export interface AiPatchOperation {
  op: AiPatchOperationType
  path: string
  value?: unknown
}

export interface AiPatchWrite {
  title: string
  targetType: AiPatchTargetType
  targetId?: string | null
  parentId?: string | null
  baseRevision?: number | null
  operations: AiPatchOperation[]
}

export interface AiPatchChange {
  path: string
  changeType: 'ADDED' | 'MODIFIED' | 'REMOVED' | 'UNCHANGED'
  oldValue: unknown
  newValue: unknown
  dangerous: boolean
}

export interface AiPatchFieldError {
  path: string
  code: string
  message: string
}

export interface AiPatchPreviewResponse {
  previewId: string
  projectId: string
  targetType: AiPatchTargetType
  title: string
  targetId: string | null
  parentId: string | null
  baseRevision: number | null
  currentRevision: number
  changes: AiPatchChange[]
  warnings: string[]
  errors: AiPatchFieldError[]
  canConfirm: boolean
  expiresAt: string
}

export interface AiPatchConfirmResponse {
  targetType: AiPatchTargetType
  asset: Record<string, unknown>
  revision: number
}

export interface AiPatchApi {
  preview(projectId: string, input: AiPatchWrite): Promise<AiPatchPreviewResponse>
  confirm(projectId: string, previewId: string): Promise<AiPatchConfirmResponse>
}

function basePath(projectId: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/ai/patches`
}

export const aiPatchApi: AiPatchApi = {
  preview(projectId, input) {
    return apiRequest<AiPatchPreviewResponse>(`${basePath(projectId)}/preview`, { method: 'POST', body: input })
  },
  confirm(projectId, previewId) {
    return apiRequest<AiPatchConfirmResponse>(`${basePath(projectId)}/confirm`, {
      method: 'POST', body: { previewId },
    })
  },
}

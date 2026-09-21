import { apiRequest } from './http'

export type ImportAction = 'CREATE' | 'UPDATE' | 'SKIP'

export interface ImportPreviewRequest {
  moduleId?: string | null
  source: string
}

export interface ImportChoice {
  index: number
  action: ImportAction
}

export interface ImportPreviewItem {
  index: number
  sourcePath: string
  definitionName: string
  caseName: string
  method: string
  urlTemplate: string
  requestSpec: Record<string, unknown>
  caseSpec: Record<string, unknown>
  variables: Record<string, unknown>
  assertions: unknown[]
  conflictType: string | null
  existingDefinitionId: string | null
  existingCaseId: string | null
  recommendedAction: ImportAction
  warnings: string[]
  errors: string[]
}

export interface ImportPreviewResponse {
  previewId: string
  projectId: string
  sourceType: 'CURL' | 'OPENAPI'
  projectRevision: number
  items: ImportPreviewItem[]
  warnings: string[]
  expiresAt: string
}

export interface ImportConfirmResponse {
  definitions: unknown[]
  cases: unknown[]
}

export interface ImportApi {
  previewCurl(projectId: string, input: ImportPreviewRequest): Promise<ImportPreviewResponse>
  previewOpenApi(projectId: string, input: ImportPreviewRequest): Promise<ImportPreviewResponse>
  confirm(projectId: string, previewId: string, choices?: ImportChoice[]): Promise<ImportConfirmResponse>
}

function basePath(projectId: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/imports`
}

export const importApi: ImportApi = {
  previewCurl(projectId, input) {
    return apiRequest<ImportPreviewResponse>(`${basePath(projectId)}/curl/preview`, { method: 'POST', body: input })
  },
  previewOpenApi(projectId, input) {
    return apiRequest<ImportPreviewResponse>(`${basePath(projectId)}/openapi/preview`, { method: 'POST', body: input })
  },
  confirm(projectId, previewId, choices = []) {
    return apiRequest<ImportConfirmResponse>(`${basePath(projectId)}/confirm`, {
      method: 'POST', body: { previewId, choices },
    })
  },
}

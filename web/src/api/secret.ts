import { apiRequest } from './http'

export interface SecretSummary {
  id: string
  projectId: string
  name: string
  mask: '••••••••' | string
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
}

export type SecretResponse = SecretSummary

export interface SecretCreateInput {
  name: string
  value: string
}

export interface SecretReplaceInput {
  value: string
  revision: number
}

export interface SecretApi {
  list(projectId: string, includeArchived?: boolean): Promise<SecretSummary[]>
  create(projectId: string, input: SecretCreateInput): Promise<SecretSummary>
  replace(projectId: string, secretId: string, input: SecretReplaceInput): Promise<SecretSummary>
  update(projectId: string, secretId: string, input: SecretReplaceInput): Promise<SecretSummary>
  archive(projectId: string, secretId: string, revision: number): Promise<SecretSummary>
}

function resourcePath(projectId: string, secretId?: string): string {
  const projectPath = encodeURIComponent(projectId)
  return secretId === undefined
    ? `/api/v1/projects/${projectPath}/secrets`
    : `/api/v1/projects/${projectPath}/secrets/${encodeURIComponent(secretId)}`
}

function replaceSecret(projectId: string, secretId: string, input: SecretReplaceInput): Promise<SecretSummary> {
  return apiRequest<SecretSummary>(resourcePath(projectId, secretId), { method: 'PUT', body: input })
}

export const secretApi: SecretApi = {
  list(projectId, includeArchived = false) {
    return apiRequest<SecretSummary[]>(`${resourcePath(projectId)}?includeArchived=${includeArchived}`)
  },
  create(projectId, input) {
    return apiRequest<SecretSummary>(resourcePath(projectId), { method: 'POST', body: input })
  },
  replace: replaceSecret,
  update: replaceSecret,
  archive(projectId, secretId, revision) {
    return apiRequest<SecretSummary>(`${resourcePath(projectId, secretId)}/archive`, { method: 'POST', body: { revision } })
  },
}

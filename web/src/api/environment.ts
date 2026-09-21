import { apiRequest } from './http'

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }
export type JsonObject = { [key: string]: JsonValue }

export interface EnvironmentParameter {
  name: string
  value: string
  enabled: boolean
}

export interface EnvironmentRequestOptions {
  defaultHeaders?: EnvironmentParameter[]
  followRedirects?: boolean
  connectTimeoutMillis?: number
  responseTimeoutMillis?: number
  readTimeoutMillis?: number
  totalTimeoutMillis?: number
  proxy?: { scheme?: string; host: string; port: number; username?: string; password?: string; passwordSecretRef?: string } | null
  clientCertificate?: { type: 'PKCS12'; fileId?: string; passwordSecretRef?: string; secretRef?: string; passwordRef?: string } | null
}

export interface Environment {
  id: string
  projectId: string
  name: string
  baseUrl: string
  variables: JsonObject
  requestOptions?: EnvironmentRequestOptions
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
}

export type EnvironmentResponse = Environment

export interface EnvironmentWrite {
  name: string
  baseUrl: string
  variables: JsonObject
  requestOptions?: EnvironmentRequestOptions
}

export interface EnvironmentUpdate extends EnvironmentWrite {
  revision: number
}

export interface EnvironmentApi {
  list(projectId: string, includeArchived?: boolean): Promise<Environment[]>
  get(projectId: string, environmentId: string): Promise<Environment>
  create(projectId: string, input: EnvironmentWrite): Promise<Environment>
  update(projectId: string, environmentId: string, input: EnvironmentUpdate): Promise<Environment>
  archive(projectId: string, environmentId: string, revision: number): Promise<Environment>
  restore(projectId: string, environmentId: string, revision: number): Promise<Environment>
}

function resourcePath(projectId: string, environmentId?: string): string {
  const projectPath = encodeURIComponent(projectId)
  return environmentId === undefined
    ? `/api/v1/projects/${projectPath}/environments`
    : `/api/v1/projects/${projectPath}/environments/${encodeURIComponent(environmentId)}`
}

export const environmentApi: EnvironmentApi = {
  list(projectId, includeArchived = false) {
    return apiRequest<Environment[]>(`${resourcePath(projectId)}?includeArchived=${includeArchived}`)
  },
  get(projectId, environmentId) {
    return apiRequest<Environment>(resourcePath(projectId, environmentId))
  },
  create(projectId, input) {
    return apiRequest<Environment>(resourcePath(projectId), { method: 'POST', body: input })
  },
  update(projectId, environmentId, input) {
    return apiRequest<Environment>(resourcePath(projectId, environmentId), { method: 'PUT', body: input })
  },
  archive(projectId, environmentId, revision) {
    return apiRequest<Environment>(`${resourcePath(projectId, environmentId)}/archive`, { method: 'POST', body: { revision } })
  },
  restore(projectId, environmentId, revision) {
    return apiRequest<Environment>(`${resourcePath(projectId, environmentId)}/restore`, { method: 'POST', body: { revision } })
  },
}

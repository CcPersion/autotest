import { apiRequest } from './http'

export type ApiHttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS'
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }
export type JsonObject = { [key: string]: JsonValue }

export interface PathParameter {
  name: string
  value: string
}

export interface RequestParameter extends PathParameter {
  enabled: boolean
}

export interface RequestBody {
  type: 'NONE' | 'JSON' | 'TEXT' | 'URLENCODED' | 'MULTIPART'
  value?: JsonValue
}

export interface RequestOptions {
  followRedirects?: boolean
  connectTimeoutMillis?: number
  responseTimeoutMillis?: number
  readTimeoutMillis?: number
  totalTimeoutMillis?: number
  proxy?: { scheme: string; host: string; port: number; username?: string; password?: string; passwordSecretRef?: string } | null
  clientCertificate?: { type: 'PKCS12'; fileId?: string; passwordSecretRef?: string; secretRef?: string; passwordRef?: string } | null
}

export interface RequestSpec {
  pathParams: PathParameter[]
  query: RequestParameter[]
  headers: RequestParameter[]
  cookies: RequestParameter[]
  body: RequestBody
  options: RequestOptions
}

export interface ApiDefinition {
  id: string
  projectId: string
  moduleId: string | null
  name: string
  method: ApiHttpMethod
  urlTemplate: string
  requestSpec: RequestSpec
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface ApiDefinitionWrite {
  moduleId?: string | null
  name: string
  method: ApiHttpMethod
  urlTemplate: string
  requestSpec: RequestSpec
  revision?: number
}

export interface ApiDefinitionApi {
  list(projectId: string, moduleId?: string | null, includeArchived?: boolean): Promise<ApiDefinition[]>
  get(projectId: string, definitionId: string): Promise<ApiDefinition>
  create(projectId: string, input: ApiDefinitionWrite): Promise<ApiDefinition>
  update(projectId: string, definitionId: string, input: ApiDefinitionWrite & { revision: number }): Promise<ApiDefinition>
  archive(projectId: string, definitionId: string, revision: number): Promise<ApiDefinition>
}

function collectionPath(projectId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/api-definitions`
}

function detailPath(projectId: string, definitionId: string): string {
  return `${collectionPath(projectId)}/${encodeURIComponent(definitionId)}`
}

export const apiDefinitionApi: ApiDefinitionApi = {
  list(projectId, moduleId, includeArchived = false) {
    const query = new URLSearchParams()
    if (moduleId) query.set('moduleId', moduleId)
    query.set('includeArchived', String(includeArchived))
    return apiRequest<ApiDefinition[]>(`${collectionPath(projectId)}?${query.toString()}`)
  },
  get(projectId, definitionId) {
    return apiRequest<ApiDefinition>(detailPath(projectId, definitionId))
  },
  create(projectId, input) {
    return apiRequest<ApiDefinition>(collectionPath(projectId), { method: 'POST', body: input })
  },
  update(projectId, definitionId, input) {
    return apiRequest<ApiDefinition>(detailPath(projectId, definitionId), { method: 'PUT', body: input })
  },
  archive(projectId, definitionId, revision) {
    return apiRequest<ApiDefinition>(`${detailPath(projectId, definitionId)}/archive`, {
      method: 'POST', body: { revision },
    })
  },
}

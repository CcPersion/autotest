import { apiRequest } from './http'
import type { JsonObject, JsonValue, RequestSpec } from './apiDefinition'

export interface CaseSpec extends Omit<RequestSpec, 'pathParams' | 'query' | 'headers' | 'cookies' | 'options'> {
  pathParams: Record<string, string>
  query: Record<string, string>
  headers: Record<string, string>
  cookies: Record<string, string>
  extractors?: ApiExtractor[]
  dataRows?: CaseDataRow[]
  dataRowOptions?: { continueOnFailure: boolean }
}

export interface CaseDataRow {
  id: string
  enabled: boolean
  values: Record<string, JsonValue>
}

export type ApiAssertion = JsonObject
export type ApiExtractor = JsonObject

export interface ApiCase {
  id: string
  projectId: string
  apiDefinitionId: string
  name: string
  caseSpec: CaseSpec
  variables: JsonObject
  assertions: ApiAssertion[]
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface ApiCaseWrite {
  name: string
  caseSpec: CaseSpec
  variables: JsonObject
  assertions: ApiAssertion[]
  revision?: number
}

export interface ApiCaseApi {
  list(projectId: string, definitionId: string, includeArchived?: boolean): Promise<ApiCase[]>
  get(projectId: string, definitionId: string, caseId: string): Promise<ApiCase>
  create(projectId: string, definitionId: string, input: ApiCaseWrite): Promise<ApiCase>
  update(projectId: string, definitionId: string, caseId: string, input: ApiCaseWrite & { revision: number }): Promise<ApiCase>
  archive(projectId: string, definitionId: string, caseId: string, revision: number): Promise<ApiCase>
}

function collectionPath(projectId: string, definitionId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/api-definitions/${encodeURIComponent(definitionId)}/cases`
}

function detailPath(projectId: string, definitionId: string, caseId: string): string {
  return `${collectionPath(projectId, definitionId)}/${encodeURIComponent(caseId)}`
}

export const apiCaseApi: ApiCaseApi = {
  list(projectId, definitionId, includeArchived = false) {
    return apiRequest<ApiCase[]>(`${collectionPath(projectId, definitionId)}?includeArchived=${includeArchived}`)
  },
  get(projectId, definitionId, caseId) {
    return apiRequest<ApiCase>(detailPath(projectId, definitionId, caseId))
  },
  create(projectId, definitionId, input) {
    return apiRequest<ApiCase>(collectionPath(projectId, definitionId), { method: 'POST', body: input })
  },
  update(projectId, definitionId, caseId, input) {
    return apiRequest<ApiCase>(detailPath(projectId, definitionId, caseId), { method: 'PUT', body: input })
  },
  archive(projectId, definitionId, caseId, revision) {
    return apiRequest<ApiCase>(`${detailPath(projectId, definitionId, caseId)}/archive`, {
      method: 'POST', body: { revision },
    })
  },
}

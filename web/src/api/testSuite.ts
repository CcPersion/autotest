import { apiRequest } from './http'
import type { RunRecord } from './run'

export type TestSuiteMemberType = 'API_CASE' | 'SCENARIO'

export interface TestSuiteMember {
  id: string
  position: number
  targetType: TestSuiteMemberType
  targetId: string
  enabled: boolean
}

export interface TestSuite {
  id: string
  projectId: string
  name: string
  description: string | null
  environmentId: string | null
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
  members: TestSuiteMember[]
}

export interface TestSuiteWrite {
  name: string
  description: string
  environmentId: string | null
  members: TestSuiteMember[]
  revision?: number
}

export interface TestSuiteApi {
  list(projectId: string, includeArchived?: boolean): Promise<TestSuite[]>
  get(projectId: string, suiteId: string): Promise<TestSuite>
  create(projectId: string, input: TestSuiteWrite): Promise<TestSuite>
  update(projectId: string, suiteId: string, input: TestSuiteWrite): Promise<TestSuite>
  archive(projectId: string, suiteId: string, revision: number): Promise<TestSuite>
  run(projectId: string, suiteId: string, environmentId: string, idempotencyKey: string): Promise<RunRecord>
}

function collectionPath(projectId: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/test-suites`
}

export const testSuiteApi: TestSuiteApi = {
  list(projectId, includeArchived = false) {
    return apiRequest<TestSuite[]>(`${collectionPath(projectId)}?includeArchived=${includeArchived}`)
  },
  get(projectId, suiteId) {
    return apiRequest<TestSuite>(`${collectionPath(projectId)}/${encodeURIComponent(suiteId)}`)
  },
  create(projectId, input) {
    return apiRequest<TestSuite>(collectionPath(projectId), { method: 'POST', body: input })
  },
  update(projectId, suiteId, input) {
    return apiRequest<TestSuite>(`${collectionPath(projectId)}/${encodeURIComponent(suiteId)}`, { method: 'PUT', body: input })
  },
  archive(projectId, suiteId, revision) {
    return apiRequest<TestSuite>(`${collectionPath(projectId)}/${encodeURIComponent(suiteId)}/archive`, {
      method: 'POST', body: { revision },
    })
  },
  run(projectId, suiteId, environmentId, idempotencyKey) {
    return apiRequest<RunRecord>(`${collectionPath(projectId)}/${encodeURIComponent(suiteId)}/runs`, {
      method: 'POST', body: { environmentId, idempotencyKey },
    })
  },
}

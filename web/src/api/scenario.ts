import { apiRequest } from './http'
import type { JsonObject } from './apiDefinition'
import type { RunRecord } from './run'

export type ScenarioStepKind = 'API_CASE' | 'HTTP' | 'SQL' | 'REDIS' | 'CONDITION' | 'LOOP' | 'WAIT' | 'CLEANUP'
export type ScenarioSection = 'MAIN' | 'CLEANUP'
export type ScenarioReferenceMode = 'REFERENCE' | 'COPY'
export type ScenarioFailureStrategy = 'STOP' | 'CONTINUE' | 'RETRY'

export interface ScenarioStep {
  id: string
  parentId: string | null
  position: number
  kind: ScenarioStepKind
  title: string
  enabled: boolean
  section: ScenarioSection
  referenceMode: ScenarioReferenceMode | null
  apiCaseId: string | null
  failureStrategy: ScenarioFailureStrategy
  stepConfig: JsonObject
}

export interface Scenario {
  id: string
  projectId: string
  name: string
  description: string | null
  variables: JsonObject
  settings: JsonObject
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
  steps: ScenarioStep[]
}

export interface ScenarioStepInput {
  id: string
  parentId: string | null
  position: number
  kind: ScenarioStepKind
  title: string
  enabled: boolean
  section: ScenarioSection
  referenceMode: ScenarioReferenceMode | null
  apiCaseId: string | null
  failureStrategy: ScenarioFailureStrategy
  stepConfig: JsonObject
}

export interface ScenarioWrite {
  name: string
  description: string
  variables: JsonObject
  settings: JsonObject
  steps: ScenarioStepInput[]
  revision?: number
}

export interface ScenarioApi {
  list(projectId: string, includeArchived?: boolean): Promise<Scenario[]>
  get(projectId: string, scenarioId: string): Promise<Scenario>
  create(projectId: string, input: ScenarioWrite): Promise<Scenario>
  update(projectId: string, scenarioId: string, input: ScenarioWrite): Promise<Scenario>
  archive(projectId: string, scenarioId: string, revision: number): Promise<Scenario>
  run(projectId: string, scenarioId: string, environmentId: string, idempotencyKey: string): Promise<RunRecord>
}

function collectionPath(projectId: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/scenarios`
}

export const scenarioApi: ScenarioApi = {
  list(projectId, includeArchived = false) {
    return apiRequest<Scenario[]>(`${collectionPath(projectId)}?includeArchived=${includeArchived}`)
  },
  get(projectId, scenarioId) {
    return apiRequest<Scenario>(`${collectionPath(projectId)}/${encodeURIComponent(scenarioId)}`)
  },
  create(projectId, input) {
    return apiRequest<Scenario>(collectionPath(projectId), { method: 'POST', body: input })
  },
  update(projectId, scenarioId, input) {
    return apiRequest<Scenario>(`${collectionPath(projectId)}/${encodeURIComponent(scenarioId)}`, { method: 'PUT', body: input })
  },
  archive(projectId, scenarioId, revision) {
    return apiRequest<Scenario>(`${collectionPath(projectId)}/${encodeURIComponent(scenarioId)}/archive`, { method: 'POST', body: { revision } })
  },
  run(projectId, scenarioId, environmentId, idempotencyKey) {
    return apiRequest<RunRecord>(`${collectionPath(projectId)}/${encodeURIComponent(scenarioId)}/runs`, {
      method: 'POST', body: { environmentId, idempotencyKey },
    })
  },
}

export function newScenarioStep(kind: ScenarioStepKind, position: number): ScenarioStepInput {
  return {
    id: crypto.randomUUID(), parentId: null, position, kind,
    title: kind === 'API_CASE' ? '引用接口用例' : kind === 'CLEANUP' ? '清理步骤' : `新建${kind}步骤`,
    enabled: true, section: kind === 'CLEANUP' ? 'CLEANUP' : 'MAIN',
    referenceMode: kind === 'API_CASE' ? 'REFERENCE' : null, apiCaseId: null,
    failureStrategy: kind === 'CLEANUP' ? 'CONTINUE' : 'STOP', stepConfig: {},
  }
}

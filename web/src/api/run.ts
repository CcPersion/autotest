import { apiRequest } from './http'
import type { JsonObject, JsonValue } from './apiDefinition'

export type RunStatus = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'CANCELED' | 'INTERRUPTED'

export interface RunRecord {
  id: string
  projectId: string
  environmentId: string
  targetType: string
  targetId: string
  status: RunStatus
  executionPlan: JsonObject
  idempotencyKey: string
  jmeterVersion: string
  startedAt: string | null
  finishedAt: string | null
  exitCode: number | null
  jmxPath: string | null
  jtlPath: string | null
  logPath: string | null
  cancelRequested: boolean
  createdAt: string
}

export interface RunRequest {
  environmentId: string
  targetType: 'API_CASE' | 'SCENARIO' | 'TEST_SUITE'
  targetId: string
  executionPlan: JsonObject
  idempotencyKey: string
}

export interface PlanTargetRequest {
  targetType: 'SAVED_DEFINITION' | 'DRAFT_DEFINITION'
  definitionId?: string
  environmentId: string
  idempotencyKey?: string
  draft?: JsonObject
}

export interface StepResult {
  id: string
  runId: string
  stepId: string
  resultKey: string
  memberId: string | null
  sequenceNo: number
  status: string
  durationMs: number
  requestSummary: JsonValue
  responseSummary: JsonValue
  assertions: JsonValue
  extractions: JsonValue
  errorSummary: JsonValue
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export interface RunReport {
  runId: string
  status: RunStatus
  startedAt: string | null
  finishedAt: string | null
  exitCode: number | null
  cleanupStatus: 'NOT_APPLICABLE' | 'PENDING' | 'PASSED' | 'FAILED' | 'NOT_EXECUTED'
  suiteMembers: Array<{ memberId: string; position: number; targetType: 'API_CASE' | 'SCENARIO'; targetId: string; targetName: string; enabled: boolean }>
  steps: StepResult[]
}

export interface RunApi {
  list(projectId: string, limit?: number): Promise<RunRecord[]>
  create(projectId: string, input: RunRequest): Promise<RunRecord>
  preview(projectId: string, input: PlanTargetRequest): Promise<JsonObject>
  debug(projectId: string, input: PlanTargetRequest): Promise<RunRecord>
  get(projectId: string, runId: string): Promise<RunRecord>
  report(projectId: string, runId: string): Promise<RunReport>
}

function collectionPath(projectId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/runs`
}

export const runApi: RunApi = {
  list(projectId, limit = 20) {
    return apiRequest<RunRecord[]>(`${collectionPath(projectId)}?limit=${encodeURIComponent(limit)}`)
  },
  create(projectId, input) {
    return apiRequest<RunRecord>(collectionPath(projectId), { method: 'POST', body: input })
  },
  preview(projectId, input) {
    return apiRequest<JsonObject>(`/api/v1/projects/${encodeURIComponent(projectId)}/preview`, { method: 'POST', body: input })
  },
  debug(projectId, input) {
    return apiRequest<RunRecord>(`/api/v1/projects/${encodeURIComponent(projectId)}/debug-runs`, { method: 'POST', body: input })
  },
  get(projectId, runId) {
    return apiRequest<RunRecord>(`${collectionPath(projectId)}/${encodeURIComponent(runId)}`)
  },
  report(projectId, runId) {
    return apiRequest<RunReport>(`${collectionPath(projectId)}/${encodeURIComponent(runId)}/report`)
  },
}

import { apiRequest } from './http'
import type { JsonValue } from './apiDefinition'

export interface ExtractorTrialSample {
  statusCode: number
  durationMs: number
  body: string
  headers: Record<string, string>
  cookies: Record<string, string>
}

export interface ExtractorTrialRule {
  type: string
  expression: string
  variable: string
  defaultValue?: JsonValue
  failIfMissing?: boolean
}

export interface ExtractorTrialRequest {
  response: ExtractorTrialSample
  extractors: ExtractorTrialRule[]
}

export type TrialValueType = 'string' | 'number' | 'boolean' | 'null' | 'object' | 'array' | 'missing'

export interface TrialResult {
  ruleIndex: number
  type: string
  expression: string
  variable: string
  matched: boolean
  usedDefault: boolean
  value: JsonValue | null
  valueType: TrialValueType
  failed: boolean
  errorCode: string
  message: string
}

export interface ExtractorTrialResponse {
  results: TrialResult[]
}

export interface ExtractorTrialApi {
  run(projectId: string, input: ExtractorTrialRequest): Promise<ExtractorTrialResponse>
}

export const extractorTrialApi: ExtractorTrialApi = {
  run(projectId, input) {
    return apiRequest<ExtractorTrialResponse>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/extractor-trials`,
      { method: 'POST', body: input },
    )
  },
}

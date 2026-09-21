import type { JsonValue } from '../api/apiDefinition'

export interface TrialExtractor {
  type: string
  expression: string
  variable: string
  defaultValue?: JsonValue | string
  failIfMissing?: boolean
}

export interface TrialResponse {
  status: number
  durationMs: number
  bodyText: string
  headers: Record<string, string>
  cookies: Record<string, string>
}

export type TrialValueType = 'string' | 'number' | 'boolean' | 'null' | 'object' | 'array'

export interface TrialResult {
  variable: string
  type: string
  matched: boolean
  failure: boolean
  value: JsonValue | undefined
  valueType: TrialValueType | 'missing'
  message: string
}

export function runExtractorTrial(extractors: TrialExtractor[], response: TrialResponse): TrialResult[] {
  const body = parseBody(response.bodyText)
  return extractors.map((extractor) => {
    const value = extractValue(extractor, body, response)
    const matched = value !== undefined
    const resultValue = matched ? value : defaultValue(extractor.defaultValue)
    return {
      variable: extractor.variable,
      type: extractor.type,
      matched,
      failure: !matched && extractor.failIfMissing !== false,
      value: resultValue,
      valueType: resultValue === undefined ? 'missing' : valueType(resultValue),
      message: matched ? '已命中' : resultValue === undefined ? '未命中' : '未命中，使用默认值',
    }
  })
}

function parseBody(text: string): JsonValue {
  try {
    return JSON.parse(text) as JsonValue
  } catch {
    return text
  }
}

function extractValue(extractor: TrialExtractor, body: JsonValue, response: TrialResponse): JsonValue | undefined {
  const type = extractor.type.toUpperCase()
  if (type === 'STATUS') return response.status
  if (type === 'HEADER') return findHeader(response.headers, extractor.expression)
  if (type === 'COOKIE') return findHeader(response.cookies, extractor.expression)
  if (type === 'REGEX') {
    try {
      const match = new RegExp(extractor.expression).exec(response.bodyText)
      return match ? (match[1] ?? match[0]) : undefined
    } catch {
      return undefined
    }
  }
  if (type === 'JSON_PATH' || type === 'JMESPATH' || type === 'JMES_PATH') {
    return readPath(body, type === 'JSON_PATH' ? extractor.expression : `$.${extractor.expression.replace(/^\$\.?/, '')}`)
  }
  return undefined
}

function findHeader(values: Record<string, string>, name: string): string | undefined {
  const wanted = name.trim().toLowerCase()
  const key = Object.keys(values).find((item) => item.toLowerCase() === wanted)
  return key === undefined ? undefined : values[key]
}

function readPath(value: JsonValue, expression: string): JsonValue | undefined {
  const normalized = expression.trim()
  if (!normalized.startsWith('$')) return undefined
  const tokenPattern = /(?:^\$)|(?:\.([A-Za-z_][A-Za-z0-9_-]*))|(?:\[['\"]?([^'\"]+)['\"]?\])|(?:\[(\d+)\])/g
  let cursor: JsonValue = value
  let match: RegExpExecArray | null
  let consumed = 0
  while ((match = tokenPattern.exec(normalized)) !== null) {
    consumed = match.index + match[0].length
    const property = match[1] ?? match[2]
    if (property !== undefined) {
      if (!isObject(cursor) || !(property in cursor)) return undefined
      cursor = cursor[property]
      continue
    }
    if (match[3] !== undefined) {
      if (!Array.isArray(cursor)) return undefined
      const index = Number(match[3])
      if (index >= cursor.length) return undefined
      cursor = cursor[index]
    }
  }
  return consumed === normalized.length ? cursor : undefined
}

function isObject(value: JsonValue): value is { [key: string]: JsonValue } {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function defaultValue(value: TrialExtractor['defaultValue']): JsonValue | undefined {
  if (value === undefined || value === '') return undefined
  if (typeof value !== 'string') return value
  try {
    return JSON.parse(value) as JsonValue
  } catch {
    return value
  }
}

function valueType(value: JsonValue): TrialValueType {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  if (typeof value === 'object') return 'object'
  return typeof value as TrialValueType
}

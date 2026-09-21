<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'

import AppIcon from '../components/AppIcon.vue'
import ModuleTree from '../components/ModuleTree.vue'
import { moduleApi as defaultModuleApi, type ModuleApi, type ModuleNode } from '../api/module'
import {
  apiDefinitionApi as defaultDefinitionApi,
  type ApiDefinition,
  type ApiDefinitionApi,
  type ApiDefinitionWrite,
  type JsonValue,
  type RequestBody,
  type RequestOptions,
  type RequestSpec,
  type JsonObject,
} from '../api/apiDefinition'
import {
  apiCaseApi as defaultCaseApi,
  type ApiCase,
  type ApiCaseApi,
  type ApiCaseWrite,
  type CaseSpec,
  type ApiExtractor,
  type CaseDataRow,
} from '../api/apiCase'
import { environmentApi as defaultEnvironmentApi, type EnvironmentApi } from '../api/environment'
import { runApi as defaultRunApi, type RunApi, type RunStatus } from '../api/run'
import { extractorTrialApi as defaultExtractorTrialApi, type ExtractorTrialApi, type TrialResult } from '../api/extractorTrial'
import { parseCsvDataRows, serializeCsvDataRows } from '../utils/csvDataRows'

const props = defineProps<{
  mode: 'api' | 'case'
  projectId?: string | null
  environmentId?: string | null
  moduleApi?: ModuleApi
  definitionApi?: ApiDefinitionApi
  caseApi?: ApiCaseApi
  environmentApi?: EnvironmentApi
  runApi?: RunApi
  extractorTrialApi?: ExtractorTrialApi
  fileAssets?: Array<{ fileId: string; originalName: string; kind: 'REQUEST_FILE' | 'PKCS12' }>
}>()

const emit = defineEmits<{ navigate: [page: string]; runCreated: [runId: string] }>()

interface DefinitionDraft extends ApiDefinitionWrite {
  id?: string
  revision?: number
}

interface CaseDraft extends ApiCaseWrite {
  id?: string
  revision?: number
}

interface ExtractorDraft {
  type: 'JSON_PATH' | 'JMESPATH' | 'XPATH' | 'REGEX' | 'HEADER' | 'COOKIE'
  expression: string
  variable: string
  defaultValue: string
  failIfMissing: boolean
}

interface AssertionDraft {
  type: 'STATUS' | 'JSON_PATH' | 'JMES_PATH' | 'XPATH' | 'BODY' | 'HEADER' | 'COOKIE' | 'SCHEMA' | 'RESPONSE_TIME' | 'VARIABLE'
  operator: string
  expression: string
  expected: string
}

interface UrlEncodedDraft {
  name: string
  value: string
  enabled: boolean
}

interface MultipartDraft {
  name: string
  kind: 'TEXT' | 'FILE'
  value: string
  fileId: string
  contentType: string
  enabled: boolean
}

const moduleClient = computed(() => props.moduleApi ?? defaultModuleApi)
const definitionClient = computed(() => props.definitionApi ?? defaultDefinitionApi)
const caseClient = computed(() => props.caseApi ?? defaultCaseApi)
const environmentClient = computed(() => props.environmentApi ?? defaultEnvironmentApi)
const runClient = computed(() => props.runApi ?? defaultRunApi)
const extractorTrialClient = computed(() => props.extractorTrialApi ?? defaultExtractorTrialApi)
const definitions = shallowRef<ApiDefinition[]>([])
const cases = shallowRef<ApiCase[]>([])
const selectedModuleId = ref<string | null>(null)
const selectedDefinitionId = ref<string | null>(null)
const selectedCaseId = ref<string | null>(null)
const definitionForm = shallowRef<DefinitionDraft | null>(null)
const caseForm = shallowRef<CaseDraft | null>(null)
const bodyText = ref('')
const urlEncodedRows = ref<UrlEncodedDraft[]>([])
const multipartRows = ref<MultipartDraft[]>([])
const caseSpecText = ref('')
const variablesText = ref('{}')
const extractorRows = ref<ExtractorDraft[]>([])
const assertionRows = ref<AssertionDraft[]>([])
const dataRows = ref<CaseDataRow[]>([])
const continueOnDataRowFailure = ref(true)
const requestTab = ref('Query')
const caseTab = ref('请求覆盖')
const searchTerm = ref('')
const loading = ref(false)
const caseLoading = ref(false)
const saving = ref(false)
const running = ref(false)
const errorMessage = ref('')
const trialBodyText = ref('{\n  "token": "demo-token",\n  "profile": { "roles": ["qa", "dev"] }\n}')
const trialHeadersText = ref('{"X-Trace-Id":"trace-demo"}')
const trialCookiesText = ref('{"sid":"cookie-demo"}')
const trialStatus = ref(200)
const trialDuration = ref(42)
const trialResults = shallowRef<TrialResult[]>([])
let generation = 0

const filteredDefinitions = shallowRef<ApiDefinition[]>([])
const selectedDefinition = shallowRef<ApiDefinition | null>(null)
const requestTabs = ['Query', 'Path', 'Header', 'Cookie', 'Body', '高级']
const caseTabs = ['请求覆盖', '用例变量', '数据行', '提取器', '断言']
const extractorTypes: ExtractorDraft['type'][] = ['JSON_PATH', 'JMESPATH', 'XPATH', 'REGEX', 'HEADER', 'COOKIE']
const assertionTypes: AssertionDraft['type'][] = ['STATUS', 'JSON_PATH', 'JMES_PATH', 'XPATH', 'BODY', 'HEADER', 'COOKIE', 'SCHEMA', 'RESPONSE_TIME', 'VARIABLE']
const pathNamePattern = /^[A-Za-z_][A-Za-z0-9_.-]*$/
const dataColumns = computed(() => {
  const columns: string[] = []
  for (const row of dataRows.value) for (const key of Object.keys(row.values)) if (!columns.includes(key)) columns.push(key)
  return columns
})

function jsonText(value: unknown): string {
  if (value === undefined) return ''
  if (typeof value === 'string') return value
  try { return JSON.stringify(value) } catch { return String(value) }
}

function extractorDraftFrom(value: unknown): ExtractorDraft {
  const item = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  const type = extractorTypes.includes(item.type as ExtractorDraft['type']) ? item.type as ExtractorDraft['type'] : 'JSON_PATH'
  return {
    type,
    expression: typeof item.expression === 'string' ? item.expression : '',
    variable: typeof item.variable === 'string' ? item.variable : '',
    defaultValue: jsonText(item.defaultValue),
    failIfMissing: item.failIfMissing !== false,
  }
}

function assertionDraftFrom(value: unknown): AssertionDraft {
  const item = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  const type = assertionTypes.includes(item.type as AssertionDraft['type']) ? item.type as AssertionDraft['type'] : 'STATUS'
  return {
    type,
    operator: typeof item.operator === 'string' ? item.operator : assertionOperators(type)[0],
    expression: typeof item.expression === 'string' ? item.expression : '',
    expected: item.expected === undefined ? '' : JSON.stringify(item.expected),
  }
}

function assertionOperators(type: AssertionDraft['type']): string[] {
  if (type === 'STATUS') return ['EQUALS']
  if (type === 'RESPONSE_TIME') return ['LESS_THAN']
  if (type === 'SCHEMA') return ['VALIDATE']
  if (type === 'XPATH') return ['EXISTS', 'NOT_EXISTS']
  if (type === 'JSON_PATH' || type === 'JMES_PATH') return ['EXISTS', 'NOT_EXISTS', 'EQUALS', 'NOT_EQUALS', 'GREATER_THAN', 'LESS_THAN', 'CONTAINS']
  if (type === 'HEADER' || type === 'COOKIE') return ['EXISTS', 'NOT_EXISTS', 'EQUALS', 'CONTAINS', 'NOT_CONTAINS', 'MATCHES']
  if (type === 'VARIABLE') return ['EQUALS', 'NOT_EQUALS', 'GREATER_THAN', 'LESS_THAN', 'CONTAINS']
  return ['EQUALS', 'CONTAINS', 'NOT_CONTAINS', 'MATCHES']
}

function assertionNeedsExpression(type: AssertionDraft['type']): boolean {
  return !['STATUS', 'RESPONSE_TIME', 'SCHEMA', 'BODY'].includes(type)
}

function assertionNeedsExpected(row: AssertionDraft): boolean {
  if (row.type === 'XPATH') return false
  return !['JSON_PATH', 'JMES_PATH', 'HEADER', 'COOKIE'].includes(row.type)
    || !['EXISTS', 'NOT_EXISTS'].includes(row.operator)
}

function assertionExpectedPlaceholder(type: AssertionDraft['type']): string {
  return type === 'SCHEMA' ? '{"type":"object"}' : '200 或 "ok"'
}

function changeAssertionType(row: AssertionDraft) {
  const operators = assertionOperators(row.type)
  if (!operators.includes(row.operator)) row.operator = operators[0]
  if (!assertionNeedsExpression(row.type)) row.expression = ''
  if (['STATUS', 'RESPONSE_TIME'].includes(row.type) && !row.expected) row.expected = row.type === 'STATUS' ? '200' : '1000'
}

function addExtractor() {
  extractorRows.value.push({ type: 'JSON_PATH', expression: '$.', variable: '', defaultValue: '', failIfMissing: true })
}

function removeExtractor(index: number) {
  extractorRows.value.splice(index, 1)
}

function addAssertion() {
  assertionRows.value.push({ type: 'STATUS', operator: 'EQUALS', expression: '', expected: '200' })
}

function removeAssertion(index: number) {
  assertionRows.value.splice(index, 1)
}

function filterDefinitionItems() {
  const term = searchTerm.value.trim().toLowerCase()
  if (!term) {
    const all: ApiDefinition[] = []
    for (const item of definitions.value as unknown as Array<ApiDefinition>) all.push(item)
    filteredDefinitions.value = all
    return
  }
  const result: ApiDefinition[] = []
  for (const item of definitions.value as unknown as Array<{ name: string; urlTemplate: string }>) {
    if (item.name.toLowerCase().includes(term) || item.urlTemplate.toLowerCase().includes(term)) {
      result.push(item as ApiDefinition)
    }
  }
  filteredDefinitions.value = result
}

function syncSelectedDefinition() {
  selectedDefinition.value = null
  for (const item of definitions.value as unknown as Array<ApiDefinition>) {
    if (item.id === selectedDefinitionId.value) {
      selectedDefinition.value = item
      break
    }
  }
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function emptyOptions(): RequestOptions {
  return { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000, proxy: null }
}

function toggleClientCertificate(event: Event) {
  const form = definitionForm.value
  if (!form) return
  const enabled = (event.target as HTMLInputElement).checked
  const options = { ...form.requestSpec.options,
    clientCertificate: enabled ? { type: 'PKCS12' as const, fileId: '', passwordSecretRef: '' } : null }
  definitionForm.value = { ...form, requestSpec: { ...form.requestSpec, options } }
}

function updateClientCertificate(field: 'fileId' | 'passwordSecretRef', value: string) {
  const form = definitionForm.value
  const certificate = form?.requestSpec.options.clientCertificate
  if (!form || !certificate) return
  const options = { ...form.requestSpec.options,
    clientCertificate: { ...certificate, [field]: value } }
  definitionForm.value = { ...form, requestSpec: { ...form.requestSpec, options } }
}

function updateClientCertificateFromEvent(field: 'fileId' | 'passwordSecretRef', event: Event) {
  updateClientCertificate(field, (event.target as HTMLInputElement).value)
}

function toggleProxy(event: Event) {
  const form = definitionForm.value
  if (!form) return
  const enabled = (event.target as HTMLInputElement).checked
  const options = { ...form.requestSpec.options,
    proxy: enabled ? { scheme: 'http', host: '', port: 8080, username: '', passwordSecretRef: '' } : null }
  definitionForm.value = { ...form, requestSpec: { ...form.requestSpec, options } }
}

function updateProxy(field: 'scheme' | 'host' | 'port' | 'username' | 'passwordSecretRef', value: string | number) {
  const form = definitionForm.value
  const proxy = form?.requestSpec.options.proxy
  if (!form || !proxy) return
  const options = { ...form.requestSpec.options, proxy: { ...proxy, [field]: value } }
  definitionForm.value = { ...form, requestSpec: { ...form.requestSpec, options } }
}

function updateProxyFromEvent(field: 'scheme' | 'host' | 'port' | 'username' | 'passwordSecretRef', event: Event) {
  const input = event.target as HTMLInputElement
  updateProxy(field, field === 'port' ? Number(input.value) : input.value)
}

function emptyRequestSpec(): RequestSpec {
  return { pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: emptyOptions() }
}

function emptyCaseSpec(): CaseSpec {
  return { pathParams: {}, query: {}, headers: {}, cookies: {}, body: { type: 'NONE' } }
}

function multipartRowsFrom(value: unknown): MultipartDraft[] {
  if (Array.isArray(value)) return value.map((item) => {
    const row = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>
    const kind = row.kind === 'FILE' || row.fileId ? 'FILE' : 'TEXT'
    return { name: String(row.name || ''), kind, value: String(row.value || ''), fileId: String(row.fileId || ''), contentType: String(row.contentType || row.mimeType || ''), enabled: row.enabled !== false }
  })
  if (!value || typeof value !== 'object') return []
  const object = value as { fields?: unknown; files?: unknown }
  const fields = Array.isArray(object.fields) ? object.fields : []
  const files = Array.isArray(object.files) ? object.files : []
  return [...fields, ...files].map((item) => {
    const row = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>
    const kind = row.fileId ? 'FILE' : 'TEXT'
    return { name: String(row.name || ''), kind, value: String(row.value || ''), fileId: String(row.fileId || ''), contentType: String(row.contentType || row.mimeType || ''), enabled: row.enabled !== false }
  })
}

function changeBodyType() {
  const form = definitionForm.value
  if (!form) return
  const type = form.requestSpec.body.type
  if (type === 'URLENCODED') {
    urlEncodedRows.value = Array.isArray(form.requestSpec.body.value)
      ? (form.requestSpec.body.value as unknown[]).map((item) => ({ ...(item as UrlEncodedDraft), enabled: (item as UrlEncodedDraft).enabled !== false })) : []
  } else if (type === 'MULTIPART') {
    multipartRows.value = multipartRowsFrom(form.requestSpec.body.value)
  }
  bodyText.value = type === 'JSON' ? JSON.stringify(form.requestSpec.body.value ?? {}, null, 2) : type === 'TEXT' ? String(form.requestSpec.body.value ?? '') : ''
}

function addBodyRow() {
  const form = definitionForm.value
  if (!form) return
  if (form.requestSpec.body.type === 'URLENCODED') urlEncodedRows.value.push({ name: '', value: '', enabled: true })
  if (form.requestSpec.body.type === 'MULTIPART') multipartRows.value.push({ name: '', kind: 'TEXT', value: '', fileId: '', contentType: 'text/plain', enabled: true })
}

function removeBodyRow(index: number) {
  const form = definitionForm.value
  if (!form) return
  if (form.requestSpec.body.type === 'URLENCODED') urlEncodedRows.value.splice(index, 1)
  if (form.requestSpec.body.type === 'MULTIPART') multipartRows.value.splice(index, 1)
}

function bodyValueForSave(type: RequestBody['type']): JsonValue | undefined {
  if (type === 'URLENCODED') return urlEncodedRows.value.map((row) => ({ name: row.name, value: row.value, enabled: row.enabled }))
  if (type === 'MULTIPART') return multipartRows.value.map((row) => ({ name: row.name, kind: row.kind, ...(row.kind === 'FILE' ? { fileId: row.fileId, contentType: row.contentType } : { value: row.value }), enabled: row.enabled }))
  return undefined
}

function errorText(error: unknown): string {
  const value = error as { code?: string; message?: string; status?: number }
  if (value.code === 'REVISION_CONFLICT') return '版本已变化，请刷新后重试'
  if (value.code === 'NAME_CONFLICT') return '名称已存在，请换一个名称'
  if (value.code === 'PROJECT_ARCHIVED') return '项目已归档，不能修改接口'
  if (value.code === 'PARENT_ARCHIVED') return '接口已归档，不能修改用例'
  if (value.code === 'VALIDATION_FAILED' || value.code === 'INVALID_JSON') return value.message || '请求参数不合法'
  if (value.status === 409) return '操作冲突，请刷新后重试'
  return value.message || '操作失败，请稍后重试'
}

function definitionDraftFrom(item: ApiDefinition): DefinitionDraft {
  const requestSpec = clone(item.requestSpec || emptyRequestSpec())
  requestSpec.pathParams ||= []
  requestSpec.query = (requestSpec.query || []).map((row) => ({ ...row, enabled: row.enabled !== false }))
  requestSpec.headers = (requestSpec.headers || []).map((row) => ({ ...row, enabled: row.enabled !== false }))
  requestSpec.cookies = (requestSpec.cookies || []).map((row) => ({ ...row, enabled: row.enabled !== false }))
  requestSpec.body ||= { type: 'NONE' }
  requestSpec.options ||= emptyOptions()
  return { id: item.id, moduleId: item.moduleId, name: item.name, method: item.method, urlTemplate: item.urlTemplate, requestSpec, revision: item.revision }
}

function setDefinitionForm(item: ApiDefinition | null) {
  definitionForm.value = item ? definitionDraftFrom(item) : {
    moduleId: selectedModuleId.value,
    name: '', method: 'GET', urlTemplate: '', requestSpec: emptyRequestSpec(),
  }
  const body = definitionForm.value.requestSpec.body
  bodyText.value = body.type === 'JSON' ? JSON.stringify(body.value ?? {}, null, 2) : ''
  changeBodyType()
  requestTab.value = 'Query'
}

watch(() => definitionForm.value?.method, (method) => {
  const form = definitionForm.value
  if (!form) return
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method || '') && form.requestSpec.body.type === 'NONE') {
    form.requestSpec.body = { type: 'JSON', value: {} }
    bodyText.value = '{}'
  } else if (['GET', 'HEAD', 'OPTIONS'].includes(method || '') && form.requestSpec.body.type !== 'NONE') {
    form.requestSpec.body = { type: 'NONE' }
    bodyText.value = ''
  }
}, { flush: 'sync' })

function caseDraftFrom(item: ApiCase): CaseDraft {
  return { id: item.id, name: item.name, caseSpec: clone(item.caseSpec), variables: clone(item.variables), assertions: clone(item.assertions), revision: item.revision }
}

function setCaseForm(item: ApiCase | null) {
  caseForm.value = item ? caseDraftFrom(item) : { name: '', caseSpec: emptyCaseSpec(), variables: {}, assertions: [] }
  caseSpecText.value = JSON.stringify(caseForm.value.caseSpec, null, 2)
  variablesText.value = JSON.stringify(caseForm.value.variables, null, 2)
  extractorRows.value = (caseForm.value.caseSpec.extractors ?? []).map(extractorDraftFrom)
  assertionRows.value = caseForm.value.assertions.map(assertionDraftFrom)
  dataRows.value = (caseForm.value.caseSpec.dataRows ?? []).map((row) => ({
    id: row.id || newRowId(), enabled: row.enabled !== false, values: { ...row.values },
  }))
  continueOnDataRowFailure.value = caseForm.value.caseSpec.dataRowOptions?.continueOnFailure !== false
  trialResults.value = []
  caseTab.value = '请求覆盖'
}

function newRowId(): string {
  return globalThis.crypto?.randomUUID?.() || `row-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function addDataRow(values: Record<string, string> = {}) {
  const initial = dataColumns.value.length ? Object.fromEntries(dataColumns.value.map((column) => [column, values[column] ?? ''])) : values
  dataRows.value.push({ id: newRowId(), enabled: true, values: initial })
}

function addEmptyDataRow() {
  addDataRow()
}

function removeDataRow(index: number) {
  dataRows.value.splice(index, 1)
}

function addDataColumn() {
  const column = `field${dataColumns.value.length + 1}`
  for (const row of dataRows.value) row.values[column] = ''
  if (!dataRows.value.length) addDataRow({ [column]: '' })
}

function exportDataRows() {
  const content = serializeCsvDataRows(dataRows.value.map((row) => row.values))
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8' })
  const anchor = document.createElement('a')
  anchor.href = URL.createObjectURL(blob)
  anchor.download = `${caseForm.value?.name || 'data-rows'}.csv`
  anchor.click()
  URL.revokeObjectURL(anchor.href)
}

async function importDataRows(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const rows = parseCsvDataRows(await file.text())
    dataRows.value = rows.map((values) => ({ id: newRowId(), enabled: true, values }))
    errorMessage.value = ''
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || 'CSV 导入失败'
  } finally {
    input.value = ''
  }
}

async function runTrial() {
  errorMessage.value = ''
  try {
    if (!props.projectId) {
      errorMessage.value = '缺少项目，无法运行试算'
      return
    }
    const headers = parseObjectJson(trialHeadersText.value, '响应头必须是 JSON 对象')
    const cookies = parseObjectJson(trialCookiesText.value, '响应 Cookie 必须是 JSON 对象')
    if (!headers || !cookies) return
    const result = await extractorTrialClient.value.run(props.projectId, {
      response: {
        statusCode: trialStatus.value,
        durationMs: trialDuration.value,
        body: trialBodyText.value,
        headers: stringMap(headers),
        cookies: stringMap(cookies),
      },
      extractors: extractorRows.value.map((row) => ({
        type: row.type,
        expression: row.expression.trim(),
        variable: row.variable.trim(),
        defaultValue: parseTrialDefault(row.defaultValue),
        failIfMissing: row.failIfMissing,
      })),
    })
    trialResults.value = result.results
  } catch (error) {
    errorMessage.value = errorText(error)
  }
}

function trialValueText(value: TrialResult['value']): string {
  if (value === null) return 'null'
  return typeof value === 'string' ? value : JSON.stringify(value)
}

function stringMap(value: JsonObject): Record<string, string> {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, String(item)]))
}

function parseTrialDefault(text: string): JsonValue | undefined {
  if (!text.trim()) return undefined
  try { return JSON.parse(text) as JsonValue } catch { return text }
}

async function loadCases(projectId: string, definitionId: string, requestGeneration: number) {
  caseLoading.value = true
  try {
    const items = await caseClient.value.list(projectId, definitionId, false)
    if (requestGeneration !== generation || props.projectId !== projectId || selectedDefinitionId.value !== definitionId) return
    cases.value = items.filter((item) => !item.archived)
    selectedCaseId.value = cases.value[0]?.id ?? null
    caseForm.value = null
  } catch (error) {
    if (requestGeneration === generation) errorMessage.value = errorText(error)
  } finally {
    if (requestGeneration === generation) caseLoading.value = false
  }
}

async function loadDefinitions(requestGeneration = generation, moduleId = selectedModuleId.value) {
  const projectId = props.projectId
  if (!projectId) return
  loading.value = true
  errorMessage.value = ''
  try {
    const items = await definitionClient.value.list(projectId, moduleId ?? undefined, false)
    if (requestGeneration !== generation || props.projectId !== projectId) return
    definitions.value = items.filter((item) => !item.archived)
    filterDefinitionItems()
    let next: ApiDefinition | null = null
    for (const item of definitions.value as unknown as Array<ApiDefinition>) {
      if (item.id === selectedDefinitionId.value) {
        next = item
        break
      }
    }
    next ||= definitions.value[0] ?? null
    selectedDefinitionId.value = next?.id ?? null
    syncSelectedDefinition()
    if (props.mode === 'api') {
      if (next && !definitionForm.value?.id) setDefinitionForm(next)
      if (!next) setDefinitionForm(null)
    } else if (next) {
      await loadCases(projectId, next.id, requestGeneration)
    } else {
      cases.value = []
      caseForm.value = null
    }
  } catch (error) {
    if (requestGeneration === generation) errorMessage.value = errorText(error)
  } finally {
    if (requestGeneration === generation) loading.value = false
  }
}

function selectModule(node: ModuleNode) {
  selectedModuleId.value = node.id
  selectedDefinitionId.value = null
  definitionForm.value = null
  cases.value = []
  caseForm.value = null
  const requestGeneration = ++generation
  void loadDefinitions(requestGeneration, node.id)
}

function selectDefinition(item: ApiDefinition) {
  selectedDefinitionId.value = item.id
  selectedDefinition.value = item
  errorMessage.value = ''
  if (props.mode === 'api') {
    setDefinitionForm(item)
  } else if (props.projectId) {
    void loadCases(props.projectId, item.id, generation)
  }
}

function newDefinition() {
  errorMessage.value = ''
  selectedDefinitionId.value = null
  selectedDefinition.value = null
  setDefinitionForm(null)
  requestTab.value = 'Body'
}

function newCase() {
  errorMessage.value = ''
  selectedCaseId.value = null
  setCaseForm(null)
}

function changeDefinitionMethod() {
  const form = definitionForm.value
  if (!form) return
  const body: RequestBody = !['GET', 'HEAD', 'OPTIONS'].includes(form.method)
    ? { type: 'JSON', value: {} }
    : { type: 'NONE' }
  definitionForm.value = { ...form, requestSpec: { ...form.requestSpec, body } }
  bodyText.value = body.type === 'JSON' ? JSON.stringify(body.value, null, 2) : ''
  changeBodyType()
}

function addParameter(kind: 'query' | 'headers' | 'cookies') {
  const form = definitionForm.value
  if (!form) return
  definitionForm.value = {
    ...form,
    requestSpec: {
      ...form.requestSpec,
      [kind]: [...form.requestSpec[kind], { name: '', value: '', enabled: true }],
    },
  }
}

function removeParameter(kind: 'query' | 'headers' | 'cookies', index: number) {
  const form = definitionForm.value
  if (!form) return
  definitionForm.value = {
    ...form,
    requestSpec: {
      ...form.requestSpec,
      [kind]: form.requestSpec[kind].filter((_, rowIndex) => rowIndex !== index),
    },
  }
}

function syncPathParams() {
  const form = definitionForm.value
  if (!form) return
  const sanitizedUrl = form.urlTemplate.replace(/\$\{[^{}]*\}/g, 'x')
  const names: string[] = []
  for (const match of sanitizedUrl.matchAll(/\{([^{}]+)\}/g)) {
    const name = match[1]
    if (pathNamePattern.test(name) && !names.includes(name)) names.push(name)
  }
  const pathParams = names.map((name) => {
    for (const item of form.requestSpec.pathParams as Array<{ name: string; value: string }>) {
      if (item.name === name) return item
    }
    return { name, value: '' }
  })
  definitionForm.value = { ...form, requestSpec: { ...form.requestSpec, pathParams } }
}

function updateUrlTemplate(event: Event) {
  const form = definitionForm.value
  if (!form) return
  definitionForm.value = { ...form, urlTemplate: (event.target as HTMLInputElement).value }
  syncPathParams()
}

function parseObjectJson(text: string, message: string): JsonObject | null {
  try {
    const value = JSON.parse(text) as unknown
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('not-object')
    return value as JsonObject
  } catch {
    errorMessage.value = message
    return null
  }
}

function parseJsonValue(text: string, message: string): JsonValue | null {
  try {
    return JSON.parse(text) as JsonValue
  } catch {
    errorMessage.value = message
    return null
  }
}

async function saveDefinition() {
  if (saving.value || !props.projectId || !definitionForm.value) return null
  syncPathParams()
  const form = definitionForm.value
  let body: RequestBody = { type: 'NONE' }
  if (form.requestSpec.body.type !== 'NONE') {
    if (form.requestSpec.body.type === 'TEXT') {
      body = { type: 'TEXT', value: bodyText.value }
    } else if (form.requestSpec.body.type === 'URLENCODED' || form.requestSpec.body.type === 'MULTIPART') {
      body = { type: form.requestSpec.body.type, value: bodyValueForSave(form.requestSpec.body.type) }
    } else {
      const parsedBody = parseJsonValue(bodyText.value,
        `${form.requestSpec.body.type} Body 格式不正确`)
       if (parsedBody === null) return null
      body = { type: form.requestSpec.body.type, value: parsedBody }
    }
  }
  const input: ApiDefinitionWrite = {
    moduleId: form.moduleId,
    name: form.name,
    method: form.method,
    urlTemplate: form.urlTemplate,
    requestSpec: { ...clone(form.requestSpec), body },
  }
  saving.value = true
  errorMessage.value = ''
  try {
    const saved = form.id
      ? await definitionClient.value.update(props.projectId, form.id, { ...input, revision: form.revision ?? 0 })
      : await definitionClient.value.create(props.projectId, input)
    selectedDefinitionId.value = saved.id
    definitionForm.value = definitionDraftFrom(saved)
    await loadDefinitions(generation, selectedModuleId.value)
    return saved
  } catch (error) {
    errorMessage.value = errorText(error)
    return null
  } finally {
    saving.value = false
  }
}

function resolveDefinitionPath(definition: ApiDefinition): string {
  const values = new Map((definition.requestSpec.pathParams || []).map((item) => [item.name, item.value]))
  return definition.urlTemplate.replace(/\{([^{}]+)\}/g, (match, name: string) => {
    const value = values.get(name)
    if (value === undefined || value === '') return match
    return value.startsWith('${') && value.endsWith('}') ? value : encodeURIComponent(value)
  })
}

function mergedDefinitionHeaders(definition: ApiDefinition, environment: { requestOptions?: { defaultHeaders?: Array<{ name: string; value: string; enabled: boolean }> } }) {
  const values = new Map<string, { name: string; value: string; enabled: boolean }>()
  for (const item of environment.requestOptions?.defaultHeaders || []) values.set(item.name.toLowerCase(), item)
  for (const item of definition.requestSpec.headers || []) values.set(item.name.toLowerCase(), item)
  return [...values.values()]
}

async function sendRequest() {
  if (running.value || !props.projectId) return
  if (!props.environmentId) {
    errorMessage.value = '请先选择运行环境'
    return
  }
  running.value = true
  errorMessage.value = ''
  try {
    const saved = await saveDefinition()
    if (!saved) return
    const run = await runClient.value.debug(props.projectId, {
      targetType: 'SAVED_DEFINITION',
      definitionId: saved.id,
      environmentId: props.environmentId,
      idempotencyKey: `studio-${globalThis.crypto?.randomUUID?.() || Date.now()}`,
    })
    emit('runCreated', run.id)
    let status: RunStatus = run.status
    for (let attempt = 0; attempt < 60 && !['PASSED', 'FAILED', 'CANCELED', 'INTERRUPTED'].includes(status); attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 500))
      status = (await runClient.value.get(props.projectId!, run.id)).status
    }
    emit('navigate', 'report')
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    running.value = false
  }
}

async function archiveDefinition() {
  if (!props.projectId || !definitionForm.value?.id || !globalThis.confirm(`归档接口“${definitionForm.value.name}”？`)) return
  saving.value = true
  errorMessage.value = ''
  try {
    await definitionClient.value.archive(props.projectId, definitionForm.value.id, definitionForm.value.revision ?? 0)
    definitionForm.value = null
    selectedDefinitionId.value = null
    await loadDefinitions(generation, selectedModuleId.value)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

function selectCase(item: ApiCase) {
  selectedCaseId.value = item.id
  setCaseForm(item)
}

async function saveCase() {
  if (saving.value || !props.projectId || !selectedDefinition.value || !caseForm.value) return
  const caseSpec = parseObjectJson(caseSpecText.value, '请求覆盖必须是 JSON 对象') as unknown as CaseSpec | null
  const variables = parseObjectJson(variablesText.value, '用例变量必须是 JSON 对象')
  if (!caseSpec || !variables) return
  caseSpec.dataRows = dataRows.value.map((row) => ({ id: row.id, enabled: row.enabled, values: { ...row.values } }))
  caseSpec.dataRowOptions = { continueOnFailure: continueOnDataRowFailure.value }
  caseSpec.extractors = extractorRows.value.map((row) => {
    const extractor: Record<string, unknown> = {
      type: row.type, expression: row.expression.trim(), variable: row.variable.trim(), failIfMissing: row.failIfMissing,
    }
    const defaultValue = parseTrialDefault(row.defaultValue)
    if (defaultValue !== undefined) extractor.defaultValue = defaultValue
    return extractor as ApiExtractor
  })
  const assertions: ApiCaseWrite['assertions'] = []
  try {
    for (const row of assertionRows.value) {
      const assertion: Record<string, unknown> = { type: row.type, operator: row.operator }
      if (assertionNeedsExpression(row.type)) {
        const expression = row.expression.trim()
        if (!expression) {
          errorMessage.value = `${row.type} 断言必须填写表达式`
          return
        }
        assertion.expression = expression
      }
      if (assertionNeedsExpected(row) && row.expected.trim()) assertion.expected = JSON.parse(row.expected)
      assertions.push(assertion as ApiCaseWrite['assertions'][number])
    }
  } catch {
    errorMessage.value = '断言 expected 必须是合法 JSON'
    return
  }
  const input: ApiCaseWrite = { name: caseForm.value.name, caseSpec, variables, assertions }
  saving.value = true
  errorMessage.value = ''
  try {
    const saved = caseForm.value.id
      ? await caseClient.value.update(props.projectId, selectedDefinition.value.id, caseForm.value.id, { ...input, revision: caseForm.value.revision ?? 0 })
      : await caseClient.value.create(props.projectId, selectedDefinition.value.id, input)
    selectedCaseId.value = saved.id
    await loadCases(props.projectId, selectedDefinition.value.id, generation)
    setCaseForm(saved)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function archiveCase() {
  if (!props.projectId || !selectedDefinition.value || !caseForm.value?.id || !globalThis.confirm(`归档用例“${caseForm.value.name}”？`)) return
  saving.value = true
  errorMessage.value = ''
  try {
    await caseClient.value.archive(props.projectId, selectedDefinition.value.id, caseForm.value.id, caseForm.value.revision ?? 0)
    await loadCases(props.projectId, selectedDefinition.value.id, generation)
    caseForm.value = null
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

function refresh() {
  const requestGeneration = ++generation
  void loadDefinitions(requestGeneration, selectedModuleId.value)
}

watch(() => props.projectId, (projectId) => {
  generation++
  selectedModuleId.value = null
  selectedDefinitionId.value = null
  selectedDefinition.value = null
  selectedCaseId.value = null
  definitions.value = []
  cases.value = []
  definitionForm.value = null
  caseForm.value = null
  errorMessage.value = ''
  if (projectId) void loadDefinitions(generation, null)
}, { immediate: true })

watch(searchTerm, filterDefinitionItems)
</script>

<template>
  <section class="studio-shell" data-testid="api-studio">
    <aside class="asset-sidebar">
      <div class="asset-tools">
        <div class="small-search"><AppIcon name="search" :size="14" /><input v-model="searchTerm" placeholder="搜索接口" /></div>
        <button class="icon-button" data-action="create-definition" aria-label="新建接口" @click="props.mode === 'api' ? newDefinition() : newCase()"><AppIcon name="plus" :size="17" /></button>
      </div>
      <div class="asset-scope"><button class="active">全部</button><button>我维护的</button></div>
      <ModuleTree v-if="props.projectId" :key="props.projectId" :project-id="props.projectId" :api="moduleClient" @select="selectModule" />
      <div v-else class="asset-empty-state">请先创建或选择项目</div>

      <div v-if="props.projectId" class="endpoint-tree">
        <section>
          <header><strong>{{ props.mode === 'api' ? '接口定义' : '接口' }}</strong><small>{{ filteredDefinitions.length }}</small></header>
          <p v-if="loading" class="asset-empty-state">加载中…</p>
          <p v-else-if="!filteredDefinitions.length" class="asset-empty-state">暂无接口</p>
          <button v-for="item in filteredDefinitions" v-else :key="item.id" :class="{ selected: item.id === selectedDefinitionId }" :data-definition-id="item.id" @click="selectDefinition(item)">
            <span class="method-mini" :class="item.method.toLowerCase()">{{ item.method }}</span><span>{{ item.name }}</span>
          </button>
        </section>
        <section v-if="props.mode === 'case'" class="endpoint-case-list">
          <header><strong>接口用例</strong><small>{{ cases.length }}</small></header>
          <p v-if="caseLoading" class="asset-empty-state">加载中…</p>
          <p v-else-if="!cases.length" class="asset-empty-state">暂无用例</p>
          <button v-for="item in cases" v-else :key="item.id" :class="{ selected: item.id === selectedCaseId }" data-action="edit-case" :data-case-id="item.id" @click="selectCase(item)">
            <span class="method-mini">CASE</span><span>{{ item.name }}</span><small>rev {{ item.revision }}</small>
          </button>
          <button v-if="selectedDefinition" data-action="create-case" @click="newCase"><AppIcon name="plus" :size="13" /> 新建用例</button>
          <button data-action="refresh-cases" @click="selectedDefinition && props.projectId && loadCases(props.projectId, selectedDefinition.id, generation)"><AppIcon name="refresh" :size="13" /> 刷新用例</button>
        </section>
      </div>
    </aside>

    <div class="request-workbench">
      <header class="editor-header">
        <div class="breadcrumb"><span>{{ props.mode === 'api' ? '接口定义' : '接口用例' }}</span><i>/</i><strong>{{ props.mode === 'api' ? (definitionForm?.name || '新建接口') : (caseForm?.name || selectedDefinition?.name || '选择接口') }}</strong></div>
        <div>
          <button class="secondary-button" data-action="refresh-studio" @click="refresh">刷新</button>
          <button v-if="props.mode === 'api'" class="secondary-button" data-action="archive-definition" :disabled="saving || !definitionForm?.id" @click="archiveDefinition">归档</button>
          <button v-if="props.mode === 'case'" class="secondary-button" data-action="archive-case" :disabled="saving || !caseForm?.id" @click="archiveCase">归档</button>
          <button class="primary-button" :data-action="props.mode === 'api' ? 'save-definition' : 'save-case'" :disabled="saving || (!definitionForm && props.mode === 'api') || (!caseForm && props.mode === 'case')" @click="props.mode === 'api' ? saveDefinition() : saveCase()">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </header>

      <template v-if="props.mode === 'api'">
        <form v-if="definitionForm" class="api-definition-form" data-form="definition" @submit.prevent="saveDefinition">
          <label class="field-label definition-name-field">接口名称<input v-model="definitionForm.name" name="definition-name" required /></label>
        <div class="request-line">
          <select v-model="definitionForm.method" class="method-select" name="definition-method" @change="changeDefinitionMethod"><option v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']" :key="method" :value="method">{{ method }}</option></select>
          <div class="url-input"><span>URL</span><input :value="definitionForm.urlTemplate" name="definition-url" placeholder="/v1/orders/{orderId}" @input="updateUrlTemplate" /></div>
          <button class="secondary-button send-request" data-action="send-request" :disabled="saving || running" @click="sendRequest">{{ running ? '运行中…' : '发送' }}</button>
        </div>
        <nav class="tabbar request-tabs">
          <button v-for="tab in requestTabs" :key="tab" type="button" :class="{ active: requestTab === tab }" @click="requestTab = tab">{{ tab }}<span v-if="tab === 'Query' && definitionForm.requestSpec.query.length">{{ definitionForm.requestSpec.query.length }}</span><span v-if="tab === 'Header' && definitionForm.requestSpec.headers.length">{{ definitionForm.requestSpec.headers.length }}</span><span v-if="tab === 'Cookie' && definitionForm.requestSpec.cookies.length">{{ definitionForm.requestSpec.cookies.length }}</span></button>
        </nav>
        <div class="request-editor">
          <div v-if="requestTab === 'Body'" class="body-editor">
            <div class="body-toolbar"><select v-model="definitionForm.requestSpec.body.type" name="definition-body-type" @change="changeBodyType"><option value="NONE">NONE</option><option value="JSON">JSON</option><option value="TEXT">TEXT</option><option value="URLENCODED">URLENCODED</option><option value="MULTIPART">MULTIPART</option></select><span></span><small>请求体由 Runner 按类型编译为 JMeter 组件</small></div>
            <textarea v-if="definitionForm.requestSpec.body.type === 'JSON' || definitionForm.requestSpec.body.type === 'TEXT'" v-model="bodyText" name="definition-body" class="json-editor" spellcheck="false" :placeholder="definitionForm.requestSpec.body.type === 'TEXT' ? '纯文本请求体' : '{\n  &quot;key&quot;: &quot;value&quot;\n}'" />
            <div v-else-if="definitionForm.requestSpec.body.type === 'URLENCODED'" class="key-value-table body-rows">
              <div class="kv-head"><span>启用</span><span>名称</span><span>值</span><span></span></div>
              <div v-for="(row, index) in urlEncodedRows" :key="`form-${index}`" class="kv-row"><input v-model="row.enabled" type="checkbox" /><input v-model="row.name" placeholder="字段名" /><input v-model="row.value" placeholder="值或变量" /><button type="button" aria-label="删除 Body 参数" @click="removeBodyRow(index)">×</button></div>
              <button type="button" class="table-add" data-action="add-urlencoded" @click="addBodyRow">＋ 添加 URL Encoded 参数</button>
            </div>
            <div v-else-if="definitionForm.requestSpec.body.type === 'MULTIPART'" class="key-value-table body-rows">
              <div class="kv-head"><span>启用</span><span>名称</span><span>类型</span><span>值 / 文件</span><span>Content-Type</span><span></span></div>
              <div v-for="(row, index) in multipartRows" :key="`multipart-${index}`" class="kv-row"><input v-model="row.enabled" type="checkbox" /><input v-model="row.name" placeholder="字段名" /><select v-model="row.kind"><option value="TEXT">TEXT</option><option value="FILE">FILE</option></select><input v-if="row.kind === 'TEXT'" v-model="row.value" placeholder="文本值" /><select v-else-if="props.fileAssets?.length" v-model="row.fileId"><option value="">选择文件</option><option v-for="asset in props.fileAssets.filter((item) => item.kind === 'REQUEST_FILE')" :key="asset.fileId" :value="asset.fileId">{{ asset.originalName }}</option></select><input v-else v-model="row.fileId" placeholder="fileId" /><input v-model="row.contentType" placeholder="可选 MIME" /><button type="button" aria-label="删除 Multipart 参数" @click="removeBodyRow(index)">×</button></div>
              <button type="button" class="table-add" data-action="add-multipart" @click="addBodyRow">＋ 添加 Multipart 参数</button>
            </div>
            <p v-else class="empty-editor">当前请求不包含 Body</p>
          </div>
          <div v-else-if="requestTab === 'Path'" class="key-value-table">
            <div class="kv-head"><span>Path 参数</span><span>名称</span><span>值</span><span></span></div>
            <div v-for="row in definitionForm.requestSpec.pathParams" :key="row.name" class="kv-row path-row"><span>•</span><input v-model="row.name" readonly /><input v-model="row.value" placeholder="路径值或变量" /><span></span></div>
            <p v-if="!definitionForm.requestSpec.pathParams.length" class="asset-empty-state">在 URL 中使用 {name} 自动生成 Path 参数</p>
          </div>
          <div v-else-if="requestTab === 'Query' || requestTab === 'Header' || requestTab === 'Cookie'" class="key-value-table">
            <div class="kv-head"><span></span><span>参数名</span><span>值</span><span>启用</span><span></span></div>
            <div v-for="(row, index) in definitionForm.requestSpec[requestTab === 'Query' ? 'query' : requestTab === 'Header' ? 'headers' : 'cookies']" :key="`${requestTab}-${index}`" class="kv-row">
              <input v-model="row.enabled" type="checkbox" />
              <input v-model="row.name" placeholder="参数名" />
              <input v-model="row.value" placeholder="值或变量" />
              <span>{{ row.enabled ? '启用' : '停用' }}</span>
              <button type="button" aria-label="删除参数" @click="removeParameter(requestTab === 'Query' ? 'query' : 'headers', index)">×</button>
            </div>
            <button type="button" class="table-add" :data-action="requestTab === 'Query' ? 'add-query' : requestTab === 'Header' ? 'add-header' : 'add-cookie'" @click="addParameter(requestTab === 'Query' ? 'query' : requestTab === 'Header' ? 'headers' : 'cookies')"><AppIcon name="plus" :size="14" /> 添加{{ requestTab === 'Query' ? 'Query' : requestTab === 'Header' ? 'Header' : 'Cookie' }}参数</button>
          </div>
          <div v-else-if="requestTab === '高级'" class="advanced-editor">
            <label><input v-model="definitionForm.requestSpec.options.followRedirects" type="checkbox" /> 跟随重定向</label>
            <label>连接超时（毫秒）<input v-model.number="definitionForm.requestSpec.options.connectTimeoutMillis" type="number" min="0" /></label>
            <label>响应超时（毫秒）<input v-model.number="definitionForm.requestSpec.options.responseTimeoutMillis" type="number" min="0" /></label>
            <label><input :checked="Boolean(definitionForm.requestSpec.options.proxy)" type="checkbox" @change="toggleProxy" /> 使用 HTTP 代理</label>
            <div v-if="definitionForm.requestSpec.options.proxy" class="proxy-fields">
              <label>代理协议<select :value="definitionForm.requestSpec.options.proxy.scheme" @change="updateProxyFromEvent('scheme', $event)"><option value="http">HTTP</option></select></label>
              <label>代理主机<input :value="definitionForm.requestSpec.options.proxy.host" placeholder="proxy.internal" @input="updateProxyFromEvent('host', $event)" /></label>
              <label>代理端口<input :value="definitionForm.requestSpec.options.proxy.port" type="number" min="1" max="65535" @input="updateProxyFromEvent('port', $event)" /></label>
              <label>代理用户名<input :value="definitionForm.requestSpec.options.proxy.username" @input="updateProxyFromEvent('username', $event)" /></label>
              <label>代理密码 secret 引用<input :value="definitionForm.requestSpec.options.proxy.passwordSecretRef" placeholder="${secret:proxy-password}" @input="updateProxyFromEvent('passwordSecretRef', $event)" /></label>
            </div>
            <label><input :checked="Boolean(definitionForm.requestSpec.options.clientCertificate)" type="checkbox" @change="toggleClientCertificate" /> 使用 PKCS12 客户端证书</label>
            <div v-if="definitionForm.requestSpec.options.clientCertificate" class="client-cert-fields">
              <label>PKCS12 文件<select v-if="props.fileAssets?.length" :value="definitionForm.requestSpec.options.clientCertificate.fileId" @change="updateClientCertificateFromEvent('fileId', $event)"><option value="">选择证书文件</option><option v-for="asset in props.fileAssets.filter((item) => item.kind === 'PKCS12')" :key="asset.fileId" :value="asset.fileId">{{ asset.originalName }}</option></select><input v-else :value="definitionForm.requestSpec.options.clientCertificate.fileId" placeholder="fileId" @input="updateClientCertificateFromEvent('fileId', $event)" /></label>
              <label>证书密码 secret 引用<input :value="definitionForm.requestSpec.options.clientCertificate.passwordSecretRef" placeholder="${secret:client-cert-password}" @input="updateClientCertificateFromEvent('passwordSecretRef', $event)" /></label>
              <small>只保存 fileId 和密钥引用；证书内容由 Runner 在执行时临时加载。</small>
            </div>
          </div>
        </div>
        </form>
        <div v-else class="empty-editor"><strong>选择或新建接口</strong><p>接口保存后可继续创建接口用例。</p></div>
      </template>

      <template v-else>
        <div v-if="selectedDefinition" class="case-identity">
          <div><span class="method-large">{{ selectedDefinition.method }}</span><strong>{{ selectedDefinition.name }}</strong><small>{{ cases.length }} 个已保存用例 · 仅保存配置，不在浏览器发送</small></div>
          <button class="secondary-button" data-action="new-case" @click="newCase">新建用例</button>
        </div>
        <div v-else class="empty-editor"><strong>请选择接口</strong><p>先在左侧模块和接口列表中选择定义。</p></div>
        <nav v-if="caseForm" class="tabbar request-tabs"><button v-for="tab in caseTabs" :key="tab" type="button" :data-tab="tab" :class="{ active: caseTab === tab }" @click="caseTab = tab">{{ tab }}</button></nav>
        <form v-if="caseForm" class="request-editor case-editor" data-form="case" @submit.prevent="saveCase">
          <label class="field-label">用例名称<input v-model="caseForm.name" name="case-name" required /></label>
          <label v-if="caseTab === '请求覆盖'" class="field-label">请求覆盖（Path / Query / Header / Body）<textarea v-model="caseSpecText" name="case-spec" rows="10" spellcheck="false" /></label>
          <label v-else-if="caseTab === '用例变量'" class="field-label">用例变量 JSON<textarea v-model="variablesText" name="case-variables" rows="10" spellcheck="false" /></label>
          <div v-else-if="caseTab === '数据行'" class="data-row-editor">
            <div class="rule-toolbar"><div><strong>数据驱动</strong><small>每行都是独立运行上下文；值仅作为当前行变量参与执行。</small></div><div class="data-row-actions"><button type="button" class="secondary-button" data-action="add-data-row" @click="addEmptyDataRow">＋ 新增行</button><button type="button" class="secondary-button" data-action="add-data-column" @click="addDataColumn">＋ 新增列</button><label class="secondary-button file-button">导入 CSV<input type="file" accept=".csv,text/csv" data-action="import-data-rows" @change="importDataRows" /></label><button type="button" class="secondary-button" data-action="export-data-rows" @click="exportDataRows">导出 CSV</button></div></div>
            <label class="row-option"><input v-model="continueOnDataRowFailure" type="checkbox" /> 单行失败后继续下一行</label>
            <div v-if="dataRows.length && dataColumns.length" class="data-row-table-wrap"><table class="data-row-table"><thead><tr><th>启用</th><th v-for="column in dataColumns" :key="column">{{ column }}</th><th></th></tr></thead><tbody><tr v-for="(row, rowIndex) in dataRows" :key="row.id"><td><input v-model="row.enabled" type="checkbox" /></td><td v-for="column in dataColumns" :key="column"><input v-model="row.values[column]" :name="`data-row-${rowIndex}-${column}`" /></td><td><button type="button" class="icon-button danger" :data-action="`remove-data-row-${rowIndex}`" @click="removeDataRow(rowIndex)">×</button></td></tr></tbody></table></div>
            <p v-else class="rule-empty">暂无数据行。可以新增列后填写，或直接导入首行为列名的 CSV。</p>
          </div>
          <div v-else-if="caseTab === '提取器'" class="rule-editor">
            <div class="rule-toolbar"><div><strong>响应提取</strong><small>把响应中的值保存为后续步骤可引用的变量</small></div><button type="button" class="secondary-button" data-action="add-extractor" @click="addExtractor">＋ 添加提取器</button></div>
            <p v-if="!extractorRows.length" class="rule-empty">暂无提取器。建议从 JSONPath 开始，例如 <code>$.token</code>。</p>
            <article v-for="(row, index) in extractorRows" :key="`extractor-${index}`" class="rule-card">
              <div class="rule-card-head"><strong>提取器 {{ index + 1 }}</strong><button type="button" class="icon-button danger" :data-action="`remove-extractor-${index}`" @click="removeExtractor(index)">×</button></div>
              <div class="rule-grid three-columns">
                <label>类型<select v-model="row.type" :name="`extractor-type-${index}`"><option v-for="type in extractorTypes" :key="type" :value="type">{{ type }}</option></select></label>
                <label>响应表达式<input v-model="row.expression" :name="`extractor-expression-${index}`" :placeholder="row.type === 'HEADER' || row.type === 'COOKIE' ? 'X-Request-Id' : '$.data.token'" /></label>
                <label>变量名<input v-model="row.variable" :name="`extractor-variable-${index}`" placeholder="accessToken" /></label>
              </div>
              <div class="rule-grid two-columns"><label>未命中默认值<input v-model="row.defaultValue" :name="`extractor-default-${index}`" placeholder="可选，支持变量引用" /></label><label class="check-field"><input v-model="row.failIfMissing" type="checkbox" :name="`extractor-fail-${index}`" /> 未命中时让步骤失败</label></div>
            </article>
          </div>
          <div v-else class="rule-editor">
            <div class="rule-toolbar"><div><strong>响应断言</strong><small>多个断言会全部执行，失败原因分别进入报告</small></div><button type="button" class="secondary-button" data-action="add-assertion" @click="addAssertion">＋ 添加断言</button></div>
            <p v-if="!assertionRows.length" class="rule-empty">暂无断言。至少添加一个状态码或业务字段断言。</p>
            <article v-for="(row, index) in assertionRows" :key="`assertion-${index}`" class="rule-card">
              <div class="rule-card-head"><strong>断言 {{ index + 1 }}</strong><button type="button" class="icon-button danger" :data-action="`remove-assertion-${index}`" @click="removeAssertion(index)">×</button></div>
              <div class="rule-grid three-columns"><label>类型<select v-model="row.type" :name="`assertion-type-${index}`" @change="changeAssertionType(row)"><option v-for="type in assertionTypes" :key="type" :value="type">{{ type }}</option></select></label><label>操作符<select v-model="row.operator" :name="`assertion-operator-${index}`"><option v-for="operator in assertionOperators(row.type)" :key="operator" :value="operator">{{ operator }}</option></select></label><label v-if="assertionNeedsExpression(row.type)">表达式<input v-model="row.expression" :name="`assertion-expression-${index}`" placeholder="$.data.status" required /></label></div>
              <label v-if="assertionNeedsExpected(row)" class="expected-field">预期值（JSON）<input v-model="row.expected" :name="`assertion-expected-${index}`" :placeholder="assertionExpectedPlaceholder(row.type)" /></label>
            </article>
          </div>
        </form>
      </template>

      <section class="response-panel">
        <header><nav class="tabbar"><button class="active">响应试算</button><button disabled>真实发送（Runner）</button></nav><div class="response-metrics"><span>真实发送经 Platform API 进入 Runner，不由浏览器直连目标服务</span></div></header>
        <div v-if="props.mode === 'case' && caseForm" class="trial-panel">
          <div class="trial-toolbar"><div><strong>用响应样本验证提取规则</strong><small>试算与保存的表达式相同；对象和数组不会被强制转成字符串。</small></div><button class="primary-button" data-action="run-extractor-trial" type="button" @click="runTrial">运行试算</button></div>
          <div class="trial-grid">
            <label>响应体（JSON 或文本）<textarea v-model="trialBodyText" data-field="trial-body" spellcheck="false" /></label>
            <div class="trial-side-fields">
              <label>状态码<input v-model.number="trialStatus" type="number" min="100" max="599" /></label>
              <label>耗时（毫秒）<input v-model.number="trialDuration" type="number" min="0" /></label>
              <label>响应头（JSON）<textarea v-model="trialHeadersText" data-field="trial-headers" spellcheck="false" /></label>
              <label>Cookie（JSON）<textarea v-model="trialCookiesText" data-field="trial-cookies" spellcheck="false" /></label>
            </div>
          </div>
          <div v-if="trialResults.length" class="trial-results" data-testid="extractor-trial-results">
            <div class="trial-results-head"><strong>提取结果</strong><small>{{ trialResults.length }} 条规则</small></div>
            <div v-for="result in trialResults" :key="`${result.ruleIndex}-${result.variable}`" class="trial-result-row" :class="{ failed: result.failed }">
              <span class="trial-result-state">{{ result.matched ? '命中' : result.failed ? '失败' : '默认值' }}</span><code>{{ result.variable }}</code><span class="trial-type">{{ result.valueType }}</span><pre>{{ trialValueText(result.value) }}</pre><small>{{ result.message }}</small>
            </div>
          </div>
          <div v-else class="response-placeholder trial-empty"><AppIcon name="code" /><span>填写响应样本并点击“运行试算”，这里会显示每条提取规则的命中结果。</span></div>
        </div>
        <div v-else class="response-placeholder"><AppIcon name="code" /><span>接口定义先保存请求结构；切换到接口用例后可用响应样本试算提取器。</span></div>
      </section>
    </div>

    <aside class="context-inspector">
      <header><span class="section-overline">CONTEXT</span><h3>执行上下文</h3></header>
      <div class="context-block"><span>当前环境</span><strong><i class="env-dot"></i>由顶部环境选择器管理</strong><small>环境变量和密钥仅作为配置引用保存。</small></div>
      <div class="context-block"><span>请求行为</span><dl><div><dt>方法</dt><dd>{{ definitionForm?.method || selectedDefinition?.method || '—' }}</dd></div><div><dt>Body</dt><dd>{{ definitionForm?.requestSpec.body.type || '—' }}</dd></div><div><dt>revision</dt><dd>{{ definitionForm?.revision || selectedDefinition?.revision || '—' }}</dd></div></dl></div>
      <div class="security-note"><AppIcon name="lock" :size="16" /><p><strong>浏览器不直连目标服务</strong><small>保存和发送均只调用 Platform API；Runner 使用 JMeter 执行并回传报告。</small></p></div>
      <button class="link-button" @click="emit('navigate', 'environments')">管理环境配置 <AppIcon name="arrow" :size="14" /></button>
    </aside>
    <p v-if="errorMessage" class="studio-error" role="alert">{{ errorMessage }}</p>
  </section>
</template>

<style scoped>
.asset-empty-state { padding: 12px; color: var(--muted); font-size: 9px; text-align: center; }
.api-definition-form { min-height: 0; display: contents; }
.definition-name-field { margin: 10px 14px 0; }
.endpoint-case-list { border-top: 1px solid var(--line); }
.endpoint-case-list > button { width: calc(100% - 12px); margin: 1px 6px; padding: 7px 8px; display: flex; align-items: center; gap: 7px; color: #697386; background: transparent; font-size: 9px; text-align: left; }
.endpoint-case-list > button:hover, .endpoint-case-list > button.selected { background: #e9edf5; color: var(--ink); }
.endpoint-case-list > button small { margin-left: auto; color: var(--muted); font-size: 7px; }
.body-toolbar select { padding: 3px 5px; border: 1px solid var(--line); border-radius: 4px; color: var(--blue); font-size: 8px; }
.body-toolbar small { color: var(--muted); font-size: 8px; }
.json-editor, .case-editor textarea { width: 100%; min-height: 180px; padding: 14px 17px; border: 0; outline: 0; resize: vertical; color: #5b6578; font-family: var(--mono); font-size: 9px; line-height: 1.75; }
.path-row { grid-template-columns: 28px 1fr 1.4fr 1fr 24px; }
.case-editor { padding: 13px 15px; }
.case-editor .field-label { margin-top: 0; }
.case-editor .field-label + .field-label { margin-top: 14px; }
.rule-editor { padding: 13px 15px; }
.rule-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.rule-toolbar strong { display: block; color: var(--ink); font-size: 11px; }
.rule-toolbar small { display: block; margin-top: 3px; color: var(--muted); font-size: 8px; }
.rule-empty { margin: 0; padding: 24px 12px; border: 1px dashed var(--line); border-radius: 7px; color: var(--muted); font-size: 9px; text-align: center; }
.rule-empty code { color: var(--blue); font-family: var(--mono); }
.rule-card { margin-bottom: 10px; padding: 11px; border: 1px solid var(--line); border-radius: 7px; background: #fbfcfe; }
.rule-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; color: #596579; font-size: 9px; }
.rule-card-head .icon-button { width: 20px; height: 20px; color: var(--muted); }
.rule-card-head .icon-button.danger:hover { color: var(--red); }
.rule-grid { display: grid; gap: 8px; }
.rule-grid.three-columns { grid-template-columns: 0.95fr 1.5fr 1fr; }
.rule-grid.two-columns { grid-template-columns: 1.2fr 1fr; margin-top: 8px; }
.rule-card label, .expected-field { display: flex; flex-direction: column; gap: 4px; color: #738096; font-size: 8px; }
.rule-card input, .rule-card select { min-width: 0; padding: 7px 8px; border: 1px solid #dfe4ed; border-radius: 4px; outline: 0; color: #566174; background: #fff; font-size: 9px; }
.rule-card input:focus, .rule-card select:focus { border-color: var(--blue); box-shadow: 0 0 0 2px rgba(62, 104, 216, 0.08); }
.check-field { flex-direction: row !important; align-items: center; padding-top: 19px; }
.check-field input { width: 13px; height: 13px; }
.expected-field { margin-top: 8px; }
.expected-field input { font-family: var(--mono); }
.data-row-editor { padding: 13px 15px; }
.data-row-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.file-button { position: relative; overflow: hidden; cursor: pointer; }
.file-button input { position: absolute; inset: 0; width: 100%; opacity: 0; cursor: pointer; }
.row-option { display: flex; align-items: center; gap: 6px; margin: 8px 0 10px; color: #738096; font-size: 8px; }
.data-row-table-wrap { overflow: auto; border: 1px solid var(--line); border-radius: 7px; }
.data-row-table { min-width: 100%; border-collapse: collapse; color: #596579; font-size: 8px; }
.data-row-table th { padding: 8px; border-bottom: 1px solid var(--line); color: var(--muted); background: #f5f7fa; font-weight: 600; text-align: left; white-space: nowrap; }
.data-row-table td { padding: 5px 7px; border-bottom: 1px solid #edf0f5; }
.data-row-table tr:last-child td { border-bottom: 0; }
.data-row-table td input:not([type="checkbox"]) { width: 100%; min-width: 100px; padding: 6px; border: 1px solid #dfe4ed; border-radius: 4px; outline: 0; color: #566174; font-size: 8px; }
.trial-panel { padding: 13px 15px; }
.trial-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.trial-toolbar strong { display: block; color: var(--ink); font-size: 11px; }
.trial-toolbar small { display: block; margin-top: 3px; color: var(--muted); font-size: 8px; }
.trial-grid { display: grid; grid-template-columns: 1.25fr 0.75fr; gap: 10px; }
.trial-grid label, .trial-side-fields label { display: flex; flex-direction: column; gap: 4px; color: #738096; font-size: 8px; }
.trial-grid textarea, .trial-side-fields input { width: 100%; min-height: 108px; padding: 8px; border: 1px solid #dfe4ed; border-radius: 4px; outline: 0; resize: vertical; color: #566174; background: #fff; font-family: var(--mono); font-size: 9px; line-height: 1.5; }
.trial-side-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.trial-side-fields input { min-height: 0; font-family: inherit; }
.trial-side-fields label:nth-child(3), .trial-side-fields label:nth-child(4) { grid-column: span 2; }
.trial-side-fields textarea { min-height: 48px; }
.trial-results { margin-top: 12px; border: 1px solid var(--line); border-radius: 7px; overflow: hidden; background: #fbfcfe; }
.trial-results-head { display: flex; justify-content: space-between; padding: 8px 10px; border-bottom: 1px solid var(--line); color: var(--ink); font-size: 9px; }
.trial-results-head small { color: var(--muted); font-size: 8px; }
.trial-result-row { display: grid; grid-template-columns: 48px 1fr 48px minmax(100px, 1.4fr) auto; align-items: center; gap: 8px; padding: 8px 10px; border-bottom: 1px solid #edf0f5; font-size: 8px; }
.trial-result-row:last-child { border-bottom: 0; }
.trial-result-row.failed { background: #fff7f6; }
.trial-result-state { color: #168a5a; }
.trial-result-row.failed .trial-result-state { color: var(--red); }
.trial-result-row code { color: var(--blue); font-family: var(--mono); }
.trial-type { color: #7953b8; font-family: var(--mono); }
.trial-result-row pre { margin: 0; overflow: hidden; color: #566174; font-family: var(--mono); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.trial-result-row small { color: var(--muted); white-space: nowrap; }
.trial-empty { min-height: 128px; }
.studio-error { position: absolute; bottom: 12px; left: 50%; z-index: 2; margin: 0; padding: 7px 12px; transform: translateX(-50%); color: var(--red); background: #fff1f1; border: 1px solid #f4cccc; border-radius: 5px; font-size: 9px; }
</style>

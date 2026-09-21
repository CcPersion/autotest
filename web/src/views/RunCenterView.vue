<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import { apiCaseApi, type ApiCase } from '../api/apiCase'
import { apiDefinitionApi, type ApiDefinition, type JsonObject } from '../api/apiDefinition'
import { environmentApi, type Environment, type EnvironmentParameter } from '../api/environment'
import { runApi, type RunStatus } from '../api/run'

const props = defineProps<{ projectId?: string | null; environmentId?: string | null }>()
const emit = defineEmits<{ navigate: [page: string]; runCreated: [runId: string] }>()

const definitions = shallowRef<ApiDefinition[]>([])
const cases = shallowRef<ApiCase[]>([])
const selectedDefinitionId = ref('')
const selectedCaseId = ref('')
const loading = ref(false)
const running = ref(false)
const errorMessage = ref('')
const statusMessage = ref('')
const lastRun = ref<{ id: string; status: RunStatus } | null>(null)
const previewOpen = ref(false)
const previewLoading = ref(false)
const preview = ref<{ url: string; headers: string[]; cookies: string[]; body: string } | null>(null)

async function loadAssets() {
  definitions.value = []
  cases.value = []
  selectedDefinitionId.value = ''
  selectedCaseId.value = ''
  lastRun.value = null
  errorMessage.value = ''
  if (!props.projectId) return
  loading.value = true
  try {
    definitions.value = (await apiDefinitionApi.list(props.projectId)).filter((item) => !item.archived)
    selectedDefinitionId.value = definitions.value[0]?.id || ''
    await loadCases()
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || '接口资产加载失败'
  } finally {
    loading.value = false
  }
}

async function loadCases() {
  cases.value = []
  selectedCaseId.value = ''
  if (!props.projectId || !selectedDefinitionId.value) return
  try {
    cases.value = (await apiCaseApi.list(props.projectId, selectedDefinitionId.value)).filter((item) => !item.archived)
    selectedCaseId.value = cases.value[0]?.id || ''
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || '接口用例加载失败'
  }
}

function selectedDefinition() {
  return definitions.value.find((item) => item.id === selectedDefinitionId.value)
}

function selectedCase() {
  return cases.value.find((item) => item.id === selectedCaseId.value)
}

function masked(name: string, value: string): string {
  const normalized = name.toLowerCase().replace(/[_-]/g, '')
  return normalized.includes('authorization') || normalized.includes('cookie')
    || normalized.includes('token') || normalized.includes('password')
    || normalized.includes('apikey') || value.includes('${secret:')
    ? '••••••••'
    : value
}

function maskedBody(value: unknown, fieldName = ''): unknown {
  if (typeof value === 'string') {
    return fieldName ? masked(fieldName, value) : value.replace(/\$\{secret:[^}]+}/g, '••••••••')
  }
  if (Array.isArray(value)) return value.map((item) => maskedBody(item, fieldName))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .map(([key, item]) => [key, maskedBody(item, key)]))
  }
  return value
}

function selectedValue(definition: ApiDefinition, item: ApiCase, kind: 'pathParams' | 'query' | 'headers' | 'cookies', name: string) {
  const override = item.caseSpec[kind === 'pathParams' ? 'pathParams' : kind]?.[name]
  const declared = definition.requestSpec[kind === 'pathParams' ? 'pathParams' : kind]
    ?.find((parameter) => parameter.name === name)?.value
  return String(override ?? declared ?? '')
}

async function togglePreview() {
  if (previewOpen.value) {
    previewOpen.value = false
    return
  }
  const definition = selectedDefinition()
  const item = selectedCase()
  if (!props.projectId || !props.environmentId || !definition || !item) return
  previewLoading.value = true
  try {
    const environment = await environmentApi.get(props.projectId, props.environmentId)
    const path = definition.urlTemplate.replace(/\{([^{}]+)\}/g, (_match, name: string) =>
      encodeURIComponent(selectedValue(definition, item, 'pathParams', name)))
    const base = environment.baseUrl.replace(/\/$/, '')
    const query = (definition.requestSpec.query || [])
      .filter((parameter) => parameter.enabled)
      .map((parameter) => [parameter.name, selectedValue(definition, item, 'query', parameter.name)])
      .filter(([, value]) => value !== '')
      .map(([name, value]) => `${encodeURIComponent(name)}=${encodeURIComponent(masked(name, value))}`)
    const headers = mergedHeaders(environment, definition, item)
      .filter((parameter) => parameter.enabled)
      .map((parameter) => `${parameter.name}: ${masked(parameter.name, parameter.value)}`)
    const cookies = (definition.requestSpec.cookies || [])
      .filter((parameter) => parameter.enabled)
      .map((parameter) => `${parameter.name}=${masked(parameter.name, selectedValue(definition, item, 'cookies', parameter.name))}`)
    const body = item.caseSpec.body || definition.requestSpec.body
    const bodyText = body.type === 'NONE'
      ? '(无请求体)'
      : body.type === 'MULTIPART'
        ? JSON.stringify({ ...maskedBody(body.value) as Record<string, unknown>, files: Array.isArray((body.value as Record<string, unknown>)?.files) ? '[文件已隐藏]' : undefined }, null, 2)
        : body.type === 'URLENCODED'
          ? JSON.stringify(maskedBody(body.value), null, 2)
          : typeof body.value === 'string' ? maskedBody(body.value) as string : JSON.stringify(maskedBody(body.value), null, 2)
    preview.value = {
      url: `${base}${path}${query.length ? `?${query.join('&')}` : ''}`,
      headers,
      cookies,
      body: bodyText,
    }
    previewOpen.value = true
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || '请求预览生成失败'
  } finally {
    previewLoading.value = false
  }
}

function mergedParameters(definition: ApiDefinition, item: ApiCase, kind: 'query' | 'headers' | 'cookies') {
  const values = item.caseSpec[kind] || {}
  const declared = definition.requestSpec[kind] || []
  return declared.map((parameter) => ({
    name: parameter.name,
    value: values[parameter.name] ?? parameter.value,
    enabled: parameter.enabled,
  }))
}

function mergedHeaders(environment: Environment, definition: ApiDefinition, item: ApiCase) {
  const defaults = environment.requestOptions?.defaultHeaders ?? []
  const declared = definition.requestSpec.headers || []
  const byName = new Map<string, EnvironmentParameter>()
  for (const parameter of defaults) byName.set(parameter.name.toLowerCase(), parameter)
  for (const parameter of declared) byName.set(parameter.name.toLowerCase(), parameter)
  const overrides = item.caseSpec.headers || {}
  return [...byName.values()].map((parameter) => {
    const overrideName = Object.keys(overrides).find((name) => name.toLowerCase() === parameter.name.toLowerCase())
    return {
      name: parameter.name,
      value: overrideName ? overrides[overrideName] : parameter.value,
      enabled: parameter.enabled,
    }
  })
}

function mergedRequestOptions(environment: Environment, definition: ApiDefinition) {
  const defaults = environment.requestOptions ?? {}
  const definitionOptions = definition.requestSpec.options ?? {}
  return {
    followRedirects: definitionOptions.followRedirects ?? defaults.followRedirects ?? true,
    connectTimeoutMillis: definitionOptions.connectTimeoutMillis ?? defaults.connectTimeoutMillis ?? 0,
    responseTimeoutMillis: definitionOptions.responseTimeoutMillis ?? defaults.responseTimeoutMillis ?? 0,
    proxy: definitionOptions.proxy ?? defaults.proxy ?? null,
    clientCertificate: definitionOptions.clientCertificate ?? defaults.clientCertificate ?? null,
  }
}

function resolvedPath(definition: ApiDefinition, item: ApiCase): string {
  return definition.urlTemplate.replace(/\{([^{}]+)\}/g, (_match, name: string) =>
    encodeURIComponent(selectedValue(definition, item, 'pathParams', name)))
}

async function createRun() {
  if (running.value || !props.projectId || !props.environmentId) return
  const definition = selectedDefinition()
  const item = selectedCase()
  if (!definition || !item) {
    errorMessage.value = '请先选择一个已保存的接口用例'
    return
  }
  running.value = true
  errorMessage.value = ''
  statusMessage.value = '正在创建运行并等待 Runner…'
  try {
    const environment = await environmentApi.get(props.projectId, props.environmentId)
    const plan = {
      jmeterVersion: '5.6.3',
      projectId: props.projectId,
      planId: `case-${item.id}`,
      stepId: `case-step-${item.id}`,
      baseUrl: environment.baseUrl,
      method: definition.method,
      urlTemplate: resolvedPath(definition, item),
      query: mergedParameters(definition, item, 'query'),
      headers: mergedHeaders(environment, definition, item),
      cookies: mergedParameters(definition, item, 'cookies'),
      body: item.caseSpec.body || definition.requestSpec.body,
      ...mergedRequestOptions(environment, definition),
      variables: item.variables,
      variableScopes: {
        environment: environment.variables,
        scenario: {},
        caseVariables: item.variables,
        dataRow: {},
        extracted: {},
      },
      extractors: item.caseSpec.extractors ?? [],
      assertions: item.assertions,
      dataRows: item.caseSpec.dataRows ?? [],
      dataRowOptions: item.caseSpec.dataRowOptions ?? { continueOnFailure: true },
    }
    const run = await runApi.create(props.projectId, {
      environmentId: props.environmentId,
      targetType: 'API_CASE',
      targetId: item.id,
      executionPlan: plan as unknown as JsonObject,
      idempotencyKey: `web-${crypto.randomUUID()}`,
    })
    lastRun.value = { id: run.id, status: run.status }
    emit('runCreated', run.id)
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 500))
      const latest = await runApi.get(props.projectId, run.id)
      lastRun.value = { id: latest.id, status: latest.status }
      if (['PASSED', 'FAILED', 'CANCELED', 'INTERRUPTED'].includes(latest.status)) break
    }
    statusMessage.value = `运行 ${lastRun.value?.status === 'PASSED' ? '通过' : '结束'}，可查看报告证据。`
    emit('navigate', 'report')
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || '运行创建失败'
    statusMessage.value = ''
  } finally {
    running.value = false
  }
}

watch(() => [props.projectId, props.environmentId], () => { void loadAssets() }, { immediate: true })
watch(selectedDefinitionId, () => { void loadCases() })
</script>

<template>
  <section class="runs-page panel-surface" data-testid="run-center">
    <div class="run-overview">
      <div><span class="section-overline">RUN CENTER</span><h2>真实运行中心</h2><p>选择已保存的接口用例，由 Runner 使用 JMeter 5.6.3 执行并生成原生报告。</p></div>
      <div class="metric-line"><div><span>当前环境</span><strong>{{ props.environmentId ? '已选择' : '未选择' }}</strong></div><div><span>接口定义</span><strong>{{ definitions.length }}</strong></div><div><span>接口用例</span><strong>{{ cases.length }}</strong></div></div>
    </div>
    <div class="run-create-panel">
      <label>接口定义<select v-model="selectedDefinitionId" :disabled="loading || running"><option value="">请选择接口</option><option v-for="item in definitions" :key="item.id" :value="item.id">{{ item.method }} · {{ item.name }}</option></select></label>
      <label>接口用例<select v-model="selectedCaseId" :disabled="loading || running || !selectedDefinitionId"><option value="">请选择用例</option><option v-for="item in cases" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <button class="secondary-button" data-action="preview-request" :disabled="running || previewLoading || !selectedCaseId" @click="togglePreview"><AppIcon name="eye" :size="14" />{{ previewLoading ? '生成预览…' : previewOpen ? '收起预览' : '请求预览' }}</button>
      <button class="primary-button" data-action="run-case" :disabled="running || !props.projectId || !props.environmentId || !selectedCaseId" @click="createRun"><AppIcon name="play" :size="14" />{{ running ? '执行中…' : '运行用例' }}</button>
      <span v-if="statusMessage" class="run-status-message" data-testid="run-status">{{ statusMessage }}</span>
    </div>
    <div v-if="previewOpen && preview" class="request-preview" data-testid="request-preview">
      <div class="preview-heading"><div><span class="section-overline">FINAL REQUEST</span><strong>{{ selectedDefinition()?.method }} {{ preview.url }}</strong></div><span>敏感值已脱敏</span></div>
      <div class="preview-grid"><div><small>Headers</small><pre>{{ preview.headers.length ? preview.headers.join('\n') : '(无)' }}</pre></div><div><small>Cookies</small><pre>{{ preview.cookies.length ? preview.cookies.join('\n') : '(无)' }}</pre></div><div class="preview-body"><small>Body</small><pre>{{ preview.body }}</pre></div></div>
    </div>
    <p v-if="errorMessage" class="studio-error" role="alert">{{ errorMessage }}</p>
    <div v-if="lastRun" class="run-result-card"><span class="run-status-icon" :class="lastRun.status.toLowerCase()"><AppIcon :name="lastRun.status === 'PASSED' ? 'check' : 'runs'" :size="14" /></span><div><strong>运行 {{ lastRun.status }}</strong><code>{{ lastRun.id }}</code></div><button class="secondary-button" @click="emit('navigate', 'report')">查看报告</button></div>
    <div v-if="!props.projectId" class="run-empty">请先在顶部创建或选择项目。</div>
    <div v-else-if="!props.environmentId" class="run-empty">请先在环境配置中创建活动环境。</div>
    <div v-else-if="!definitions.length && !loading" class="run-empty">暂无可运行接口，请先在接口用例页面保存定义和用例。</div>
    <div v-else class="run-hint"><AppIcon name="lock" :size="14" />浏览器只提交执行计划到 Platform API，不直接访问目标服务；请求、响应和断言证据由 Runner 回传。</div>
  </section>
</template>

<style scoped>
.run-create-panel { display: flex; align-items: end; gap: 12px; padding: 18px 22px; border-bottom: 1px solid var(--line); background: #fafbfe; }
.run-create-panel label { display: grid; gap: 5px; min-width: 220px; color: var(--muted); font-size: 10px; }
.run-create-panel select { min-width: 220px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink); background: white; }
.run-status-message { color: var(--blue); font-size: 10px; }
.request-preview { margin: 0 22px 18px; padding: 16px; border: 1px solid #d8e2f4; border-radius: 8px; background: #fbfcff; }
.preview-heading { display: flex; align-items: end; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.preview-heading div { display: grid; gap: 5px; min-width: 0; }
.preview-heading strong { overflow-wrap: anywhere; color: var(--ink); font-size: 11px; font-weight: 600; }
.preview-heading > span { color: #2b9b68; font-size: 10px; white-space: nowrap; }
.preview-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.preview-grid > div { min-width: 0; }
.preview-grid small { color: var(--muted); font-size: 10px; }
.preview-grid pre { min-height: 42px; margin: 6px 0 0; padding: 10px; overflow: auto; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: #36445e; font: 10px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.preview-body { grid-column: 1 / -1; }
.run-result-card { display: flex; align-items: center; gap: 10px; margin: 20px 22px 0; padding: 12px 14px; border: 1px solid #d8e2f4; border-radius: 8px; background: #f6f9ff; }
.run-result-card div { display: grid; gap: 3px; flex: 1; }
.run-result-card code { color: var(--muted); font-size: 9px; }
.run-status-icon { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 50%; color: #1f9d67; background: #dff6ea; }
.run-status-icon.failed, .run-status-icon.canceled, .run-status-icon.interrupted { color: #c44848; background: #fde5e5; }
.run-empty { margin: 28px 22px; padding: 28px; text-align: center; color: var(--muted); border: 1px dashed var(--line); border-radius: 8px; }
.run-hint { display: flex; align-items: center; gap: 7px; margin: 20px 22px; color: var(--muted); font-size: 10px; }
.studio-error { margin: 12px 22px; }
</style>

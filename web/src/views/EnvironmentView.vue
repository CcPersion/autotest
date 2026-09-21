<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import AppIcon from '../components/AppIcon.vue'
import { environmentApi as defaultEnvironmentApi, type Environment, type EnvironmentApi, type JsonObject, type EnvironmentRequestOptions } from '../api/environment'
import { secretApi as defaultSecretApi, type SecretApi, type SecretSummary } from '../api/secret'
import { jdbcDataSourceApi as defaultJdbcDataSourceApi, type JdbcDataSource, type JdbcDataSourceApi } from '../api/jdbcDataSource'
import { redisDataSourceApi as defaultRedisDataSourceApi, type RedisDataSource, type RedisDataSourceApi } from '../api/redisDataSource'

const MASK = '••••••••'
const tabs = ['普通变量', '密钥', 'JDBC', 'Redis', '证书', '文件'] as const
type Tab = typeof tabs[number]

const props = withDefaults(defineProps<{
  projectId?: string | null
  environmentApi?: EnvironmentApi
  secretApi?: SecretApi
  jdbcDataSourceApi?: JdbcDataSourceApi
  redisDataSourceApi?: RedisDataSourceApi
}>(), { projectId: null })

const emit = defineEmits<{ changed: [] }>()

const environmentClient = computed(() => props.environmentApi ?? defaultEnvironmentApi)
const secretClient = computed(() => props.secretApi ?? defaultSecretApi)
const jdbcClient = computed(() => props.jdbcDataSourceApi ?? defaultJdbcDataSourceApi)
const redisClient = computed(() => props.redisDataSourceApi ?? defaultRedisDataSourceApi)
const tab = ref<Tab>('普通变量')
const environments = ref<Environment[]>([])
const secrets = ref<SecretSummary[]>([])
const showArchived = ref(false)
const selectedEnvironmentId = ref<string | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const environmentFormOpen = ref(false)
const editingEnvironmentId = ref<string | null>(null)
const environmentName = ref('')
const environmentBaseUrl = ref('')
const environmentVariables = ref('{}')
const environmentRequestOptions = ref('{}')
const environmentCertificateEnabled = ref(false)
const environmentCertificateSecretRef = ref('')
const environmentCertificatePasswordRef = ref('')
const secretReference = ref('')
const secretFormMode = ref<'create' | 'replace' | null>(null)
const selectedSecretId = ref<string | null>(null)
const secretName = ref('')
const secretValue = ref('')
const jdbcSources = ref<JdbcDataSource[]>([])
const jdbcFormOpen = ref(false)
const jdbcEditingId = ref<string | null>(null)
const jdbcName = ref('')
const jdbcType = ref<'POSTGRESQL' | 'MYSQL'>('POSTGRESQL')
const jdbcHost = ref('')
const jdbcPort = ref(5432)
const jdbcDatabase = ref('')
const jdbcUsername = ref('')
const jdbcSecretRef = ref('')
const jdbcTestingId = ref<string | null>(null)
const redisSources = ref<RedisDataSource[]>([])
const redisFormOpen = ref(false)
const redisEditingId = ref<string | null>(null)
const redisName = ref('')
const redisHost = ref('')
const redisPort = ref(6379)
const redisDatabaseNumber = ref(0)
const redisUsername = ref('')
const redisSecretRef = ref('')
const redisTls = ref(false)
const redisTestingId = ref<string | null>(null)
const saving = ref(false)
let dataGeneration = 0

const selectedEnvironment = computed(() => environments.value.find((item) => item.id === selectedEnvironmentId.value) ?? null)
const selectedSecret = computed(() => secrets.value.find((item) => item.id === selectedSecretId.value) ?? null)

function messageFor(error: unknown): string {
  const value = error as { status?: number; code?: string; message?: string; details?: { path?: string; name?: string } }
  if (value.code === 'NAME_CONFLICT') return '名称已存在'
  if (value.code === 'REVISION_CONFLICT' || value.status === 409) return '版本已变化，请刷新后重试'
  if (value.code === 'SECRET_REFERENCE_NOT_FOUND') {
    const field = value.details?.path || value.details?.name
    return field ? `密钥引用不存在（字段：${field}）` : '密钥引用不存在，请检查变量字段'
  }
  return value.message || '操作失败'
}

function setSelectedEnvironment(items: Environment[]) {
  const candidate = items.find((item) => item.id === selectedEnvironmentId.value && (!item.archived || showArchived.value))
  if (candidate) return
  selectedEnvironmentId.value = items.find((item) => !item.archived)?.id ?? items[0]?.id ?? null
}

function clearProjectState() {
  environments.value = []
  secrets.value = []
  selectedEnvironmentId.value = null
  errorMessage.value = ''
  loading.value = false
  saving.value = false
  closeEnvironmentForm()
  closeSecretForm()
  jdbcSources.value = []
  closeJdbcForm()
  redisSources.value = []
  closeRedisForm()
}

function operationIsCurrent(projectId: string, generation: number): boolean {
  return props.projectId === projectId && dataGeneration === generation
}

async function loadEnvironments(generation: number) {
  if (!props.projectId) {
    return
  }
  loading.value = true
  try {
    const items = await environmentClient.value.list(props.projectId, showArchived.value)
    if (generation !== dataGeneration) return
    environments.value = items
    setSelectedEnvironment(items)
  } catch (error) {
    if (generation === dataGeneration) errorMessage.value = messageFor(error)
  } finally {
    if (generation === dataGeneration) loading.value = false
  }
}

async function loadSecrets(generation: number) {
  if (!props.projectId) {
    return
  }
  try {
    const items = await secretClient.value.list(props.projectId, false)
    if (generation === dataGeneration) secrets.value = items
  } catch (error) {
    if (generation === dataGeneration) errorMessage.value = messageFor(error)
  }
}

async function loadJdbcSources(generation: number, environmentId = selectedEnvironmentId.value) {
  if (!props.projectId || !environmentId) { jdbcSources.value = []; return }
  try {
    const items = await jdbcClient.value.list(props.projectId, environmentId, showArchived.value)
    if (operationIsCurrent(props.projectId, generation) && selectedEnvironmentId.value === environmentId) jdbcSources.value = items
  } catch (error) {
    if (operationIsCurrent(props.projectId, generation)) errorMessage.value = messageFor(error)
  }
}

async function loadRedisSources(generation: number, environmentId = selectedEnvironmentId.value) {
  if (!props.projectId || !environmentId) { redisSources.value = []; return }
  try {
    const items = await redisClient.value.list(props.projectId, environmentId, showArchived.value)
    if (operationIsCurrent(props.projectId, generation) && selectedEnvironmentId.value === environmentId) redisSources.value = items
  } catch (error) {
    if (operationIsCurrent(props.projectId, generation)) errorMessage.value = messageFor(error)
  }
}

async function loadData() {
  const generation = ++dataGeneration
  clearProjectState()
  if (!props.projectId) {
    return
  }
  await Promise.all([loadEnvironments(generation), loadSecrets(generation)])
  await Promise.all([loadJdbcSources(generation), loadRedisSources(generation)])
}

function startCreateJdbc() {
  jdbcEditingId.value = null; jdbcName.value = ''; jdbcType.value = 'POSTGRESQL'; jdbcHost.value = ''; jdbcPort.value = 5432; jdbcDatabase.value = ''; jdbcUsername.value = ''; jdbcSecretRef.value = ''; jdbcFormOpen.value = true
}

function startEditJdbc(source: JdbcDataSource) {
  jdbcEditingId.value = source.id; jdbcName.value = source.name; jdbcType.value = source.databaseType; jdbcHost.value = source.host; jdbcPort.value = source.port; jdbcDatabase.value = source.databaseName; jdbcUsername.value = source.username; jdbcSecretRef.value = source.secretRef; jdbcFormOpen.value = true
}

function closeJdbcForm() { jdbcFormOpen.value = false; jdbcEditingId.value = null; jdbcName.value = ''; jdbcHost.value = ''; jdbcDatabase.value = ''; jdbcUsername.value = ''; jdbcSecretRef.value = '' }

async function submitJdbc() {
  if (!props.projectId || !selectedEnvironmentId.value || !jdbcName.value.trim() || !jdbcSecretRef.value || saving.value) return
  const projectId = props.projectId; const environmentId = selectedEnvironmentId.value; const generation = dataGeneration
  saving.value = true; errorMessage.value = ''
  try {
    const current = jdbcSources.value.find((item) => item.id === jdbcEditingId.value)
    const input = { name: jdbcName.value.trim(), databaseType: jdbcType.value, host: jdbcHost.value.trim(), port: Number(jdbcPort.value), databaseName: jdbcDatabase.value.trim(), username: jdbcUsername.value.trim(), secretRef: jdbcSecretRef.value.trim(), options: {} as Record<string, unknown>, ...(current ? { revision: current.revision } : {}) }
    const saved = jdbcEditingId.value ? await jdbcClient.value.update(projectId, environmentId, jdbcEditingId.value, input) : await jdbcClient.value.create(projectId, environmentId, input)
    if (!operationIsCurrent(projectId, generation)) return
    jdbcSources.value = jdbcEditingId.value ? jdbcSources.value.map((item) => item.id === saved.id ? saved : item) : [saved, ...jdbcSources.value]
    closeJdbcForm()
  } catch (error) { if (operationIsCurrent(projectId, generation)) errorMessage.value = messageFor(error) } finally { if (operationIsCurrent(projectId, generation)) saving.value = false }
}

async function testJdbc(source: JdbcDataSource) {
  if (!props.projectId || !selectedEnvironmentId.value) return
  jdbcTestingId.value = source.id
  try { const result = await jdbcClient.value.testConnection(props.projectId, selectedEnvironmentId.value, source.id); errorMessage.value = result.message } catch (error) { errorMessage.value = messageFor(error) } finally { jdbcTestingId.value = null }
}

function startCreateRedis() {
  redisEditingId.value = null; redisName.value = ''; redisHost.value = ''; redisPort.value = 6379; redisDatabaseNumber.value = 0; redisUsername.value = ''; redisSecretRef.value = ''; redisTls.value = false; redisFormOpen.value = true
}

function startEditRedis(source: RedisDataSource) {
  redisEditingId.value = source.id; redisName.value = source.name; redisHost.value = source.host; redisPort.value = source.port; redisDatabaseNumber.value = source.databaseNumber; redisUsername.value = source.username ?? ''; redisSecretRef.value = source.secretRef ?? ''; redisTls.value = Boolean(source.options?.tls); redisFormOpen.value = true
}

function closeRedisForm() { redisFormOpen.value = false; redisEditingId.value = null; redisName.value = ''; redisHost.value = ''; redisPort.value = 6379; redisDatabaseNumber.value = 0; redisUsername.value = ''; redisSecretRef.value = ''; redisTls.value = false }

async function submitRedis() {
  if (!props.projectId || !selectedEnvironmentId.value || !redisName.value.trim() || saving.value) return
  const projectId = props.projectId; const environmentId = selectedEnvironmentId.value; const generation = dataGeneration
  saving.value = true; errorMessage.value = ''
  try {
    const current = redisSources.value.find((item) => item.id === redisEditingId.value)
    const input = { name: redisName.value.trim(), host: redisHost.value.trim(), port: Number(redisPort.value), databaseNumber: Number(redisDatabaseNumber.value), username: redisUsername.value.trim() || undefined, secretRef: redisSecretRef.value.trim() || undefined, options: { tls: redisTls.value }, ...(current ? { revision: current.revision } : {}) }
    const saved = redisEditingId.value ? await redisClient.value.update(projectId, environmentId, redisEditingId.value, input) : await redisClient.value.create(projectId, environmentId, input)
    if (!operationIsCurrent(projectId, generation)) return
    redisSources.value = redisEditingId.value ? redisSources.value.map((item) => item.id === saved.id ? saved : item) : [saved, ...redisSources.value]
    closeRedisForm()
  } catch (error) { if (operationIsCurrent(projectId, generation)) errorMessage.value = messageFor(error) } finally { if (operationIsCurrent(projectId, generation)) saving.value = false }
}

async function testRedis(source: RedisDataSource) {
  if (!props.projectId || !selectedEnvironmentId.value) return
  redisTestingId.value = source.id
  try { const result = await redisClient.value.testConnection(props.projectId, selectedEnvironmentId.value, source.id); errorMessage.value = result.message } catch (error) { errorMessage.value = messageFor(error) } finally { redisTestingId.value = null }
}

function parseVariables(): JsonObject | undefined {
  try {
    const value: unknown = JSON.parse(environmentVariables.value)
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('not object')
    return value as JsonObject
  } catch {
    errorMessage.value = '变量必须是 JSON object'
    return undefined
  }
}

function parseRequestOptions(): EnvironmentRequestOptions | undefined {
  try {
    const value: unknown = JSON.parse(environmentRequestOptions.value)
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('not object')
    const options = { ...(value as EnvironmentRequestOptions) }
    if (environmentCertificateEnabled.value) {
      if (!environmentCertificateSecretRef.value || !environmentCertificatePasswordRef.value) {
        errorMessage.value = '请同时选择客户端证书和证书密码密钥'
        return undefined
      }
      options.clientCertificate = {
        type: 'PKCS12',
        secretRef: `\${secret:${environmentCertificateSecretRef.value}}`,
        passwordRef: `\${secret:${environmentCertificatePasswordRef.value}}`,
      }
    } else {
      delete options.clientCertificate
    }
    return options
  } catch {
    errorMessage.value = '默认请求配置必须是 JSON object'
    return undefined
  }
}

function secretReferenceName(reference: string | undefined): string {
  const match = reference?.match(/^\$\{secret:([^}]+)}$/)
  return match?.[1] ?? ''
}

function startCreateEnvironment() {
  editingEnvironmentId.value = null
  environmentName.value = ''
  environmentBaseUrl.value = ''
  environmentVariables.value = '{}'
  environmentRequestOptions.value = '{}'
  environmentCertificateEnabled.value = false
  environmentCertificateSecretRef.value = ''
  environmentCertificatePasswordRef.value = ''
  secretReference.value = ''
  errorMessage.value = ''
  environmentFormOpen.value = true
}

function startEditEnvironment(environment: Environment = selectedEnvironment.value!) {
  if (!environment) return
  editingEnvironmentId.value = environment.id
  environmentName.value = environment.name
  environmentBaseUrl.value = environment.baseUrl
  environmentVariables.value = JSON.stringify(environment.variables, null, 2)
  environmentRequestOptions.value = JSON.stringify(environment.requestOptions ?? {}, null, 2)
  const certificate = environment.requestOptions?.clientCertificate
  environmentCertificateEnabled.value = Boolean(certificate)
  environmentCertificateSecretRef.value = secretReferenceName(certificate?.secretRef)
  environmentCertificatePasswordRef.value = secretReferenceName(certificate?.passwordRef)
  secretReference.value = ''
  errorMessage.value = ''
  environmentFormOpen.value = true
}

function closeEnvironmentForm() {
  environmentFormOpen.value = false
  editingEnvironmentId.value = null
  environmentName.value = ''
  environmentBaseUrl.value = ''
  environmentVariables.value = '{}'
  environmentRequestOptions.value = '{}'
  environmentCertificateEnabled.value = false
  environmentCertificateSecretRef.value = ''
  environmentCertificatePasswordRef.value = ''
  secretReference.value = ''
}

function addSecretReference() {
  if (!secretReference.value) return
  const variables = parseVariables() ?? {}
  variables.secret = `\${secret:${secretReference.value}}`
  environmentVariables.value = JSON.stringify(variables, null, 2)
}

async function submitEnvironment() {
  if (!props.projectId || saving.value) return
  const variables = parseVariables()
  if (!variables) return
  const requestOptions = parseRequestOptions()
  if (!requestOptions) return
  const projectId = props.projectId
  const generation = dataGeneration
  const editingId = editingEnvironmentId.value
  const current = editingId ? selectedEnvironment.value : null
  if (editingId && !current) return
  const input = {
    name: environmentName.value.trim(),
    baseUrl: environmentBaseUrl.value.trim(),
    variables,
    ...(Object.keys(requestOptions).length ? { requestOptions } : {}),
  }
  saving.value = true
  errorMessage.value = ''
  try {
    if (editingId) {
      const updated = await environmentClient.value.update(projectId, editingId, { ...input, revision: current!.revision })
      if (!operationIsCurrent(projectId, generation)) return
      environments.value = environments.value.map((item) => item.id === updated.id ? updated : item)
      selectedEnvironmentId.value = updated.id
    } else {
      const created = await environmentClient.value.create(projectId, input)
      if (!operationIsCurrent(projectId, generation)) return
      environments.value = [created, ...environments.value]
      selectedEnvironmentId.value = created.id
    }
    closeEnvironmentForm()
    emit('changed')
  } catch (error) {
    if (operationIsCurrent(projectId, generation)) errorMessage.value = messageFor(error)
  } finally {
    if (operationIsCurrent(projectId, generation)) saving.value = false
  }
}

async function archiveEnvironment(environment: Environment = selectedEnvironment.value!) {
  if (!props.projectId || !environment || !globalThis.confirm(`归档环境“${environment.name}”？`)) return
  const projectId = props.projectId
  const generation = dataGeneration
  try {
    const archived = await environmentClient.value.archive(projectId, environment.id, environment.revision)
    if (!operationIsCurrent(projectId, generation)) return
    environments.value = showArchived.value
      ? environments.value.map((item) => item.id === archived.id ? archived : item)
      : environments.value.filter((item) => item.id !== archived.id)
    setSelectedEnvironment(environments.value)
    emit('changed')
  } catch (error) {
    if (operationIsCurrent(projectId, generation)) errorMessage.value = messageFor(error)
  }
}

async function restoreEnvironment(environment: Environment) {
  if (!props.projectId) return
  const projectId = props.projectId
  const generation = dataGeneration
  try {
    const restored = await environmentClient.value.restore(projectId, environment.id, environment.revision)
    if (!operationIsCurrent(projectId, generation)) return
    environments.value = showArchived.value
      ? environments.value.map((item) => item.id === restored.id ? restored : item)
      : [restored, ...environments.value]
    selectedEnvironmentId.value = restored.id
    emit('changed')
  } catch (error) {
    if (operationIsCurrent(projectId, generation)) errorMessage.value = messageFor(error)
  }
}

async function toggleArchivedEnvironments() {
  showArchived.value = !showArchived.value
  await loadEnvironments(dataGeneration)
}

function startCreateSecret() {
  secretFormMode.value = 'create'
  selectedSecretId.value = null
  secretName.value = ''
  secretValue.value = ''
  errorMessage.value = ''
}

function startReplaceSecret(secret: SecretSummary) {
  secretFormMode.value = 'replace'
  selectedSecretId.value = secret.id
  secretName.value = secret.name
  secretValue.value = ''
  errorMessage.value = ''
}

function closeSecretForm() {
  secretFormMode.value = null
  selectedSecretId.value = null
  secretName.value = ''
  secretValue.value = ''
}

async function submitSecret() {
  if (!props.projectId || !secretValue.value || saving.value) return
  const projectId = props.projectId
  const generation = dataGeneration
  const mode = secretFormMode.value
  const current = selectedSecret.value
  const value = secretValue.value
  const name = secretName.value.trim()
  saving.value = true
  errorMessage.value = ''
  try {
    if (mode === 'replace' && current) {
      const updated = await secretClient.value.replace(projectId, current.id, {
        value,
        revision: current.revision,
      })
      if (!operationIsCurrent(projectId, generation)) return
      secrets.value = secrets.value.map((item) => item.id === updated.id ? { ...updated, mask: MASK } : item)
    } else if (mode === 'create') {
      const created = await secretClient.value.create(projectId, { name, value })
      if (!operationIsCurrent(projectId, generation)) return
      secrets.value = [{ ...created, mask: MASK }, ...secrets.value]
    }
    closeSecretForm()
  } catch (error) {
    if (operationIsCurrent(projectId, generation)) errorMessage.value = messageFor(error)
  } finally {
    if (operationIsCurrent(projectId, generation)) {
      secretValue.value = ''
      saving.value = false
    }
  }
}

async function archiveSecret(secret: SecretSummary) {
  if (!props.projectId || !globalThis.confirm(`归档密钥“${secret.name}”？`)) return
  const projectId = props.projectId
  const generation = dataGeneration
  try {
    await secretClient.value.archive(projectId, secret.id, secret.revision)
    if (!operationIsCurrent(projectId, generation)) return
    secrets.value = secrets.value.filter((item) => item.id !== secret.id)
  } catch (error) {
    if (operationIsCurrent(projectId, generation)) errorMessage.value = messageFor(error)
  }
}

watch(() => props.projectId, () => {
  showArchived.value = false
  void loadData()
})

watch(selectedEnvironmentId, () => { if (props.projectId) void loadJdbcSources(dataGeneration) })

onMounted(() => void loadData())
</script>

<template>
  <section class="environment-layout panel-surface">
    <template v-if="!props.projectId">
      <div class="environment-empty-state" data-testid="environment-empty-state">
        <AppIcon name="env" :size="28" />
        <h2>请先选择或创建项目</h2>
        <p>环境、变量和密钥都属于当前项目。</p>
      </div>
    </template>
    <template v-else>
      <header class="content-header compact-header">
        <div>
          <span class="environment-chip"><i></i>{{ selectedEnvironment?.name || '暂无环境' }}</span>
          <h2>测试环境配置</h2>
          <p>运行前由平台解析，敏感信息仅在 Runner 内临时注入。</p>
        </div>
        <div>
          <button class="secondary-button" data-action="show-archived-environments" @click="toggleArchivedEnvironments">{{ showArchived ? '查看活动环境' : '查看已归档环境' }}</button>
          <button class="primary-button" data-action="create-environment" @click="startCreateEnvironment">新建环境</button>
        </div>
      </header>
      <div v-if="errorMessage" class="environment-error" role="alert">{{ errorMessage }}</div>
      <div class="environment-body">
        <nav class="vertical-tabs">
          <button v-for="item in tabs" :key="item" :data-tab="item" :class="{ active: tab === item }" @click="tab = item">
            <AppIcon :name="item === '密钥' ? 'lock' : ['JDBC', 'Redis'].includes(item) ? 'database' : item === '文件' ? 'upload' : 'env'" :size="16" />
            {{ item }}<span v-if="item === '密钥'">{{ secrets.length }}</span>
          </button>
        </nav>
        <div class="config-content">
          <template v-if="tab === '普通变量'">
            <div class="config-title">
              <div><h3>普通变量</h3><p>可在请求和场景中通过 ${name} 引用。</p></div>
              <div><button class="secondary-button" data-action="edit-environment" :disabled="!selectedEnvironment" @click="startEditEnvironment()">编辑环境</button><button class="secondary-button" data-action="create-environment" @click="startCreateEnvironment"><AppIcon name="plus" :size="14" />新增环境</button></div>
            </div>
            <div class="environment-list">
              <article v-for="environment in environments" :key="environment.id" class="environment-card" :class="{ selected: environment.id === selectedEnvironmentId }">
                <button class="environment-card-select" @click="selectedEnvironmentId = environment.id"><strong>{{ environment.name }}</strong><small>{{ environment.baseUrl }}</small></button>
                <span v-if="environment.archived" class="project-status">已归档</span>
                <div><button v-if="!environment.archived" class="secondary-button" data-action="edit-environment" @click="startEditEnvironment(environment)">编辑</button><button v-if="!environment.archived" class="secondary-button" data-action="archive-environment" @click="archiveEnvironment(environment)">归档</button><button v-else class="secondary-button" data-action="restore-environment" @click="restoreEnvironment(environment)">恢复</button></div>
              </article>
            </div>
            <p v-if="!environments.length" class="project-empty">{{ showArchived ? '暂无已归档环境' : '暂无活动环境，请先新建环境。' }}</p>
            <form v-if="environmentFormOpen" data-form="environment" class="environment-form" @submit.prevent="submitEnvironment">
              <h3>{{ editingEnvironmentId ? '编辑环境' : '新建环境' }}</h3>
              <label>名称<input v-model="environmentName" name="environment-name" required /></label>
              <label>Base URL<input v-model="environmentBaseUrl" name="environment-base-url" type="url" required /></label>
              <label>Variables JSON<textarea v-model="environmentVariables" name="environment-variables" rows="7" required /></label>
              <label>默认请求配置 JSON<textarea v-model="environmentRequestOptions" name="environment-request-options" rows="7" placeholder="{\n  &quot;defaultHeaders&quot;: [],\n  &quot;proxy&quot;: null\n}" required /></label>
              <small class="environment-form-hint">可配置默认 Header、代理、超时和重定向；接口自身配置优先，敏感值必须使用密钥引用。</small>
              <fieldset class="certificate-fields">
                <legend>PKCS12 客户端证书</legend>
                <label class="checkbox-line"><input v-model="environmentCertificateEnabled" name="environment-client-cert-enabled" type="checkbox" />为该环境启用客户端证书</label>
                <template v-if="environmentCertificateEnabled">
                  <label>证书密钥引用<select v-model="environmentCertificateSecretRef" name="environment-client-cert-secret" required><option value="">选择证书密钥</option><option v-for="secret in secrets" :key="`cert-${secret.id}`" :value="secret.name">{{ secret.name }}</option></select></label>
                  <label>证书密码密钥引用<select v-model="environmentCertificatePasswordRef" name="environment-client-cert-password" required><option value="">选择密码密钥</option><option v-for="secret in secrets" :key="`password-${secret.id}`" :value="secret.name">{{ secret.name }}</option></select></label>
                  <small class="environment-form-hint">只保存密钥引用；证书内容由 Runner 在执行时临时加载，不会回显或写入请求预览。</small>
                </template>
              </fieldset>
              <label>插入密钥引用<select v-model="secretReference" name="secret-reference"><option value="">选择活动密钥</option><option v-for="secret in secrets" :key="secret.id" :value="secret.name">{{ secret.name }}</option></select></label>
              <button type="button" class="secondary-button" data-action="insert-secret-reference" :disabled="!secretReference" @click="addSecretReference">插入引用</button>
              <div><button type="button" class="secondary-button" @click="closeEnvironmentForm">取消</button><button type="submit" class="primary-button" :disabled="saving">保存</button></div>
            </form>
          </template>
          <template v-else-if="tab === '密钥'">
            <div class="config-title"><div><h3>密钥</h3><p>密钥创建后不再提供明文回显。</p></div><button class="secondary-button" data-action="create-secret" @click="startCreateSecret"><AppIcon name="plus" :size="14" />新增密钥</button></div>
            <div class="secret-banner"><AppIcon name="lock" /><div><strong>密钥采用加密存储</strong><small>页面、AI 上下文、日志、JMX 和报告中只会出现掩码或密钥引用。</small></div></div>
            <div class="secret-grid"><div v-for="secret in secrets" :key="secret.id" class="secret-card"><span><AppIcon name="lock" :size="15" /></span><div><code>{{ secret.name }}</code><small>{{ MASK }}</small></div><button class="secondary-button" data-action="replace-secret" @click="startReplaceSecret(secret)">替换</button><button class="secondary-button" data-action="archive-secret" @click="archiveSecret(secret)">归档</button></div></div>
            <p v-if="!secrets.length" class="project-empty">暂无活动密钥。</p>
            <form v-if="secretFormMode" data-form="secret" class="secret-form" @submit.prevent="submitSecret">
              <h3>{{ secretFormMode === 'create' ? '新建密钥' : `替换密钥：${secretName}` }}</h3>
              <label v-if="secretFormMode === 'create'">名称<input v-model="secretName" name="secret-name" required /></label>
              <label>明文值<input v-model="secretValue" name="secret-value" type="password" autocomplete="new-password" required /></label>
              <div><button type="button" class="secondary-button" data-action="close-secret-form" @click="closeSecretForm">取消</button><button type="submit" class="primary-button" :disabled="saving">保存</button></div>
            </form>
          </template>
          <template v-else-if="tab === '证书'">
            <div class="config-title"><div><h3>PKCS12 客户端证书</h3><p>证书内容只以密钥引用保存，执行时由 Runner 临时加载。</p></div><button class="secondary-button" data-action="edit-certificate-environment" :disabled="!selectedEnvironment" @click="startEditEnvironment()">编辑环境证书</button></div>
            <div class="connection-card"><div class="connection-logo"><AppIcon name="lock" :size="24" /></div><div><span class="enabled-pill">{{ selectedEnvironment?.requestOptions?.clientCertificate ? '已配置引用' : '未配置' }}</span><h3>{{ selectedEnvironment?.requestOptions?.clientCertificate ? '已启用 PKCS12 客户端证书' : '当前环境未启用客户端证书' }}</h3><p v-if="selectedEnvironment?.requestOptions?.clientCertificate">证书：{{ selectedEnvironment.requestOptions.clientCertificate.secretRef }}；密码：{{ selectedEnvironment.requestOptions.clientCertificate.passwordRef }}</p><p v-else>点击“编辑环境证书”，从当前项目密钥中选择证书和密码引用。</p></div></div>
          </template>
          <template v-else-if="tab === 'JDBC'">
            <div class="config-title"><div><h3>JDBC 数据源</h3><p>按环境复用 PostgreSQL / MySQL 连接；密码只保存为密钥引用。</p></div><button class="secondary-button" data-action="create-jdbc" :disabled="!selectedEnvironment" @click="startCreateJdbc"><AppIcon name="plus" :size="14" />新增数据源</button></div>
            <div class="secret-banner"><AppIcon name="lock" /><div><strong>安全连接配置</strong><small>连接测试不会回显密码，运行计划只携带密钥引用。</small></div></div>
            <div class="environment-list"><article v-for="source in jdbcSources" :key="source.id" class="environment-card"><button class="environment-card-select" @click="startEditJdbc(source)"><strong>{{ source.name }} · {{ source.databaseType }}</strong><small>{{ source.username }}@{{ source.host }}:{{ source.port }}/{{ source.databaseName }} · {{ source.secretRef }}</small></button><span v-if="source.archived" class="project-status">已归档</span><div><button class="secondary-button" :disabled="jdbcTestingId === source.id" @click="testJdbc(source)">{{ jdbcTestingId === source.id ? '测试中…' : '测试连接' }}</button><button v-if="!source.archived" class="secondary-button" @click="startEditJdbc(source)">编辑</button></div></article></div>
            <p v-if="!jdbcSources.length" class="project-empty">当前环境暂无 JDBC 数据源。</p>
            <form v-if="jdbcFormOpen" class="environment-form" data-form="jdbc" @submit.prevent="submitJdbc"><h3>{{ jdbcEditingId ? '编辑 JDBC 数据源' : '新增 JDBC 数据源' }}</h3><label>名称<input v-model="jdbcName" required /></label><label>数据库类型<select v-model="jdbcType"><option value="POSTGRESQL">PostgreSQL</option><option value="MYSQL">MySQL</option></select></label><div class="jdbc-grid"><label>主机<input v-model="jdbcHost" required /></label><label>端口<input v-model.number="jdbcPort" type="number" min="1" max="65535" required /></label></div><div class="jdbc-grid"><label>数据库<input v-model="jdbcDatabase" required /></label><label>用户名<input v-model="jdbcUsername" required /></label></div><label>密码密钥引用<select v-model="jdbcSecretRef" required><option value="">选择活动密钥</option><option v-for="secret in secrets" :key="`jdbc-${secret.id}`" :value="secret.name">{{ secret.name }}</option></select></label><small class="environment-form-hint">只填写密钥名称，不在平台表单、日志或报告中输入密码明文。</small><div><button type="button" class="secondary-button" @click="closeJdbcForm">取消</button><button type="submit" class="primary-button" :disabled="saving">保存数据源</button></div></form>
          </template>
          <template v-else-if="tab === 'Redis'">
            <div class="config-title"><div><h3>Redis 数据源</h3><p>按环境复用 Redis 连接；命令仅允许 GET、SET、DEL、EXISTS。</p></div><button class="secondary-button" data-action="create-redis" :disabled="!selectedEnvironment" @click="startCreateRedis"><AppIcon name="plus" :size="14" />新增数据源</button></div>
            <div class="secret-banner"><AppIcon name="lock" /><div><strong>受控 Redis 连接</strong><small>密码只保存为密钥引用，SET/DEL 运行前必须显式确认。</small></div></div>
            <div class="environment-list"><article v-for="source in redisSources" :key="source.id" class="environment-card"><button class="environment-card-select" @click="startEditRedis(source)"><strong>{{ source.name }} · Redis</strong><small>{{ source.username ? `${source.username}@` : '' }}{{ source.host }}:{{ source.port }} / DB {{ source.databaseNumber }} · {{ source.secretRef || '无认证' }}</small></button><span v-if="source.archived" class="project-status">已归档</span><div><button class="secondary-button" :disabled="redisTestingId === source.id" @click="testRedis(source)">{{ redisTestingId === source.id ? '测试中…' : '测试连接' }}</button><button v-if="!source.archived" class="secondary-button" @click="startEditRedis(source)">编辑</button></div></article></div>
            <p v-if="!redisSources.length" class="project-empty">当前环境暂无 Redis 数据源。</p>
            <form v-if="redisFormOpen" class="environment-form" data-form="redis" @submit.prevent="submitRedis"><h3>{{ redisEditingId ? '编辑 Redis 数据源' : '新增 Redis 数据源' }}</h3><label>名称<input v-model="redisName" required /></label><div class="jdbc-grid"><label>主机<input v-model="redisHost" required /></label><label>端口<input v-model.number="redisPort" type="number" min="1" max="65535" required /></label></div><div class="jdbc-grid"><label>数据库编号<input v-model.number="redisDatabaseNumber" type="number" min="0" max="15" required /></label><label>用户名（可选）<input v-model="redisUsername" /></label></div><label>密码密钥引用（可选）<select v-model="redisSecretRef"><option value="">无认证</option><option v-for="secret in secrets" :key="`redis-${secret.id}`" :value="secret.name">{{ secret.name }}</option></select></label><label class="checkbox-line"><input v-model="redisTls" type="checkbox" />启用 TLS</label><small class="environment-form-hint">只填写密钥名称，不在平台表单、日志或报告中输入密码明文。</small><div><button type="button" class="secondary-button" @click="closeRedisForm">取消</button><button type="submit" class="primary-button" :disabled="saving">保存数据源</button></div></form>
          </template>
          <template v-else>
            <div class="config-title"><div><h3>{{ tab }}</h3><p>管理当前环境的连接与执行资源。</p></div></div>
            <div class="connection-card"><div class="connection-logo"><AppIcon :name="['JDBC', 'Redis'].includes(tab) ? 'database' : tab === '文件' ? 'upload' : 'lock'" :size="24" /></div><div><span class="enabled-pill">待配置</span><h3>{{ tab }}配置</h3><p>本阶段仅支持环境变量和密钥管理。</p></div></div>
          </template>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.environment-empty-state { min-height: 420px; display: grid; place-content: center; justify-items: center; gap: 8px; color: var(--muted); text-align: center; }.environment-empty-state h2 { margin: 4px 0 0; color: var(--ink); }.environment-empty-state p { margin: 0; }.environment-error { margin: 0 20px; padding: 9px 11px; color: var(--red); background: var(--red-soft); border-radius: 6px; }.environment-list { margin-top: 17px; display: grid; gap: 8px; }.environment-card { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 12px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; }.environment-card.selected { border-color: #9db2ff; background: var(--blue-soft); }.environment-card-select { min-width: 0; display: grid; justify-items: start; color: var(--ink); background: transparent; text-align: left; }.environment-card-select strong,.environment-card-select small { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.environment-card-select small { margin-top: 4px; color: var(--muted); }.environment-card>div { display: flex; gap: 5px; }.environment-form,.secret-form { margin-top: 14px; padding-top: 14px; display: grid; gap: 9px; border-top: 1px solid var(--line); }.environment-form h3,.secret-form h3 { margin: 0 0 2px; }.environment-form label,.secret-form label { color: var(--muted); }.environment-form input,.environment-form textarea,.environment-form select,.secret-form input { display: block; width: 100%; margin-top: 4px; padding: 7px; border: 1px solid var(--line-strong); border-radius: 6px; color: var(--ink); background: #fff; }.environment-form>div:last-child,.secret-form>div:last-child { display: flex; justify-content: flex-end; gap: 7px; }.environment-form>button { justify-self: start; }.secret-form { max-width: 460px; }.secret-form input[type="password"] { letter-spacing: .08em; }
.certificate-fields { display: grid; gap: 8px; margin: 0; padding: 10px; border: 1px solid var(--line); border-radius: 7px; }.certificate-fields legend { padding: 0 4px; color: var(--ink); font-size: 10px; font-weight: 600; }.checkbox-line { display: flex; align-items: center; gap: 7px; }.checkbox-line input { width: auto; margin: 0; padding: 0; }.environment-form-hint { color: var(--muted); font-size: 9px; line-height: 1.45; }
.jdbc-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; }
</style>

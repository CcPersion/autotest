<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { projectApi, type ProjectApi, type ProjectSummary } from '../api/project'

const props = defineProps<{
  open: boolean
  projects: ProjectSummary[]
  api?: ProjectApi
}>()

const emit = defineEmits<{
  'update:open': [open: boolean]
  changed: []
}>()

const api = computed(() => props.api ?? projectApi)
const items = ref<ProjectSummary[]>([])
const showArchived = ref(false)
const formOpen = ref(false)
const editingId = ref<string | null>(null)
const name = ref('')
const description = ref('')
const targetAllowlist = ref('')
const errorMessage = ref('')
const saving = ref(false)

function messageFor(error: unknown): string {
  const value = error as { status?: number; code?: string; message?: string }
  if (value.code === 'NAME_CONFLICT') return '项目名称已存在'
  if (value.code === 'REVISION_CONFLICT' || value.status === 409) return '版本已变化，请刷新后重试'
  return value.message || '项目操作失败'
}

async function load() {
  try {
    items.value = await api.value.list(showArchived.value)
    errorMessage.value = ''
  } catch (error) {
    errorMessage.value = messageFor(error)
  }
}

function startCreate() {
  editingId.value = null
  name.value = ''
  description.value = ''
  targetAllowlist.value = ''
  errorMessage.value = ''
  formOpen.value = true
}

function startEdit(project: ProjectSummary) {
  editingId.value = project.id
  name.value = project.name
  description.value = project.description || ''
  targetAllowlist.value = (project.targetAllowlist || []).join('\n')
  errorMessage.value = ''
  formOpen.value = true
}

function cancelForm() {
  formOpen.value = false
  editingId.value = null
}

async function submitForm() {
  saving.value = true
  errorMessage.value = ''
  try {
    const trimmedName = name.value.trim()
    const trimmedDescription = description.value.trim() || undefined
    const rules = targetAllowlist.value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean)
    if (editingId.value) {
      const current = items.value.find((project) => project.id === editingId.value)
      if (!current) return
      const updateInput = {
        name: trimmedName,
        description: trimmedDescription,
        revision: current.revision,
        ...(rules.length ? { targetAllowlist: rules } : {}),
      }
      const updated = await api.value.update(current.id, updateInput)
      items.value = items.value.map((project) => project.id === updated.id ? updated : project)
    } else {
      const created = await api.value.create({
        name: trimmedName,
        description: trimmedDescription,
        ...(rules.length ? { targetAllowlist: rules } : {}),
      })
      items.value = [created, ...items.value]
    }
    formOpen.value = false
    editingId.value = null
    emit('changed')
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    saving.value = false
  }
}

async function archive(project: ProjectSummary) {
  if (!globalThis.confirm(`归档项目“${project.name}”？`)) return
  try {
    const updated = await api.value.archive(project.id, project.revision)
    items.value = showArchived.value
      ? items.value.map((item) => item.id === updated.id ? updated : item)
      : items.value.filter((item) => item.id !== updated.id)
    emit('changed')
  } catch (error) {
    errorMessage.value = messageFor(error)
  }
}

async function restore(project: ProjectSummary) {
  try {
    const updated = await api.value.restore(project.id, project.revision)
    items.value = showArchived.value
      ? items.value.map((item) => item.id === updated.id ? updated : item)
      : [updated, ...items.value]
    emit('changed')
  } catch (error) {
    errorMessage.value = messageFor(error)
  }
}

async function toggleArchived() {
  showArchived.value = !showArchived.value
  await load()
}

watch(() => props.open, (open) => {
  if (open) {
    showArchived.value = false
    items.value = props.projects
    void load()
  }
})

onMounted(() => {
  if (props.open) {
    items.value = props.projects
    void load()
  }
})
</script>

<template>
  <div v-if="props.open" class="modal-backdrop project-manager-backdrop" @click.self="emit('update:open', false)">
    <section class="project-manager" role="dialog" aria-label="项目管理">
      <header class="project-manager-header">
        <div><span class="section-overline">PROJECTS</span><h2>项目管理</h2></div>
        <button class="icon-button" aria-label="关闭项目管理" @click="emit('update:open', false)">×</button>
      </header>
      <div class="project-manager-toolbar">
        <button class="primary-button" data-action="create-project" @click="startCreate">新建项目</button>
        <button class="secondary-button" :data-action="showArchived ? 'show-active' : 'show-archived'" @click="toggleArchived">
          {{ showArchived ? '查看活动项目' : '查看已归档项目' }}
        </button>
      </div>
      <p v-if="errorMessage" class="project-manager-error" role="alert">{{ errorMessage }}</p>
      <div v-if="items.length" class="project-list">
        <article v-for="project in items" :key="project.id" class="project-list-item">
          <div><strong>{{ project.name }}</strong><small>{{ project.description || '暂无描述' }}</small></div>
          <span v-if="project.archived" class="project-status">已归档</span>
          <div class="project-actions">
            <button v-if="!project.archived" class="secondary-button" data-action="edit-project" @click="startEdit(project)">编辑</button>
            <button v-if="!project.archived" class="secondary-button" data-action="archive-project" @click="archive(project)">归档</button>
            <button v-else class="secondary-button" data-action="restore-project" @click="restore(project)">恢复</button>
          </div>
        </article>
      </div>
      <p v-else class="project-empty">{{ showArchived ? '暂无已归档项目' : '暂无活动项目，请先新建项目。' }}</p>
      <form v-if="formOpen" class="project-form" @submit.prevent="submitForm">
        <h3>{{ editingId ? '编辑项目' : '新建项目' }}</h3>
        <label>名称<input v-model="name" name="project-name" required /></label>
        <label>描述<textarea v-model="description" name="project-description" rows="2" /></label>
        <label>HTTP 目标白名单
          <textarea v-model="targetAllowlist" name="project-target-allowlist" rows="3"
                    placeholder="每行一个域名/IP；支持 *.example.test" />
          <small class="project-form-help">只有列入白名单的 HTTP/HTTPS 目标才允许运行，内网地址也需显式填写。</small>
        </label>
        <div class="project-form-actions">
          <button type="button" class="secondary-button" @click="cancelForm">取消</button>
          <button type="submit" class="primary-button" :disabled="saving">保存</button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.project-manager-backdrop { place-items: start center; }
.project-manager { width: min(720px, 92vw); max-height: 78vh; overflow: auto; padding: 20px; background: #fff; border-radius: 12px; box-shadow: 0 28px 90px rgba(17,24,39,.24); }
.project-manager-header, .project-manager-toolbar, .project-list-item, .project-form-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.project-manager-header { padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.project-manager-header h2 { margin: 4px 0 0; }
.project-manager-toolbar { padding: 14px 0; }
.project-list { display: grid; gap: 8px; }
.project-list-item { padding: 12px; border: 1px solid var(--line); border-radius: 8px; }
.project-list-item strong, .project-list-item small { display: block; }
.project-list-item small { margin-top: 4px; color: var(--muted); }
.project-actions { display: flex; gap: 6px; }
.project-status { color: var(--muted); font-size: 12px; }
.project-empty { padding: 24px 0; color: var(--muted); text-align: center; }
.project-manager-error { color: var(--red); }
.project-form { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--line); }
.project-form h3 { margin: 0 0 10px; }
.project-form label { display: block; margin-top: 9px; color: var(--muted); }
.project-form input, .project-form textarea { display: block; width: 100%; margin-top: 5px; padding: 8px; border: 1px solid var(--line-strong); border-radius: 6px; }
.project-form-help { display: block; margin-top: 4px; color: var(--muted); font-size: 12px; }
.project-form-actions { justify-content: flex-end; margin-top: 12px; }
</style>

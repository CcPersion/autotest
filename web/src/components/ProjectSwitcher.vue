<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { projectApi, type ProjectApi, type ProjectSummary } from '../api/project'

const props = defineProps<{
  modelValue?: string | null
  api?: ProjectApi
}>()

const emit = defineEmits<{
  'update:modelValue': [projectId: string | null]
  loaded: [projects: ProjectSummary[]]
  create: []
  manage: []
}>()

const projects = ref<ProjectSummary[]>([])
const loading = ref(false)
const errorMessage = ref('')
const api = computed(() => props.api ?? projectApi)

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    projects.value = await api.value.list(false)
    emit('loaded', projects.value)
    const selected = projects.value.find((project) => project.id === props.modelValue)
    if (!selected) emit('update:modelValue', projects.value[0]?.id ?? null)
  } catch (error) {
    errorMessage.value = (error as { message?: string })?.message || '项目加载失败'
  } finally {
    loading.value = false
  }
}

function selectProject(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  emit('update:modelValue', value || null)
}

onMounted(refresh)
defineExpose({ projects, refresh })
</script>

<template>
  <div class="project-switcher" data-testid="project-switcher">
    <label class="context-select">
      <span>项目</span>
      <select aria-label="当前项目" :value="props.modelValue || ''" :disabled="loading" @change="selectProject">
        <option v-if="projects.length === 0" value="">{{ loading ? '加载中…' : '暂无项目' }}</option>
        <option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
    </label>
    <button class="project-manage-trigger" data-action="manage-projects" type="button" @click="emit('manage')">管理</button>
    <button v-if="projects.length === 0 && !loading" data-action="create-project" type="button" @click="emit('create')">新建项目</button>
    <span v-if="errorMessage" class="project-switcher-error" role="alert">{{ errorMessage }}</span>
  </div>
</template>

<style scoped>
.project-switcher { display: flex; align-items: center; gap: 7px; }
.project-manage-trigger, .project-switcher > button:last-of-type { padding: 3px 6px; color: var(--blue); background: transparent; font-size: 12px; }
.project-switcher-error { position: absolute; margin-top: 54px; color: var(--red); font-size: 12px; }
</style>

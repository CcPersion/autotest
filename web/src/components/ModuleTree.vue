<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { moduleApi, type ModuleApi, type ModuleNode } from '../api/module'
import ModuleTreeNode from './ModuleTreeNode.vue'

const props = defineProps<{
  projectId: string
  nodes?: ModuleNode[]
  api?: ModuleApi
}>()

const emit = defineEmits<{
  select: [node: ModuleNode]
  changed: []
}>()

const api = computed(() => props.api ?? moduleApi)
const tree = ref<ModuleNode[]>(props.nodes || [])
const loading = ref(false)
const errorMessage = ref('')
const newModuleName = ref('')
const addingParentId = ref<string | null>(null)
const addingFormOpen = ref(false)
const creating = ref(false)
const renamingId = ref<string | null>(null)
const renameValue = ref('')
const renaming = ref(false)
const draggingId = ref<string | null>(null)

function messageFor(error: unknown): string {
  const value = error as { status?: number; code?: string; message?: string; details?: { childCount?: number; apiCount?: number } }
  if (value.code === 'MODULE_NOT_EMPTY') {
    return `模块非空：${value.details?.childCount ?? 0} 个子模块、${value.details?.apiCount ?? 0} 个接口，不能删除`
  }
  if (value.code === 'MODULE_CYCLE') return '不能移动到自身或子模块下'
  if (value.code === 'NAME_CONFLICT') return '模块名称已存在'
  if (value.code === 'REVISION_CONFLICT' || value.status === 409) return '版本已变化，请刷新后重试'
  return value.message || '模块操作失败'
}

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    tree.value = await api.value.tree(props.projectId)
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    loading.value = false
  }
}

function startAdd(node: ModuleNode | null) {
  addingParentId.value = node?.id ?? null
  addingFormOpen.value = true
  newModuleName.value = ''
  errorMessage.value = ''
}

function cancelAdd() {
  addingFormOpen.value = false
  addingParentId.value = null
}

async function saveNewModule() {
  if (creating.value || !addingFormOpen.value || !newModuleName.value.trim()) return
  creating.value = true
  try {
    await api.value.create(props.projectId, {
      name: newModuleName.value.trim(),
      parentId: addingParentId.value,
      position: addingParentId.value === null ? tree.value.length : 0,
    })
    cancelAdd()
    await refresh()
    emit('changed')
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    creating.value = false
  }
}

function startRename(node: ModuleNode) {
  renamingId.value = node.id
  renameValue.value = node.name
  errorMessage.value = ''
}

async function saveRename(node: ModuleNode) {
  if (renaming.value || !renameValue.value.trim()) return
  renaming.value = true
  try {
    await api.value.rename(props.projectId, node.id, { name: renameValue.value.trim(), revision: node.revision })
    renamingId.value = null
    await refresh()
    emit('changed')
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    renaming.value = false
  }
}

async function remove(node: ModuleNode) {
  if (!globalThis.confirm(`删除模块“${node.name}”？`)) return
  try {
    await api.value.remove(props.projectId, node.id, node.revision)
    await refresh()
    emit('changed')
  } catch (error) {
    errorMessage.value = messageFor(error)
  }
}

function dragStart(node: ModuleNode) {
  draggingId.value = node.id
}

async function drop(target: ModuleNode, placement: 'child' | 'after' = 'child') {
  if (!draggingId.value || draggingId.value === target.id) return
  const source = findNode(tree.value, draggingId.value)
  if (!source) return
  const sameParent = source.parentId === target.parentId
  const position = placement === 'after'
    ? (sameParent && source.sortOrder < target.sortOrder ? target.sortOrder : target.sortOrder + 1)
    : 0
  try {
    await api.value.move(props.projectId, source.id, {
      parentId: placement === 'after' ? target.parentId : target.id,
      position,
      revision: source.revision,
    })
    draggingId.value = null
    await refresh()
    emit('changed')
  } catch (error) {
    errorMessage.value = messageFor(error)
  }
}

function onNativeDragStart(event: Event) {
  const element = (event.target as HTMLElement).closest<HTMLElement>('[data-node-id]')
  if (element) draggingId.value = element.dataset.nodeId || null
}

function onNativeDrop(event: Event) {
  event.preventDefault()
  const targetElement = event.target as HTMLElement
  const element = targetElement.closest<HTMLElement>('[data-node-id]')
  const targetId = element?.dataset.nodeId
  const target = targetId ? findNode(tree.value, targetId) : undefined
  const placement = targetElement.closest('[data-drop-position]')?.getAttribute('data-drop-position') === 'after'
    ? 'after'
    : 'child'
  if (target) void drop(target, placement)
}

function findNode(nodes: ModuleNode[], id: string): ModuleNode | undefined {
  for (const node of nodes) {
    if (node.id === id) return node
    const found = findNode(node.children, id)
    if (found) return found
  }
  return undefined
}

watch(() => props.projectId, () => {
  if (props.nodes === undefined) void refresh()
})

onMounted(() => {
  if (props.nodes === undefined) void refresh()
})

defineExpose({ refresh })
</script>

<template>
  <section class="module-tree" data-testid="module-tree" @dragstart="onNativeDragStart" @dragover.prevent @drop="onNativeDrop">
    <div class="module-tree-heading">
      <strong>模块</strong>
      <span>
        <button type="button" data-action="create-root-module" @click="startAdd(null)">新建根模块</button>
        <button type="button" data-action="refresh-modules" @click="refresh">刷新</button>
      </span>
    </div>
    <p v-if="loading" class="module-tree-empty">加载中…</p>
    <p v-else-if="!tree.length" class="module-tree-empty">暂无模块</p>
    <ModuleTreeNode
      v-for="node in tree"
      v-else
      :key="node.id"
      :node="node"
      :depth="0"
      @select="emit('select', $event)"
      @add="startAdd"
      @rename="startRename"
      @remove="remove"
    />
    <form v-if="addingFormOpen" class="module-tree-form" @submit.prevent="saveNewModule">
      <input v-model="newModuleName" name="new-module-name" aria-label="新模块名称" placeholder="新模块名称" required />
      <button type="submit" data-action="save-new-module" :disabled="creating">保存</button>
      <button type="button" @click="cancelAdd">取消</button>
    </form>
    <form v-if="renamingId !== null" class="module-tree-form" @submit.prevent="saveRename(findNode(tree, renamingId)!)">
      <input v-model="renameValue" name="rename-module" aria-label="模块名称" required />
      <button type="submit" data-action="save-rename" :disabled="renaming">保存</button>
      <button type="button" @click="renamingId = null">取消</button>
    </form>
    <p v-if="errorMessage" class="module-tree-error" role="alert">{{ errorMessage }}</p>
  </section>
</template>

<style scoped>
.module-tree { min-height: 120px; padding: 8px 6px; }
.module-tree-heading { height: 29px; padding: 0 8px; display: flex; align-items: center; justify-content: space-between; color: #7c8698; }
.module-tree-heading button { color: var(--blue); background: transparent; font-size: 11px; }
.module-tree-empty { padding: 18px 8px; color: var(--muted); font-size: 12px; text-align: center; }
.module-tree-form { display: flex; gap: 5px; padding: 8px; }
.module-tree-form input { min-width: 0; flex: 1; padding: 6px; border: 1px solid var(--line-strong); border-radius: 5px; }
.module-tree-form button { padding: 4px 6px; color: var(--blue); background: transparent; }
.module-tree-error { margin: 8px; color: var(--red); font-size: 12px; }
</style>

<script setup lang="ts">
import type { ModuleNode } from '../api/module'

defineOptions({ name: 'ModuleTreeNode' })

const props = defineProps<{
  node: ModuleNode
  depth: number
}>()

const emit = defineEmits<{
  select: [node: ModuleNode]
  add: [node: ModuleNode]
  rename: [node: ModuleNode]
  remove: [node: ModuleNode]
  'node-drag-start': [node: ModuleNode]
  'node-drop': [node: ModuleNode]
}>()
</script>

<template>
  <div class="module-tree-node" :data-node-id="props.node.id" draggable="true">
    <div class="module-tree-row" :style="{ paddingLeft: `${props.depth * 16 + 8}px` }" @click="emit('select', props.node)">
      <span class="module-tree-chevron">{{ props.node.children.length ? '▾' : '·' }}</span>
      <strong>{{ props.node.name }}</strong>
      <span class="module-tree-actions">
        <button type="button" :data-node-action="'add'" :data-node-id="props.node.id" aria-label="新增子模块" @click.stop="emit('add', props.node)">＋</button>
        <button type="button" :data-node-action="'rename'" :data-node-id="props.node.id" aria-label="重命名模块" @click.stop="emit('rename', props.node)">改名</button>
        <button type="button" :data-node-action="'remove'" :data-node-id="props.node.id" aria-label="删除模块" @click.stop="emit('remove', props.node)">删</button>
      </span>
    </div>
    <ModuleTreeNode
      v-for="child in props.node.children"
      :key="child.id"
      :node="child"
      :depth="props.depth + 1"
      @select="emit('select', $event)"
      @add="emit('add', $event)"
      @rename="emit('rename', $event)"
      @remove="emit('remove', $event)"
    />
    <div class="module-tree-drop-after" :data-node-id="props.node.id" data-drop-position="after" aria-hidden="true"></div>
  </div>
</template>

<style scoped>
.module-tree-node { user-select: none; }
.module-tree-row { min-height: 34px; display: flex; align-items: center; gap: 5px; color: var(--ink); border-radius: 5px; cursor: grab; }
.module-tree-row:hover { background: #f0f2f5; }
.module-tree-chevron { width: 12px; color: #9aa2b1; text-align: center; }
.module-tree-row strong { min-width: 0; flex: 1; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.module-tree-actions { display: flex; gap: 2px; opacity: .55; }
.module-tree-row:hover .module-tree-actions { opacity: 1; }
.module-tree-actions button { padding: 2px 3px; color: #697386; background: transparent; font-size: 10px; }
</style>

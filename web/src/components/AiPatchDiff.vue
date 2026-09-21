<script setup lang="ts">
import type { AiPatchPreviewResponse } from '../api/aiPatch'

const props = withDefaults(defineProps<{
  preview: AiPatchPreviewResponse | null
  loading?: boolean
}>(), { loading: false })

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

function display(value: unknown) {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}

function changeLabel(type: string) {
  return { ADDED: '新增', MODIFIED: '修改', REMOVED: '删除', UNCHANGED: '未变化' }[type] ?? type
}
</script>

<template>
  <section class="patch-diff" data-testid="ai-patch-diff">
    <div v-if="!props.preview" class="patch-empty">暂无草稿差异</div>
    <template v-else>
      <header class="patch-head">
        <div><span class="eyebrow">PATCH REVIEW</span><h3>{{ props.preview.title }}</h3><small>{{ props.preview.targetType }} · revision {{ props.preview.currentRevision }}</small></div>
        <span class="patch-state" :class="{ blocked: !props.preview.canConfirm }">{{ props.preview.canConfirm ? '可确认' : '需修正' }}</span>
      </header>
      <div v-if="props.preview.errors.length" class="patch-errors" role="alert">
        <strong>校验未通过</strong>
        <p v-for="error in props.preview.errors" :key="`${error.path}-${error.code}`"><code>{{ error.path }}</code> {{ error.message }}</p>
      </div>
      <div v-if="props.preview.warnings.length" class="patch-warnings"><span v-for="warning in props.preview.warnings" :key="warning">{{ warning }}</span></div>
      <div class="patch-table-wrap">
        <table class="patch-table"><thead><tr><th>字段</th><th>变化</th><th>原值</th><th>新值</th><th>风险</th></tr></thead>
          <tbody><tr v-for="change in props.preview.changes" :key="`${change.path}-${change.changeType}`" :class="{ dangerous: change.dangerous }" data-testid="ai-patch-change">
            <td><code>{{ change.path }}</code></td><td><span class="change-type">{{ changeLabel(change.changeType) }}</span></td><td><pre>{{ display(change.oldValue) }}</pre></td><td><pre>{{ display(change.newValue) }}</pre></td><td>{{ change.dangerous ? '需确认' : '—' }}</td>
          </tr><tr v-if="!props.preview.changes.length"><td colspan="5" class="no-changes">没有字段变化</td></tr></tbody>
        </table>
      </div>
      <footer class="patch-actions"><button type="button" class="secondary-button" :disabled="props.loading" @click="emit('cancel')">关闭</button><button type="button" class="primary-button" :disabled="props.loading || !props.preview.canConfirm" data-action="confirm-ai-patch" @click="emit('confirm')">{{ props.loading ? '保存中…' : '确认并保存' }}</button></footer>
    </template>
  </section>
</template>

<style scoped>
.patch-diff { border: 1px solid var(--line); border-radius: 10px; background: #fff; overflow: hidden; color: var(--ink); }
.patch-empty { padding: 28px; color: var(--muted); font-size: 11px; text-align: center; }
.patch-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 16px 18px; border-bottom: 1px solid var(--line); }
.eyebrow { color: var(--blue); font-size: 8px; letter-spacing: .14em; }
.patch-head h3 { margin: 4px 0 3px; font-size: 14px; }
.patch-head small { color: var(--muted); font-size: 9px; }
.patch-state { padding: 5px 9px; border-radius: 999px; color: #147b51; background: #e9f8ef; font-size: 9px; }
.patch-state.blocked { color: #a24b39; background: #fff0ec; }
.patch-errors, .patch-warnings { margin: 12px 16px 0; padding: 9px 11px; border-radius: 7px; font-size: 9px; }
.patch-errors { color: #a24b39; background: #fff3f0; border: 1px solid #f2d1ca; }
.patch-errors p { margin: 5px 0 0; }.patch-errors code { font-family: var(--mono); }
.patch-warnings { color: #886321; background: #fff9e9; border: 1px solid #f3e4b4; }
.patch-warnings span + span { margin-left: 8px; }
.patch-table-wrap { overflow: auto; margin: 12px 16px; border: 1px solid var(--line); border-radius: 7px; }
.patch-table { width: 100%; border-collapse: collapse; font-size: 9px; }
.patch-table th { padding: 9px; color: var(--muted); background: #f7f8fb; text-align: left; white-space: nowrap; }
.patch-table td { padding: 9px; border-top: 1px solid #edf0f5; vertical-align: top; }.patch-table tr.dangerous { background: #fffaf4; }
.patch-table code { color: var(--blue); font-family: var(--mono); }.patch-table pre { max-width: 280px; margin: 0; overflow: auto; white-space: pre-wrap; font: 9px/1.45 var(--mono); color: #566174; }
.change-type { color: #168a5a; }.dangerous .change-type { color: #a45b1a; }.no-changes { padding: 18px; color: var(--muted); text-align: center; }
.patch-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--line); background: #fbfcfe; }
button { border: 1px solid var(--line); border-radius: 5px; padding: 7px 11px; cursor: pointer; font-size: 9px; }.primary-button { color: #fff; background: var(--blue); border-color: var(--blue); }.secondary-button { color: #586579; background: #fff; }button:disabled { cursor: not-allowed; opacity: .55; }
</style>

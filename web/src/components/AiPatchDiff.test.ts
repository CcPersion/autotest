// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AiPatchDiff from './AiPatchDiff.vue'

const preview = {
  previewId: 'p1', projectId: 'project-1', targetType: 'API_DEFINITION', title: '调整接口', targetId: 'd1', parentId: null,
  baseRevision: 2, currentRevision: 2, canConfirm: true, warnings: [], errors: [], expiresAt: '',
  changes: [
    { path: '/name', changeType: 'MODIFIED', oldValue: '登录', newValue: '登录 v2', dangerous: false },
    { path: '/requestSpec/headers/0/value', changeType: 'MODIFIED', oldValue: '${secret:old}', newValue: '${secret:new}', dangerous: true },
  ],
} as const

describe('AI Patch 差异组件', () => {
  it('显示字段级旧值、新值和风险标记', () => {
    const wrapper = mount(AiPatchDiff, { props: { preview } })
    expect(wrapper.text()).toContain('/name')
    expect(wrapper.text()).toContain('登录 v2')
    expect(wrapper.text()).toContain('需确认')
    expect(wrapper.findAll('[data-testid="ai-patch-change"]')).toHaveLength(2)
  })

  it('确认和关闭都通过事件交给上层', async () => {
    const wrapper = mount(AiPatchDiff, { props: { preview } })
    await wrapper.get('button[data-action="confirm-ai-patch"]').trigger('click')
    await wrapper.get('button.secondary-button').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})

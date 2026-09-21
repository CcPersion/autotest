// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import ProjectSwitcher from './ProjectSwitcher.vue'

const projects = [
  { id: 'p1', name: '订单平台', description: '', revision: 1, archived: false, createdAt: '', updatedAt: '' },
  { id: 'p2', name: '支付平台', description: '', revision: 1, archived: false, createdAt: '', updatedAt: '' },
]

describe('项目切换器', () => {
  it('加载真实活动项目、默认选择首项并发出切换事件', async () => {
    const api = { list: vi.fn().mockResolvedValue(projects) }
    const wrapper = mount(ProjectSwitcher, { props: { api } })

    await flushPromises()

    expect(api.list).toHaveBeenCalledWith(false)
    expect(wrapper.text()).toContain('订单平台')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['p1'])

    await wrapper.get('select').setValue('p2')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['p2'])
  })

  it('没有活动项目时展示空状态并提供新建入口', async () => {
    const wrapper = mount(ProjectSwitcher, { props: { api: { list: vi.fn().mockResolvedValue([]) } } })
    await flushPromises()

    expect(wrapper.text()).toContain('暂无项目')
    expect(wrapper.get('button[data-action="create-project"]').exists()).toBe(true)
  })

  it('暴露 refresh 以便项目管理变更后刷新内部项目列表', async () => {
    const nextProjects = [{ ...projects[0], name: '订单平台（已更新）' }]
    const api = { list: vi.fn().mockResolvedValueOnce(projects).mockResolvedValueOnce(nextProjects) }
    const wrapper = mount(ProjectSwitcher, { props: { api } })
    await flushPromises()

    await (wrapper.vm as unknown as { refresh: () => Promise<void> }).refresh()
    await flushPromises()

    expect(api.list).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('订单平台（已更新）')
  })
})

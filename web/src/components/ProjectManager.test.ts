// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import ProjectManager from './ProjectManager.vue'

const activeProject = { id: 'p1', name: '订单平台', description: '主项目', revision: 2, archived: false, createdAt: '', updatedAt: '' }

describe('项目管理弹层', () => {
  it('支持新建、编辑、归档以及查看并恢复归档项目', async () => {
    const archivedProject = { ...activeProject, id: 'p2', name: '旧项目', archived: true, revision: 4 }
    const api = {
      list: vi.fn().mockResolvedValue([activeProject]),
      create: vi.fn().mockResolvedValue({ ...activeProject, id: 'p3', name: '新项目' }),
      update: vi.fn().mockResolvedValue({ ...activeProject, name: '订单平台 V2', revision: 3 }),
      archive: vi.fn().mockResolvedValue({ ...activeProject, archived: true, revision: 3 }),
      restore: vi.fn().mockResolvedValue({ ...archivedProject, archived: false, revision: 5 }),
    }
    const wrapper = mount(ProjectManager, { props: { open: true, projects: [activeProject], api } })
    await flushPromises()

    await wrapper.get('button[data-action="create-project"]').trigger('click')
    await wrapper.get('input[name="project-name"]').setValue('新项目')
    await wrapper.get('form').trigger('submit')
    expect(api.create).toHaveBeenCalledWith({ name: '新项目', description: undefined })

    await wrapper.get('button[data-action="edit-project"]').trigger('click')
    await wrapper.get('input[name="project-name"]').setValue('订单平台 V2')
    await wrapper.get('form').trigger('submit')
    expect(api.update).toHaveBeenCalledWith('p3', { name: '订单平台 V2', description: '主项目', revision: 2 })

    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    await wrapper.get('button[data-action="archive-project"]').trigger('click')
    expect(api.archive).toHaveBeenCalledWith('p3', 2)

    api.list.mockResolvedValue([archivedProject])
    await wrapper.get('button[data-action="show-archived"]').trigger('click')
    await flushPromises()
    await wrapper.get('button[data-action="restore-project"]').trigger('click')
    expect(api.restore).toHaveBeenCalledWith('p2', 4)
  })

  it('将版本冲突显示为刷新后重试', async () => {
    const api = {
      list: vi.fn().mockResolvedValue([activeProject]),
      update: vi.fn().mockRejectedValue({ status: 409, code: 'REVISION_CONFLICT', message: '版本已变化' }),
    }
    const wrapper = mount(ProjectManager, { props: { open: true, projects: [activeProject], api } })
    await flushPromises()
    await wrapper.get('button[data-action="edit-project"]').trigger('click')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('版本已变化，请刷新后重试')
  })

  it('按错误 code 优先提示名称冲突，而不是笼统版本冲突', async () => {
    const api = {
      list: vi.fn().mockResolvedValue([activeProject]),
      update: vi.fn().mockRejectedValue({ status: 409, code: 'NAME_CONFLICT', message: 'conflict' }),
    }
    const wrapper = mount(ProjectManager, { props: { open: true, projects: [activeProject], api } })
    await flushPromises()
    await wrapper.get('button[data-action="edit-project"]').trigger('click')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('项目名称已存在')
    expect(wrapper.text()).not.toContain('版本已变化，请刷新后重试')
  })
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import ModuleTree from './ModuleTree.vue'

const root = {
  id: 'm1', projectId: 'p1', parentId: null, name: '订单', sortOrder: 0, revision: 1,
  children: [{ id: 'm2', projectId: 'p1', parentId: 'm1', name: '查询', sortOrder: 0, revision: 1, children: [] }],
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

describe('共享模块树', () => {
  it('创建请求 pending 时连续提交只调用一次并在完成后恢复按钮', async () => {
    const pending = deferred<typeof root>()
    const api = {
      tree: vi.fn().mockResolvedValue([root]),
      create: vi.fn().mockReturnValue(pending.promise),
    }
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', api } })
    await flushPromises()

    await wrapper.get('button[data-node-action="add"][data-node-id="m1"]').trigger('click')
    await wrapper.get('input[name="new-module-name"]').setValue('支付')
    const form = wrapper.get('form')
    await Promise.all([form.trigger('submit'), form.trigger('submit')])

    expect(api.create).toHaveBeenCalledTimes(1)
    expect(wrapper.get('button[data-action="save-new-module"]').attributes('disabled')).toBeDefined()

    pending.resolve({ ...root, id: 'm3', name: '支付', parentId: 'm1', children: [] })
    await flushPromises()
    expect(wrapper.find('button[data-action="save-new-module"]').exists()).toBe(false)
  })

  it('改名请求 pending 时连续提交只调用一次并在完成后恢复按钮', async () => {
    const pending = deferred<typeof root>()
    const api = {
      tree: vi.fn().mockResolvedValue([root]),
      rename: vi.fn().mockReturnValue(pending.promise),
    }
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', api } })
    await flushPromises()

    await wrapper.get('button[data-node-action="rename"][data-node-id="m1"]').trigger('click')
    await wrapper.get('input[name="rename-module"]').setValue('订单 API')
    const form = wrapper.get('form')
    await Promise.all([form.trigger('submit'), form.trigger('submit')])

    expect(api.rename).toHaveBeenCalledTimes(1)
    expect(wrapper.get('button[data-action="save-rename"]').attributes('disabled')).toBeDefined()

    pending.resolve({ ...root, name: '订单 API', revision: 2 })
    await flushPromises()
    expect(wrapper.find('button[data-action="save-rename"]').exists()).toBe(false)
  })

  it('渲染任意层级并支持新增子模块、改名和拖拽移动', async () => {
    const api = {
      tree: vi.fn().mockResolvedValue([root]),
      create: vi.fn().mockResolvedValue({ ...root, id: 'm3', parentId: 'm1', name: '支付', children: [] }),
      rename: vi.fn().mockResolvedValue({ ...root, name: '订单 API', revision: 2 }),
      move: vi.fn().mockResolvedValue({ ...root, revision: 2 }),
    }
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', api } })
    await flushPromises()

    expect(wrapper.text()).toContain('订单')
    expect(wrapper.text()).toContain('查询')

    await wrapper.get('button[data-node-action="add"][data-node-id="m1"]').trigger('click')
    await wrapper.get('input[name="new-module-name"]').setValue('支付')
    await wrapper.get('button[data-action="save-new-module"]').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.create).toHaveBeenCalledWith('p1', { name: '支付', parentId: 'm1', position: 0 })
    expect(api.create).toHaveBeenCalledTimes(1)

    await wrapper.get('button[data-node-action="rename"][data-node-id="m1"]').trigger('click')
    await wrapper.get('input[name="rename-module"]').setValue('订单 API')
    await wrapper.get('button[data-action="save-rename"]').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.rename).toHaveBeenCalledWith('p1', 'm1', { name: '订单 API', revision: 1 })
    expect(api.rename).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-node-id="m2"]').trigger('dragstart')
    await wrapper.get('[data-node-id="m1"]').trigger('drop')
    expect(api.move).toHaveBeenCalledWith('p1', 'm2', { parentId: 'm1', position: 0, revision: 1 })
  })

  it('同父模块向下 after 移动按移除后的最终位置提交', async () => {
    const siblings = [
      { id: 'a', projectId: 'p1', parentId: null, name: 'A', sortOrder: 0, revision: 1, children: [] },
      { id: 'b', projectId: 'p1', parentId: null, name: 'B', sortOrder: 1, revision: 1, children: [] },
      { id: 'c', projectId: 'p1', parentId: null, name: 'C', sortOrder: 2, revision: 1, children: [] },
    ]
    const api = { move: vi.fn().mockResolvedValue(siblings[0]) }
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', nodes: siblings, api } })

    await wrapper.get('[data-node-id="a"]').trigger('dragstart')
    await wrapper.get('.module-tree-drop-after[data-node-id="b"]').trigger('drop')
    await flushPromises()

    expect(api.move).toHaveBeenCalledWith('p1', 'a', { parentId: null, position: 1, revision: 1 })
  })

  it('同父模块向上 after 移动提交目标后的最终位置', async () => {
    const siblings = [
      { id: 'a', projectId: 'p1', parentId: null, name: 'A', sortOrder: 0, revision: 1, children: [] },
      { id: 'b', projectId: 'p1', parentId: null, name: 'B', sortOrder: 1, revision: 1, children: [] },
      { id: 'c', projectId: 'p1', parentId: null, name: 'C', sortOrder: 2, revision: 1, children: [] },
    ]
    const api = { move: vi.fn().mockResolvedValue(siblings[2]) }
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', nodes: siblings, api } })

    await wrapper.get('[data-node-id="c"]').trigger('dragstart')
    await wrapper.get('.module-tree-drop-after[data-node-id="a"]').trigger('drop')
    await flushPromises()

    expect(api.move).toHaveBeenCalledWith('p1', 'c', { parentId: null, position: 1, revision: 1 })
  })

  it('删除非空模块显示子模块和接口数量，并处理版本冲突', async () => {
    const api = {
      tree: vi.fn().mockResolvedValue([root]),
      remove: vi.fn().mockRejectedValue({
        status: 409,
        code: 'MODULE_NOT_EMPTY',
        details: { childCount: 1, apiCount: 2 },
      }),
    }
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', api } })
    await flushPromises()
    await wrapper.get('button[data-node-action="remove"][data-node-id="m1"]').trigger('click')
    await flushPromises()

    expect(api.remove).toHaveBeenCalledWith('p1', 'm1', 1)
    expect(wrapper.text()).toContain('模块非空：1 个子模块、2 个接口，不能删除')
  })

  it('可从根入口新建模块并按根节点数量追加', async () => {
    const api = {
      tree: vi.fn().mockResolvedValue([]),
      create: vi.fn().mockResolvedValue({ ...root, id: 'm3', name: '鉴权', parentId: null, sortOrder: 0 }),
    }
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', api, nodes: [root] } })

    await wrapper.get('button[data-action="create-root-module"]').trigger('click')
    await wrapper.get('input[name="new-module-name"]').setValue('鉴权')
    await wrapper.get('button[data-action="save-new-module"]').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.create).toHaveBeenCalledWith('p1', { name: '鉴权', parentId: null, position: 1 })
    expect(api.create).toHaveBeenCalledTimes(1)
  })

  it('按错误 code 优先提示模块环路，而不是笼统版本冲突', async () => {
    const api = {
      tree: vi.fn().mockResolvedValue([root]),
      move: vi.fn().mockRejectedValue({ status: 409, code: 'MODULE_CYCLE', message: 'cycle' }),
    }
    const wrapper = mount(ModuleTree, { props: { projectId: 'p1', api } })
    await flushPromises()

    await wrapper.get('[data-node-id="m2"]').trigger('dragstart')
    await wrapper.get('[data-node-id="m1"]').trigger('drop')
    await flushPromises()

    expect(wrapper.text()).toContain('不能移动到自身或子模块下')
    expect(wrapper.text()).not.toContain('版本已变化，请刷新后重试')
  })
})

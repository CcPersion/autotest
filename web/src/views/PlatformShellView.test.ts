// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

import { SESSION_KEY } from '../auth/session'
import PlatformShellView from './PlatformShellView.vue'

const mocks = vi.hoisted(() => ({
  projectApi: {
    list: vi.fn().mockResolvedValue([
      { id: 'p1', name: '订单平台', description: '', revision: 1, archived: false, createdAt: '', updatedAt: '' },
      { id: 'p2', name: '支付平台', description: '', revision: 1, archived: false, createdAt: '', updatedAt: '' },
    ]),
  },
  environmentApi: {
    list: vi.fn().mockImplementation((projectId: string) => Promise.resolve(projectId === 'p1'
      ? [{ id: 'e1', projectId, name: '订单测试', baseUrl: 'https://orders.example.com', variables: {}, revision: 1, archived: false, createdAt: '', updatedAt: '' }]
      : [{ id: 'e2', projectId, name: '支付预发布', baseUrl: 'https://payments.example.com', variables: {}, revision: 1, archived: false, createdAt: '', updatedAt: '' }])),
  },
}))

const { projectApi, environmentApi } = mocks

vi.mock('../api/project', () => mocks)
vi.mock('../api/environment', () => mocks)

describe('平台壳层环境选择器', () => {
  it('活动环境跟随项目切换刷新且不保留旧项目选项', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: PlatformShellView }] })
    await router.push('/')
    const session = {
      user: ref({ id: 'u1', username: 'admin' }),
      ready: ref(true),
      restore: vi.fn().mockResolvedValue(undefined),
      logout: vi.fn().mockResolvedValue(undefined),
      changePassword: vi.fn().mockResolvedValue(undefined),
    }
    const wrapper = mount(PlatformShellView, {
      global: { plugins: [router], provide: { [SESSION_KEY as symbol]: session } },
    })
    await flushPromises()

    expect(environmentApi.list).toHaveBeenCalledWith('p1', false)
    expect(wrapper.get('[data-testid="environment-switcher"]').text()).toContain('订单测试')

    await wrapper.get('select[aria-label="当前项目"]').setValue('p2')
    await flushPromises()

    expect(environmentApi.list).toHaveBeenLastCalledWith('p2', false)
    expect(wrapper.get('[data-testid="environment-switcher"]').text()).toContain('支付预发布')
    expect(wrapper.get('[data-testid="environment-switcher"]').text()).not.toContain('订单测试')
  })

  it('环境变更事件触发顶部刷新，归档当前环境后不残留旧选项', async () => {
    environmentApi.list
      .mockReset()
      .mockResolvedValueOnce([{ id: 'e1', projectId: 'p1', name: '订单测试', baseUrl: '', variables: {}, revision: 1, archived: false, createdAt: '', updatedAt: '' }])
      .mockResolvedValueOnce([{ id: 'e2', projectId: 'p1', name: '订单新环境', baseUrl: '', variables: {}, revision: 1, archived: false, createdAt: '', updatedAt: '' }])
      .mockResolvedValueOnce([])
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: PlatformShellView }] })
    await router.push('/')
    const session = {
      user: ref({ id: 'u1', username: 'admin' }),
      ready: ref(true),
      restore: vi.fn().mockResolvedValue(undefined),
      logout: vi.fn().mockResolvedValue(undefined),
      changePassword: vi.fn().mockResolvedValue(undefined),
    }
    const wrapper = mount(PlatformShellView, {
      global: {
        plugins: [router],
        provide: { [SESSION_KEY as symbol]: session },
        stubs: {
          EnvironmentView: {
            emits: ['changed'],
            template: '<div data-testid="environment-view-stub"><button data-action="stub-create-environment" @click="$emit(\'changed\')">创建完成</button><button data-action="stub-archive-environment" @click="$emit(\'changed\')">归档完成</button></div>',
          },
        },
      },
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="environment-switcher"]').text()).toContain('订单测试')

    await wrapper.get('button[aria-label="环境配置"]').trigger('click')
    await wrapper.get('button[data-action="stub-create-environment"]').trigger('click')
    await flushPromises()
    expect(environmentApi.list).toHaveBeenLastCalledWith('p1', false)
    expect(wrapper.get('[data-testid="environment-switcher"]').text()).toContain('订单新环境')

    await wrapper.get('button[data-action="stub-archive-environment"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="environment-switcher"]').text()).not.toContain('订单新环境')
    expect(wrapper.get('[data-testid="environment-switcher"]').text()).toContain('暂无活动环境')
  })
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ref } from 'vue'

import LoginView from './LoginView.vue'

describe('登录页', () => {
  function setup() {
    const session = {
      user: ref(null),
      ready: ref(true),
      login: vi.fn().mockResolvedValue({ id: '1', username: 'admin@example.com', revision: 1 }),
    }
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/login', component: LoginView },
      { path: '/', name: 'platform', component: { template: '<main>工作台</main>' } },
    ] })
    return { session, router }
  }

  it('登录成功后提交账号密码并导航至平台首页', async () => {
    const { session, router } = setup()
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router], provide: { session } } })

    await wrapper.get('input[name="username"]').setValue('admin@example.com')
    await wrapper.get('input[name="password"]').setValue('password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(session.login).toHaveBeenCalledWith('admin@example.com', 'password')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('登录失败展示统一错误且不暴露账号是否存在', async () => {
    const { session, router } = setup()
    session.login.mockRejectedValue({ message: '用户名或密码错误' })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router], provide: { session } } })

    await wrapper.get('input[name="username"]').setValue('unknown@example.com')
    await wrapper.get('input[name="password"]').setValue('bad')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('用户名或密码错误')
    expect(wrapper.text()).not.toContain('用户不存在')
  })
})

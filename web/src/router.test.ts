import { describe, expect, it } from 'vitest'
import { ref } from 'vue'

import { createAppRouter } from './router'

describe('认证路由守卫', () => {
  it('未恢复或未登录访问平台时跳转登录页', async () => {
    const session = { user: ref(null), ready: ref(true), restore: async () => undefined }
    const router = createAppRouter(session)

    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
  })

  it('已登录访问登录页时跳转平台首页', async () => {
    const session = {
      user: ref({ id: '1', username: 'admin@example.com', revision: 1 }),
      ready: ref(true),
      restore: async () => undefined,
    }
    const router = createAppRouter(session)

    await router.push('/login')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('platform')
  })
})

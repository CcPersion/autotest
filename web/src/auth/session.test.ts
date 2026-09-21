import { describe, expect, it, vi } from 'vitest'

import { createSession } from './session'

describe('Session 状态', () => {
  it('启动时用 /me 恢复已有登录状态', async () => {
    const api = {
      me: vi.fn().mockResolvedValue({ id: '1', username: 'admin@example.com', revision: 1 }),
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
    }
    const session = createSession(api)

    await session.restore()

    expect(api.me).toHaveBeenCalledOnce()
    expect(session.user.value?.username).toBe('admin@example.com')
    expect(session.ready.value).toBe(true)
  })

  it('无效 Session 恢复为匿名而不把 401 当作页面错误', async () => {
    const api = {
      me: vi.fn().mockRejectedValue({ status: 401 }),
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
    }
    const session = createSession(api)

    await session.restore()

    expect(session.user.value).toBeNull()
    expect(session.ready.value).toBe(true)
  })

  it('退出和改密成功后清空本地 Session', async () => {
    const api = {
      me: vi.fn(),
      login: vi.fn().mockResolvedValue({ id: '1', username: 'admin@example.com', revision: 1 }),
      logout: vi.fn().mockResolvedValue(undefined),
      changePassword: vi.fn().mockResolvedValue(undefined),
    }
    const session = createSession(api)
    await session.login('admin@example.com', 'password')
    await session.logout()
    expect(session.user.value).toBeNull()

    await session.login('admin@example.com', 'password')
    await session.changePassword('password', 'new-password')
    expect(session.user.value).toBeNull()
  })
})

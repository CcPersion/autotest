import { inject, provide, ref, type InjectionKey, type Ref } from 'vue'

import { ApiError } from '../api/http'
import { sessionApi, type SessionApi, type User } from '../api/session'

export interface Session {
  user: Ref<User | null>
  ready: Ref<boolean>
  restore(): Promise<void>
  login(username: string, password: string): Promise<User>
  logout(): Promise<void>
  changePassword(currentPassword: string, newPassword: string): Promise<void>
}

export const SESSION_KEY: InjectionKey<Session> = 'session' as unknown as InjectionKey<Session>

export function createSession(api: SessionApi = sessionApi): Session {
  const user = ref<User | null>(null)
  const ready = ref(false)
  let restorePromise: Promise<void> | undefined

  async function restore(): Promise<void> {
    if (restorePromise) return restorePromise
    restorePromise = (async () => {
      try {
        user.value = await api.me()
      } catch (error) {
        if (!(error instanceof ApiError) && (error as { status?: number })?.status !== 401) {
          // 当前页面仍按匿名处理，后续导航会落到登录页；真实错误由登录请求展示。
        }
        user.value = null
      } finally {
        ready.value = true
      }
    })()
    return restorePromise
  }

  async function login(username: string, password: string): Promise<User> {
    const loggedIn = await api.login(username, password)
    user.value = loggedIn
    ready.value = true
    return loggedIn
  }

  async function logout(): Promise<void> {
    await api.logout()
    user.value = null
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await api.changePassword(currentPassword, newPassword)
    user.value = null
  }

  return { user, ready, restore, login, logout, changePassword }
}

export function provideSession(session: Session): void {
  provide(SESSION_KEY, session)
}

export function useSession(): Session {
  const session = inject(SESSION_KEY)
  if (!session) throw new Error('Session 尚未注入')
  return session
}

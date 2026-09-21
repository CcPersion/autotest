import { createRouter, createWebHistory, createMemoryHistory, type RouterHistory } from 'vue-router'

import LoginView from './views/LoginView.vue'
import PlatformShellView from './views/PlatformShellView.vue'
import type { Session } from './auth/session'

export function createAppRouter(
  session: Pick<Session, 'user' | 'ready' | 'restore'>,
  history?: RouterHistory,
) {
  const router = createRouter({
    history: history || (typeof window === 'undefined' ? createMemoryHistory() : createWebHistory()),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/', name: 'platform', component: PlatformShellView, meta: { requiresAuth: true } },
      { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
  })

  router.beforeEach(async (to) => {
    if (!session.ready.value) await session.restore()
    if (to.meta.requiresAuth && !session.user.value) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (to.name === 'login' && session.user.value) return { name: 'platform' }
    return true
  })

  return router
}

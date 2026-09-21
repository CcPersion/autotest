import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
import { createSession, SESSION_KEY } from './auth/session'
import { createAppRouter } from './router'

const session = createSession()
const router = createAppRouter(session)

async function bootstrap() {
  await session.restore()
  const app = createApp(App)
  app.provide(SESSION_KEY, session)
  app.use(router)
  app.mount('#app')
}

void bootstrap()

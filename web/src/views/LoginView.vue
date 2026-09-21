<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { useSession } from '../auth/session'

const router = useRouter()
const session = useSession()
const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

function messageFor(error: unknown): string {
  const message = (error as { message?: unknown })?.message
  return typeof message === 'string' && message.trim() ? message : '用户名或密码错误'
}

async function submit() {
  errorMessage.value = ''
  submitting.value = true
  try {
    await session.login(username.value, password.value)
    await router.push({ name: 'platform' })
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card" aria-labelledby="login-title">
      <div class="login-brand"><span class="brand-cut">A</span><span>ASTER 接口自动化</span></div>
      <p class="section-overline">PLATFORM SIGN IN</p>
      <h1 id="login-title">登录测试平台</h1>
      <p class="login-description">登录后管理项目、接口用例和执行报告。</p>
      <form class="login-form" @submit.prevent="submit">
        <label>
          用户名
          <input v-model="username" name="username" autocomplete="username" required autofocus />
        </label>
        <label>
          密码
          <input v-model="password" name="password" type="password" autocomplete="current-password" required />
        </label>
        <p v-if="errorMessage" class="login-error" role="alert">{{ errorMessage }}</p>
        <button class="primary-button login-submit" type="submit" :disabled="submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>

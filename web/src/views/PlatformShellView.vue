<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import AppIcon from '../components/AppIcon.vue'
import ProjectManager from '../components/ProjectManager.vue'
import ProjectSwitcher from '../components/ProjectSwitcher.vue'
import { environmentApi, type Environment } from '../api/environment'
import { fileAssetApi, type FileAsset } from '../api/fileAsset'
import type { ProjectSummary } from '../api/project'
import AiWorkbenchView from './AiWorkbenchView.vue'
import ApiStudioView from './ApiStudioView.vue'
import EnvironmentView from './EnvironmentView.vue'
import ReportView from './ReportView.vue'
import RunCenterView from './RunCenterView.vue'
import ScenarioView from './ScenarioView.vue'
import SettingsView from './SettingsView.vue'
import SuiteView from './SuiteView.vue'
import { useSession } from '../auth/session'
import { navigation, pageTitles } from '../prototype/data'
import { createPrototypeState, navigateTo, type PageKey } from '../prototype/state'

const router = useRouter()
const session = useSession()
const state = ref(createPrototypeState())
const projects = ref<ProjectSummary[]>([])
const activeProjectId = ref<string | null>(null)
const projectManagerOpen = ref(false)
const projectSwitcher = ref<{ refresh: () => Promise<void> } | null>(null)
const environments = ref<Environment[]>([])
const fileAssets = ref<FileAsset[]>([])
const selectedEnvironmentId = ref<string | null>(null)
const selectedRunId = ref<string | null>(null)
const environmentLoading = ref(false)
const environmentError = ref('')
let environmentRequest = 0
const commandOpen = ref(false)
const accountMenuOpen = ref(false)
const passwordDialogOpen = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const passwordError = ref('')
const changingPassword = ref(false)

const currentPage = computed(() => pageTitles[state.value.activePage])
const avatarLabel = computed(() => (session.user.value?.username || '管理员').slice(0, 2).toUpperCase())

function handleProjectsLoaded(items: ProjectSummary[]) {
  projects.value = items
  if (!items.some((project) => project.id === activeProjectId.value)) {
    activeProjectId.value = items[0]?.id ?? null
  }
}

function selectProject(projectId: string | null) {
  activeProjectId.value = projectId
}

async function refreshEnvironments(projectId: string | null = activeProjectId.value) {
  const requestId = ++environmentRequest
  environments.value = []
  selectedEnvironmentId.value = null
  environmentError.value = ''
  if (!projectId) return
  environmentLoading.value = true
  try {
    const items = await environmentApi.list(projectId, false)
    if (requestId !== environmentRequest) return
    environments.value = items.filter((item) => !item.archived)
    selectedEnvironmentId.value = environments.value[0]?.id ?? null
    fileAssets.value = await fileAssetApi.list(projectId)
  } catch (error) {
    if (requestId === environmentRequest) environmentError.value = (error as { message?: string })?.message || '环境加载失败'
  } finally {
    if (requestId === environmentRequest) environmentLoading.value = false
  }
}

async function refreshProjectSwitcher() {
  await projectSwitcher.value?.refresh()
}

function navigate(page: PageKey) {
  state.value = navigateTo(state.value, page)
}

function handleNavigate(page: string) {
  navigate(page as PageKey)
}

function handleRunCreated(runId: string) {
  selectedRunId.value = runId
}

async function logout() {
  accountMenuOpen.value = false
  await session.logout()
  await router.push({ name: 'login' })
}

function openPasswordDialog() {
  accountMenuOpen.value = false
  passwordError.value = ''
  currentPassword.value = ''
  newPassword.value = ''
  passwordDialogOpen.value = true
}

async function changePassword() {
  passwordError.value = ''
  changingPassword.value = true
  try {
    await session.changePassword(currentPassword.value, newPassword.value)
    passwordDialogOpen.value = false
    await router.push({ name: 'login' })
  } catch (error) {
    passwordError.value = (error as { message?: string })?.message || '密码修改失败'
  } finally {
    changingPassword.value = false
  }
}

watch(activeProjectId, (projectId) => { void refreshEnvironments(projectId) }, { immediate: true })
</script>

<template>
  <div class="app-frame">
    <aside class="app-rail">
      <button class="brand-mark" aria-label="Aster 测试平台" @click="navigate('ai')">
        <span class="brand-cut">A</span>
      </button>

      <nav class="rail-nav" aria-label="主导航">
        <button
          v-for="item in navigation"
          :key="item.key"
          class="rail-item"
          :class="{ active: state.activePage === item.key }"
          :aria-label="item.label"
          :title="item.label"
          @click="navigate(item.key)"
        >
          <AppIcon :name="item.icon" :size="19" />
          <span class="rail-label">{{ item.label }}</span>
        </button>
      </nav>

      <div class="rail-footer">
        <button class="rail-item" aria-label="消息通知" title="消息通知">
          <AppIcon name="bell" :size="19" />
          <span class="rail-dot"></span>
        </button>
        <div class="account-menu-wrap">
          <button
            class="user-avatar"
            title="当前用户"
            aria-label="账户菜单"
            aria-haspopup="menu"
            :aria-expanded="accountMenuOpen"
            @click="accountMenuOpen = !accountMenuOpen"
          >{{ avatarLabel }}</button>
          <div v-if="accountMenuOpen" class="account-menu" role="menu">
            <strong>{{ session.user.value?.username }}</strong>
            <button role="menuitem" @click="openPasswordDialog">修改密码</button>
            <button role="menuitem" @click="logout">退出登录</button>
          </div>
        </div>
      </div>
    </aside>

    <section class="app-body">
      <header class="topbar">
        <div class="context-group">
          <div class="product-wordmark">
            <strong>ASTER</strong>
            <span>接口自动化</span>
          </div>
          <span class="top-divider"></span>
          <ProjectSwitcher
            ref="projectSwitcher"
            :model-value="activeProjectId"
            @update:model-value="selectProject"
            @loaded="handleProjectsLoaded"
            @create="projectManagerOpen = true"
            @manage="projectManagerOpen = true"
          />
          <label class="context-select environment-select" data-testid="environment-switcher">
            <span class="status-light"></span>
            <select v-model="selectedEnvironmentId" aria-label="当前环境" :disabled="environmentLoading || !activeProjectId">
              <option v-if="!environments.length" value="">{{ environmentLoading ? '加载中…' : '暂无活动环境' }}</option>
              <option v-for="item in environments" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
            <span v-if="environmentError" class="environment-switcher-error" role="alert">{{ environmentError }}</span>
          </label>
        </div>

        <div class="top-actions">
          <button class="command-trigger" @click="commandOpen = true">
            <AppIcon name="search" :size="16" />
            <span>搜索接口、场景、运行记录</span>
            <kbd>⌘ K</kbd>
          </button>
          <span class="runner-state"><i></i> Runner 在线</span>
          <button class="primary-button compact" @click="navigate('scenario')">
            <AppIcon name="play" :size="15" />
            快速运行
          </button>
        </div>
      </header>

      <main class="workspace">
        <div class="workspace-heading">
          <div>
            <span class="eyebrow">{{ currentPage.eyebrow }}</span>
            <h1>{{ currentPage.title }}</h1>
          </div>
          <span v-if="['apis', 'cases', 'environments'].includes(state.activePage)" class="prototype-flag">平台数据 · 已接入</span>
          <span v-else class="prototype-flag">交互原型 · 本地数据</span>
        </div>

        <AiWorkbenchView v-if="state.activePage === 'ai'" :project-id="activeProjectId" @navigate="handleNavigate" />
        <ApiStudioView v-else-if="state.activePage === 'apis'" mode="api" :project-id="activeProjectId" :environment-id="selectedEnvironmentId" :file-assets="fileAssets" @navigate="handleNavigate" @run-created="handleRunCreated" />
        <ApiStudioView v-else-if="state.activePage === 'cases'" mode="case" :project-id="activeProjectId" @navigate="handleNavigate" />
        <ScenarioView v-else-if="state.activePage === 'scenario'" :project-id="activeProjectId" :environment-id="selectedEnvironmentId" @navigate="handleNavigate" @run-created="handleRunCreated" />
        <SuiteView v-else-if="state.activePage === 'suites'" :project-id="activeProjectId" :environment-id="selectedEnvironmentId" @navigate="handleNavigate" @run-created="handleRunCreated" />
        <EnvironmentView v-else-if="state.activePage === 'environments'" :project-id="activeProjectId" @changed="refreshEnvironments" />
        <RunCenterView v-else-if="state.activePage === 'runs'" :project-id="activeProjectId" :environment-id="selectedEnvironmentId" @navigate="handleNavigate" @run-created="handleRunCreated" />
        <ReportView v-else-if="state.activePage === 'report'" :project-id="activeProjectId" :run-id="selectedRunId" />
        <SettingsView v-else-if="state.activePage === 'settings'" />
      </main>
    </section>

    <div v-if="commandOpen" class="modal-backdrop" @click.self="commandOpen = false">
      <section class="command-panel" role="dialog" aria-label="全局搜索">
        <div class="command-input">
          <AppIcon name="search" :size="19" />
          <input autofocus placeholder="输入接口、场景或运行编号…" />
          <kbd>ESC</kbd>
        </div>
        <div class="command-results">
          <span class="command-caption">最近访问</span>
          <button @click="navigate('scenario'); commandOpen = false">
            <AppIcon name="flow" />
            <span><strong>合同签署核心链路</strong><small>场景自动化</small></span>
            <AppIcon name="arrow" :size="15" />
          </button>
          <button @click="navigate('apis'); commandOpen = false">
            <AppIcon name="api" />
            <span><strong>创建个人签署订单</strong><small>POST /orders/person</small></span>
            <AppIcon name="arrow" :size="15" />
          </button>
          <button @click="navigate('report'); commandOpen = false">
            <AppIcon name="report" />
            <span><strong>RUN-240908-030</strong><small>订单支付回归 · 1 个失败</small></span>
            <AppIcon name="arrow" :size="15" />
          </button>
        </div>
      </section>
    </div>

    <ProjectManager
      :open="projectManagerOpen"
      :projects="projects"
      @update:open="projectManagerOpen = $event"
      @changed="refreshProjectSwitcher"
    />

    <div v-if="passwordDialogOpen" class="modal-backdrop inner" @click.self="passwordDialogOpen = false">
      <section class="password-dialog" role="dialog" aria-labelledby="password-title">
        <h2 id="password-title">修改密码</h2>
        <form @submit.prevent="changePassword">
          <label>当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required /></label>
          <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" required /></label>
          <p v-if="passwordError" class="login-error" role="alert">{{ passwordError }}</p>
          <div class="dialog-actions">
            <button type="button" class="secondary-button" @click="passwordDialogOpen = false">取消</button>
            <button type="submit" class="primary-button" :disabled="changingPassword">{{ changingPassword ? '保存中…' : '保存并重新登录' }}</button>
          </div>
        </form>
      </section>
    </div>
  </div>
</template>

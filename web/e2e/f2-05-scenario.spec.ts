import { expect, test, type Page } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'compose-e2e-admin'

test.use({ baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173' })
test.setTimeout(120_000)

async function login(page: Page) {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
}

async function createEnvironment(page: Page, environmentName: string) {
  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-action="create-environment"]').first().click()
  await page.locator('input[name="environment-name"]').fill(environmentName)
  await page.locator('input[name="environment-base-url"]').fill('http://platform-api:8080')
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.environment-card')).toContainText(environmentName)
  await expect(page.locator('select[aria-label="当前环境"]')).toContainText(environmentName)
}

test('场景编排器保存步骤树并可重新加载', async ({ page }) => {
  const stamp = Date.now()
  const projectName = `F2-05-${stamp}`

  await login(page)
  await page.locator('button[data-action="manage-projects"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(projectName)
  await page.locator('textarea[name="project-target-allowlist"]').fill('platform-api')
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
  await page.getByRole('button', { name: '关闭项目管理' }).click()
  await createEnvironment(page, `集成环境-${stamp}`)

  await page.getByRole('button', { name: '场景自动化', exact: true }).click()
  await expect(page.locator('.scenario-shell')).toBeVisible()
  await page.locator('input[aria-label="场景名称"]').fill(`登录链路-${stamp}`)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.locator('select[aria-label="当前场景"]')).toContainText(`登录链路-${stamp}`)

  await page.getByRole('button', { name: '运行场景' }).click()
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('[data-testid="real-report"]')).toContainText('通过')
  await page.getByRole('button', { name: '场景自动化', exact: true }).click()
  await expect(page.locator('.scenario-shell')).toBeVisible()

  await page.getByRole('button', { name: '添加步骤' }).click()
  await page.getByRole('button', { name: /自定义 HTTP/ }).last().click()
  await expect(page.locator('.scenario-step').filter({ hasText: '新建自定义 HTTP' })).toHaveCount(1)
  await page.locator('input[aria-label="自定义 HTTP 路径"]').fill('/actuator/health')
  await page.locator('.scenario-step').nth(1).dragTo(page.locator('.scenario-step').nth(0))
  await expect(page.locator('.scenario-step').first()).toContainText('新建自定义 HTTP')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.locator('.scenario-step')).toHaveCount(2)

  await page.getByRole('button', { name: '运行场景' }).click()
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('[data-testid="real-report"]')).toContainText('通过')
})

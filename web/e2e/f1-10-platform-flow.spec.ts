import { expect, test, type Page } from '@playwright/test'
import { writeFileSync } from 'node:fs'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'compose-e2e-admin'
const targetBaseUrl = process.env.F1_10_TARGET_BASE_URL || 'http://platform-api:8080'
const targetHost = process.env.F1_10_TARGET_HOST || 'platform-api'
const sensitiveSentinel = 'f1-10-sensitive-sentinel'

test.use({ baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173' })
test.setTimeout(180_000)

async function login(page: Page) {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
}

test('真实浏览器完成创建接口用例、运行和报告查看', async ({ page }) => {
  const stamp = Date.now()
  const projectName = `F1-10-${stamp}`
  const environmentName = `Compose-${stamp}`
  const definitionName = `健康检查-${stamp}`
  const caseName = `状态码200-${stamp}`

  await login(page)
  await page.locator('button[data-action="manage-projects"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(projectName)
  await page.locator('textarea[name="project-target-allowlist"]').fill(targetHost)
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
  await page.getByRole('button', { name: '关闭项目管理' }).click()

  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-action="create-environment"]').first().click()
  await page.locator('input[name="environment-name"]').fill(environmentName)
  await page.locator('input[name="environment-base-url"]').fill(targetBaseUrl)
  await page.locator('textarea[name="environment-variables"]').fill('{}')
  await page.locator('textarea[name="environment-request-options"]').fill(JSON.stringify({
    defaultHeaders: [{ name: 'X-Flow', value: 'compose-preview', enabled: true }],
  }, null, 2))
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.environment-list')).toContainText(environmentName)

  await page.getByRole('button', { name: '接口管理' }).click()
  await page.locator('button[data-action="create-definition"]').click()
  await page.locator('input[name="definition-name"]').fill(definitionName)
  await page.locator('select[name="definition-method"]').selectOption('GET')
  await page.locator('input[name="definition-url"]').fill('/health')
  await page.locator('button[data-action="save-definition"]').click()
  await expect(page.locator('.endpoint-tree')).toContainText(definitionName)

  const directRun = page.waitForResponse((response) => response.url().includes('/api/v1/projects/')
    && response.url().endsWith('/runs') && response.request().method() === 'POST')
  await page.locator('button[data-action="send-request"]').click()
  expect((await directRun).ok()).toBeTruthy()
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible({ timeout: 120_000 })
  await expect(page.locator('[data-testid="real-report"]')).toContainText('PASSED')
  await page.getByRole('button', { name: '接口用例' }).click()
  await page.locator('[data-definition-id]').filter({ hasText: definitionName }).first().click()
  await page.locator('button[data-action="create-case"]').click()
  await page.locator('input[name="case-name"]').fill(caseName)
  await page.locator('textarea[name="case-spec"]').fill(JSON.stringify({
    pathParams: {}, query: {}, headers: {}, body: { type: 'NONE' },
  }, null, 2))
  await page.locator('button[data-tab="提取器"]').click()
  await page.locator('button[data-action="add-extractor"]').click()
  await page.locator('input[name="extractor-expression-0"]').fill('$.ok')
  await page.locator('input[name="extractor-variable-0"]').fill('healthStatus')
  await page.locator('button[data-tab="断言"]').click()
  await page.locator('button[data-action="add-assertion"]').click()
  await page.locator('select[name="assertion-type-0"]').selectOption('STATUS')
  await page.locator('input[name="assertion-expected-0"]').fill('200')
  await page.locator('button[data-action="add-assertion"]').click()
  await page.locator('select[name="assertion-type-1"]').selectOption('JSON_PATH')
  await page.locator('input[name="assertion-expression-1"]').fill('$.ok')
  await page.locator('select[name="assertion-operator-1"]').selectOption('EQUALS')
  await page.locator('input[name="assertion-expected-1"]').fill('true')
  await page.locator('button[data-action="save-case"]').click()
  await expect(page.locator('.endpoint-case-list')).toContainText(caseName)

  await page.getByRole('button', { name: '运行中心' }).click()
  await expect(page.locator('[data-testid="run-center"]')).toBeVisible()
  await page.locator('button[data-action="preview-request"]').click()
  await expect(page.locator('[data-testid="request-preview"]')).toBeVisible()
  await expect(page.locator('[data-testid="request-preview"]')).toContainText(`${targetBaseUrl}/health`)
  await expect(page.locator('[data-testid="request-preview"]')).toContainText('X-Flow: compose-preview')
  await page.locator('button[data-action="run-case"]').click()
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible({ timeout: 120_000 })
  const runId = await page.locator('[data-testid="real-report"]').getAttribute('data-run-id')
  if (process.env.F1_10_RUN_META_FILE && runId) {
    const projectId = await page.locator('select[aria-label="当前项目"] option:checked').getAttribute('value')
    writeFileSync(process.env.F1_10_RUN_META_FILE, JSON.stringify({ projectId, runId }), 'utf8')
  }
  await expect(page.locator('[data-testid="real-report"]')).toContainText('PASSED')
  await expect(page.locator('[data-testid="real-report"]')).toContainText('步骤证据')
  await page.getByRole('button', { name: '响应', exact: true }).click()
  await expect(page.locator('.evidence-code pre')).toContainText('"ok": true')
  await expect(page.locator('.evidence-code pre')).toContainText('"token": "***"')
  await expect(page.locator('.evidence-code pre')).not.toContainText(sensitiveSentinel)
  await page.getByRole('button', { name: '断言', exact: true }).click()
  await expect(page.locator('[data-testid="assertion-evidence-row"]')).toHaveCount(2)
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('STATUS')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('PASSED')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('JSON_PATH')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('PASSED')
  await expect(page.locator('.assertion-evidence')).not.toContainText(sensitiveSentinel)
  const evidenceDirectory = process.env.F1_10_EVIDENCE_DIR
  if (evidenceDirectory) {
    await page.screenshot({ path: `${evidenceDirectory}/f1-10-report-before-restart.png`, fullPage: true })
  }
})

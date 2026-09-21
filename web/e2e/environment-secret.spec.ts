import { expect, test, type Page, type Response } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'F1-02-e2e-initial-password'
const secretSentinel = process.env.E2E_SECRET_SENTINEL || 'F104SecretLocalOnly'

test.use({ baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173' })

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
}

async function createProject(page: Page, projectName: string) {
  await page.locator('button[data-action="create-project"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(projectName)
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
  await page.getByRole('button', { name: '关闭项目管理' }).click()
}

async function assertApiResponseSafe(response: Response) {
  if (!response.url().includes('/api/v1/')) return
  const body = await response.text()
  expect(body).not.toContain(secretSentinel)
  if (!response.url().includes('/api/v1/projects/') || !response.url().includes('/secrets')) return
  expect(body).not.toContain('ciphertext')
  expect(body).not.toContain('nonce')
  const json = JSON.parse(body) as Record<string, unknown> | Array<Record<string, unknown>>
  for (const item of Array.isArray(json) ? json : [json]) {
    expect(item).not.toHaveProperty('value')
    expect(item).toMatchObject({ mask: '••••••••' })
  }
}

test('真实环境、类型变量与密钥安全闭环', async ({ page }) => {
  const projectName = `F1-04-${Date.now()}`
  const environmentName = '集成测试环境'
  const secretName = 'payment.token'
  const responseChecks: Promise<void>[] = []
  page.on('response', (response) => responseChecks.push(assertApiResponseSafe(response)))

  await login(page)
  await createProject(page, projectName)
  await page.getByRole('button', { name: '环境配置' }).click()

  await page.locator('button[data-tab="密钥"]').click()
  await page.locator('button[data-action="create-secret"]').click()
  await page.locator('input[name="secret-name"]').fill(secretName)
  await page.locator('input[name="secret-value"]').fill(secretSentinel)
  await page.locator('form[data-form="secret"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.secret-card')).toContainText(secretName)
  await expect(page.locator('.secret-card')).toContainText('••••••••')

  const typedVariables = {
    token: `\${secret:${secretName}}`, retries: 3, enabled: true, empty: null,
    nested: { region: 'cn' }, tags: ['smoke', 2],
  }
  await page.locator('button[data-tab="普通变量"]').click()
  await page.locator('button[data-action="create-environment"]').first().click()
  await page.locator('input[name="environment-name"]').fill(environmentName)
  await page.locator('input[name="environment-base-url"]').fill('https://example.test/api')
  await page.locator('textarea[name="environment-variables"]').fill(JSON.stringify(typedVariables, null, 2))
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.environment-card')).toContainText(environmentName)
  await expect(page.locator('select[aria-label="当前环境"]')).toContainText(environmentName)

  await page.reload()
  await page.getByRole('button', { name: '环境配置' }).click()
  await expect(page.locator('.environment-card')).toContainText(environmentName)
  await page.locator('button[data-action="edit-environment"]').first().click()
  const variablesText = await page.locator('textarea[name="environment-variables"]').inputValue()
  expect(JSON.parse(variablesText)).toEqual(typedVariables)
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '取消' }).click()

  await page.locator('button[data-tab="密钥"]').click()
  await page.locator('button[data-action="replace-secret"]').click()
  await page.locator('input[name="secret-value"]').fill(`${secretSentinel}Replaced`)
  await page.locator('form[data-form="secret"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('input[name="secret-value"]')).toHaveCount(0)

  await page.locator('button[data-tab="普通变量"]').click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.locator('button[data-action="archive-environment"]').click()
  await expect(page.locator('select[aria-label="当前环境"]')).toContainText('暂无活动环境')
  await page.locator('button[data-action="show-archived-environments"]').click()
  await expect(page.locator('.environment-card')).toContainText(environmentName)
  await page.locator('button[data-action="restore-environment"]').click()
  await expect(page.locator('select[aria-label="当前环境"]')).toContainText(environmentName)

  await Promise.all(responseChecks)
})

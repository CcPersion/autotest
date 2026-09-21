import { expect, test, type Page } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'compose-e2e-admin'

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

test('三条数据行按顺序隔离执行并在报告中独立展示', async ({ page }) => {
  const stamp = Date.now()
  const projectName = `F2-04-${stamp}`
  const environmentName = `Rows-${stamp}`
  const definitionName = `健康检查数据行-${stamp}`
  const caseName = `三行回归-${stamp}`

  await login(page)
  await page.locator('button[data-action="manage-projects"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(projectName)
  await page.locator('textarea[name="project-target-allowlist"]').fill('platform-api')
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
  await page.getByRole('button', { name: '关闭项目管理' }).click()

  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-action="create-environment"]').first().click()
  await page.locator('input[name="environment-name"]').fill(environmentName)
  await page.locator('input[name="environment-base-url"]').fill('http://platform-api:8080')
  await page.locator('textarea[name="environment-variables"]').fill('{}')
  await page.locator('textarea[name="environment-request-options"]').fill('{}')
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.environment-list')).toContainText(environmentName)

  await page.getByRole('button', { name: '接口管理' }).click()
  await page.locator('button[data-action="create-definition"]').click()
  await page.locator('input[name="definition-name"]').fill(definitionName)
  await page.locator('select[name="definition-method"]').selectOption('GET')
  await page.locator('input[name="definition-url"]').fill('/actuator/health')
  await page.getByRole('button', { name: 'Query' }).click()
  await page.locator('button[data-action="add-query"]').click()
  await expect(page.locator('.kv-row')).toHaveCount(1)
  await page.locator('input[placeholder="参数名"]').last().fill('row')
  await page.locator('input[placeholder="值或变量"]').last().fill('${field1}')
  await page.locator('button[data-action="save-definition"]').click()
  await expect(page.locator('.endpoint-tree')).toContainText(definitionName)

  await page.getByRole('button', { name: '接口用例' }).click()
  await page.locator('[data-definition-id]').filter({ hasText: definitionName }).first().click()
  await page.locator('button[data-action="create-case"]').click()
  await page.locator('input[name="case-name"]').fill(caseName)
  await page.locator('textarea[name="case-spec"]').fill(JSON.stringify({
    pathParams: {}, query: {}, headers: {}, cookies: {}, body: { type: 'NONE' },
  }, null, 2))
  await page.locator('button[data-tab="数据行"]').click()
  await page.locator('button[data-action="add-data-column"]').click()
  await page.locator('input[name="data-row-0-field1"]').fill('first')
  await page.locator('button[data-action="add-data-row"]').click()
  await page.locator('input[name="data-row-1-field1"]').fill('second')
  await page.locator('button[data-action="add-data-row"]').click()
  await page.locator('input[name="data-row-2-field1"]').fill('third')
  await page.locator('button[data-action="save-case"]').click()
  await expect(page.locator('.endpoint-case-list')).toContainText(caseName)

  await page.getByRole('button', { name: '运行中心' }).click()
  await page.locator('button[data-action="run-case"]').click()
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible({ timeout: 120_000 })
  await expect(page.locator('[data-testid="real-report"]')).toContainText('PASSED')
  await expect(page.locator('.report-tree > button')).toHaveCount(3)

  const expectedRows = ['first', 'second', 'third']
  const resultKeys: string[] = []
  for (const [index, value] of expectedRows.entries()) {
    await page.locator('.report-tree > button').nth(index).click()
    await expect(page.locator('.evidence-panel header h3')).toContainText(`row=${value}`)
    const resultKey = await page.locator('.evidence-panel header code').textContent()
    expect(resultKey).toContain('#')
    resultKeys.push(resultKey || '')
  }
  expect(new Set(resultKeys).size).toBe(3)
})

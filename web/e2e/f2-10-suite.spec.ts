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

async function createProject(page: Page, name: string) {
  await page.locator('button[data-action="manage-projects"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(name)
  await page.locator('textarea[name="project-target-allowlist"]').fill('platform-api')
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(name)
  await page.getByRole('button', { name: '关闭项目管理' }).click()
}

async function createEnvironment(page: Page, name: string) {
  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-action="create-environment"]').first().click()
  await page.locator('input[name="environment-name"]').fill(name)
  await page.locator('input[name="environment-base-url"]').fill('http://platform-api:8080')
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.environment-list')).toContainText(name)
  await expect(page.locator('select[aria-label="当前环境"]')).toContainText(name)
}

async function createApiCase(page: Page, definitionName: string, caseName: string) {
  await page.getByRole('button', { name: '接口管理' }).click()
  await page.locator('button[data-action="create-definition"]').click()
  await page.locator('input[name="definition-name"]').fill(definitionName)
  await page.locator('select[name="definition-method"]').selectOption('GET')
  await page.locator('input[name="definition-url"]').fill('/actuator/health')
  await page.locator('button[data-action="save-definition"]').click()
  await expect(page.locator('.endpoint-tree')).toContainText(definitionName)

  await page.getByRole('button', { name: '接口用例' }).click()
  await page.locator('[data-definition-id]').filter({ hasText: definitionName }).first().click()
  await page.locator('button[data-action="create-case"]').click()
  await page.locator('input[name="case-name"]').fill(caseName)
  await page.locator('textarea[name="case-spec"]').fill(JSON.stringify({
    pathParams: {}, query: {}, headers: {}, body: { type: 'NONE' },
  }, null, 2))
  await page.locator('button[data-action="save-case"]').click()
  await expect(page.locator('.endpoint-case-list')).toContainText(caseName)
}

async function createScenarioMember(page: Page, scenarioName: string, caseName: string) {
  await page.getByRole('button', { name: '场景自动化', exact: true }).click()
  await expect(page.locator('.scenario-shell')).toBeVisible()
  await expect(page.locator('.scenario-state')).toContainText('已接入')
  await page.locator('select[aria-label="当前场景"]').selectOption('')
  const nameInput = page.locator('input[aria-label="场景名称"]')
  await nameInput.fill(scenarioName)
  await page.getByRole('button', { name: '添加步骤' }).click()
  await page.getByRole('button', { name: /引用接口用例/ }).last().click()
  await expect(page.locator('.scenario-step')).toHaveCount(2)

  const caseSelect = page.locator('select[aria-label="引用接口用例"]')
  const caseOption = caseSelect.locator('option').filter({ hasText: caseName }).first()
  await expect(caseOption).toHaveCount(1)
  await caseSelect.selectOption((await caseOption.getAttribute('value')) || '')
  const saveResponse = page.waitForResponse((response) => response.url().includes('/scenarios') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const saved = await saveResponse
  if (!saved.ok()) throw new Error(`场景保存失败：${saved.status()} ${await saved.text()}`)
  const savedBody = await saved.json() as { id?: string; name?: string }
  if (!savedBody.id || savedBody.name !== scenarioName) throw new Error(`场景保存响应异常：${JSON.stringify(savedBody)}`)
  await expect(page.locator('select[aria-label="当前场景"]')).toContainText(scenarioName)
}

test('真实浏览器可创建集合、选择接口用例并提交集合运行', async ({ page }) => {
  const stamp = Date.now()
  const projectName = `F2-10-${stamp}`
  const environmentName = `集合环境-${stamp}`
  const definitionName = `集合健康检查-${stamp}`
  const caseName = `集合状态码-${stamp}`
  const scenarioName = `集合业务场景-${stamp}`

  await login(page)
  await createProject(page, projectName)
  await createEnvironment(page, environmentName)
  await createApiCase(page, definitionName, caseName)
  await createScenarioMember(page, scenarioName, caseName)

  await page.getByRole('button', { name: '测试集合', exact: true }).click()
  await expect(page.locator('.suite-layout')).toBeVisible()
  await page.locator('button[aria-label="新建集合"]').click()
  await expect(page.locator('.suite-list-item')).toContainText('新集合')

  await page.locator('button[data-action="add-member"]').click()
  const picker = page.getByRole('dialog', { name: '选择集合成员' })
  await expect(picker).toBeVisible()
  const candidate = picker.locator('.candidate-row').filter({ hasText: caseName })
  await expect(candidate).toHaveCount(1)
  await candidate.getByRole('button', { name: '添加' }).click()
  const scenarioCandidate = picker.locator('.candidate-row').filter({ hasText: scenarioName })
  await expect(scenarioCandidate).toHaveCount(1)
  await scenarioCandidate.getByRole('button', { name: '添加' }).click()
  await picker.getByRole('button', { name: '完成' }).click()

  await expect(page.locator('[data-testid="suite-member"]')).toHaveCount(2)
  const memberTexts = await page.locator('[data-testid="suite-member"]').allTextContents()
  expect(memberTexts.join(' ')).toContain('用例')
  expect(memberTexts.join(' ')).toContain('场景')

  const saveResponse = page.waitForResponse((response) => response.url().includes('/test-suites/') && response.request().method() === 'PUT')
  await page.locator('button[data-action="save-suite"]').click()
  const saved = await saveResponse
  expect(saved.ok()).toBeTruthy()
  await expect(page.getByRole('status')).toContainText('测试集合已保存')

  const runResponse = page.waitForResponse((response) => response.url().includes('/test-suites/') && response.url().endsWith('/runs') && response.request().method() === 'POST')
  await page.locator('button[data-action="run-suite"]').click()
  const run = await runResponse
  expect(run.ok()).toBeTruthy()
  const runBody = await run.json() as { id: string }
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible()
  await expect(page.locator('[data-testid="real-report"]')).toHaveAttribute('data-run-id', runBody.id)
  const projectId = await page.locator('select[aria-label="当前项目"] option:checked').getAttribute('value')
  if (process.env.F1_10_RUN_META_FILE && projectId) {
    const { writeFileSync } = await import('node:fs')
    writeFileSync(process.env.F1_10_RUN_META_FILE, JSON.stringify({ projectId, runId: runBody.id }), 'utf8')
  }
  if (process.env.E2E_EXPECT_SUITE_REPORT === 'true') {
    await expect(page.locator('[data-testid="real-report"]')).toContainText('通过', { timeout: 120_000 })
    await expect(page.locator('.suite-report-members')).toContainText(caseName)
    await expect(page.locator('.suite-report-members')).toContainText(scenarioName)
  }
})

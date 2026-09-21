import { expect, test, type Page, type Response } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'F1-09-e2e-initial-password'
const sensitiveSentinel = 'f1-09-sensitive-sentinel'

test.use({ baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173' })
test.setTimeout(180_000)

function platformSave(response: Response, method: string, pathPattern: RegExp): boolean {
  const pathname = new URL(response.url()).pathname
  return response.request().method() === method && pathPattern.test(pathname)
}

async function login(page: Page) {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
}

async function createProject(page: Page, name: string) {
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText('暂无项目')
  await page.locator('button[data-action="create-project"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(name)
  await page.locator('textarea[name="project-target-allowlist"]').fill('target:8080\n172.31.90.10:8080\nmissing-target:8080')
  const saved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects$/))
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  expect((await saved).ok()).toBeTruthy()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(name)
  await page.getByRole('button', { name: '关闭项目管理' }).click()
}

async function createEnvironment(page: Page, name: string, baseUrl: string) {
  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-tab="普通变量"]').click()
  await page.locator('button[data-action="create-environment"]').first().click()
  const form = page.locator('form[data-form="environment"]')
  await form.locator('input[name="environment-name"]').fill(name)
  await form.locator('input[name="environment-base-url"]').fill(baseUrl)
  await form.locator('textarea[name="environment-variables"]').fill(JSON.stringify({
    tenant: 'demo',
    traceId: 'trace-f1-09',
    access_token: '${secret:f1_09_access_token}',
  }))
  await form.locator('textarea[name="environment-request-options"]').fill('{}')
  const saved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/environments$/))
  await form.getByRole('button', { name: '保存' }).click()
  expect((await saved).ok()).toBeTruthy()
  await expect(page.locator('select[aria-label="当前环境"]')).toContainText(name)
}

async function createSecret(page: Page) {
  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-tab="密钥"]').click()
  await page.locator('button[data-action="create-secret"]').click()
  const form = page.locator('form[data-form="secret"]')
  await form.locator('input[name="secret-name"]').fill('f1_09_access_token')
  await form.locator('input[name="secret-value"]').fill(sensitiveSentinel)
  const saved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/secrets$/))
  await form.getByRole('button', { name: '保存' }).click()
  expect((await saved).ok()).toBeTruthy()
}

async function createModule(page: Page, name: string) {
  await page.getByRole('button', { name: '接口管理' }).click()
  await expect(page.locator('[data-testid="api-studio"]')).toBeVisible()
  await page.locator('[data-action="create-root-module"]').click()
  await page.locator('input[name="new-module-name"]').fill(name)
  const saved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/modules$/))
  await page.locator('form.module-tree-form').getByRole('button', { name: '保存' }).click()
  expect((await saved).ok()).toBeTruthy()
  await page.locator('.module-tree-row').filter({ hasText: name }).click()
}

async function createDefinition(page: Page, name: string) {
  await page.locator('button[data-action="create-definition"]').click()
  await page.locator('input[name="definition-name"]').fill(name)
  await page.locator('input[name="definition-url"]').fill('/orders/{orderId}')
  await page.getByRole('button', { name: 'Path', exact: true }).click()
  await page.locator('input[placeholder="路径值或变量"]').fill('1001')
  await page.getByRole('button', { name: 'Query', exact: true }).click()
  await page.locator('button[data-action="add-query"]').click()
  const queryRows = page.locator('.kv-row')
  await queryRows.nth(0).locator('input[placeholder="参数名"]').fill('tenant')
  await queryRows.nth(0).locator('input[placeholder="值或变量"]').fill('${tenant}')
  await page.locator('button[data-action="add-query"]').click()
  await queryRows.nth(1).locator('input[placeholder="参数名"]').fill('access_token')
  await queryRows.nth(1).locator('input[placeholder="值或变量"]').fill('${access_token}')
  await page.getByRole('button', { name: 'Header', exact: true }).click()
  await page.locator('button[data-action="add-header"]').click()
  const headerRows = page.locator('.kv-row')
  await headerRows.nth(0).locator('input[placeholder="参数名"]').fill('X-Trace-Id')
  await headerRows.nth(0).locator('input[placeholder="值或变量"]').fill('${traceId}')
  await page.locator('button[data-action="add-header"]').click()
  await headerRows.nth(1).locator('input[placeholder="参数名"]').fill('Authorization')
  await headerRows.nth(1).locator('input[placeholder="值或变量"]').fill('${secret:f1_09_access_token}')
  const saved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/api-definitions$/))
  await page.locator('button[data-action="save-definition"]').click()
  expect((await saved).ok()).toBeTruthy()
  await expect(page.locator('.endpoint-tree')).toContainText(name)
}

async function waitForRun(page: Page, expectedStatus: RegExp) {
  await expect(page.locator('.report-workbench')).toBeVisible({ timeout: 120_000 })
  await expect(page.locator('.report-hero')).toContainText(expectedStatus, { timeout: 120_000 })
}

test('真实 F1-09 发送、报告证据、逐条断言、连接错误与脱敏闭环', async ({ page }) => {
  const stamp = Date.now()
  const projectName = `F1-09-${stamp}`
  const moduleName = `F1-09-模块-${stamp}`
  const definitionName = `F1-09-订单-${stamp}`

  await login(page)
  await createProject(page, projectName)
  await createSecret(page)
  await createEnvironment(page, `F1-09-可达-${stamp}`, 'http://target:8080')
  await createModule(page, moduleName)
  await createDefinition(page, definitionName)

  const sendCreated = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/debug-runs$/))
  await page.locator('button[data-action="send-request"]').click()
  const sendResponse = await sendCreated
  if (!sendResponse.ok()) throw new Error(`ApiStudio run 创建失败：HTTP ${sendResponse.status()} ${await sendResponse.text()}`)
  await waitForRun(page, /通过|PASSED/)

  await page.getByRole('button', { name: '请求', exact: true }).click()
  await expect(page.locator('.evidence-code pre')).toContainText('"method": "GET"')
  await expect(page.locator('.evidence-code pre')).toContainText('"X-Trace-Id": "trace-f1-09"')
  await expect(page.locator('.evidence-code pre')).toContainText('"Authorization": "***"')
  await expect(page.locator('.evidence-code pre')).toContainText('access_token=***')
  await expect(page.locator('.evidence-code pre')).not.toContainText(sensitiveSentinel)
  await page.getByRole('button', { name: '响应', exact: true }).click()
  await expect(page.locator('.evidence-code pre')).toContainText('"ok": true')
  await expect(page.locator('.evidence-code pre')).toContainText('"token": "***"')
  await expect(page.locator('.evidence-code pre')).toContainText('"X-F1-09-Trace": "trace-f1-09"')
  await expect(page.locator('.evidence-code pre')).toContainText('"Set-Cookie": "***"')
  await expect(page.locator('.evidence-code pre')).toContainText('"X-Api-Key": "***"')
  await expect(page.locator('.evidence-code pre')).not.toContainText(sensitiveSentinel)

  await page.getByRole('button', { name: '接口用例' }).click()
  await expect(page.locator('[data-testid="api-studio"]')).toBeVisible()
  await page.locator('[data-definition-id]').filter({ hasText: definitionName }).first().click()
  await page.locator('button[data-action="create-case"]').click()
  await page.locator('input[name="case-name"]').fill(`F1-09-含断言-${stamp}`)
  await page.locator('textarea[name="case-spec"]').fill(JSON.stringify({
    pathParams: { orderId: '1001' },
    query: { tenant: '${tenant}', access_token: '${access_token}' },
    headers: { 'X-Trace-Id': '${traceId}' },
    body: { type: 'NONE' },
  }, null, 2))
  await page.getByRole('button', { name: '断言', exact: true }).click()
  await page.locator('button[data-action="add-assertion"]').click()
  await expect(page.locator('[name="assertion-type-0"]')).toBeVisible()
  await page.locator('button[data-action="add-assertion"]').click()
  await expect(page.locator('[name="assertion-type-1"]')).toBeVisible()
  await page.locator('select[name="assertion-type-0"]').selectOption('STATUS')
  await page.locator('select[name="assertion-operator-0"]').selectOption('EQUALS')
  await page.locator('input[name="assertion-expected-0"]').fill('200')
  await page.locator('select[name="assertion-type-1"]').selectOption('JSON_PATH')
  await page.locator('input[name="assertion-expression-1"]').fill('$.ok')
  await page.locator('select[name="assertion-operator-1"]').selectOption('EQUALS')
  await page.locator('input[name="assertion-expected-1"]').fill('true')
  const caseSaved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/api-definitions\/[^/]+\/cases$/))
  await page.locator('button[data-action="save-case"]').click()
  expect((await caseSaved).ok()).toBeTruthy()

  await page.getByRole('button', { name: '运行中心' }).click()
  await expect(page.locator('[data-testid="run-center"]')).toBeVisible()
  const caseRun = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/runs$/))
  await page.locator('button[data-action="run-case"]').click()
  const caseRunResponse = await caseRun
  if (!caseRunResponse.ok()) throw new Error(`用例 run 创建失败：HTTP ${caseRunResponse.status()} ${await caseRunResponse.text()}`)
  await waitForRun(page, /通过|PASSED/)
  await page.getByRole('button', { name: '断言', exact: true }).click()
  await expect(page.locator('[data-testid="assertion-evidence-row"]')).toHaveCount(2)
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('PASSED')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('PASSED')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('实际：200')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('期望：200')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('实际：true')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('期望：true')

  await page.getByRole('button', { name: '接口用例' }).click()
  await expect(page.locator('[data-testid="api-studio"]')).toBeVisible()
  await page.locator('[data-definition-id]').filter({ hasText: definitionName }).first().click()
  const failedCaseName = `F1-09-断言失败-${stamp}`
  await page.locator('button[data-action="create-case"]').click()
  await page.locator('input[name="case-name"]').fill(failedCaseName)
  await page.locator('textarea[name="case-spec"]').fill(JSON.stringify({
    pathParams: { orderId: '1001' },
    query: { tenant: '${tenant}', access_token: '${access_token}' },
    headers: { 'X-Trace-Id': '${traceId}' },
    body: { type: 'NONE' },
  }, null, 2))
  await page.getByRole('button', { name: '断言', exact: true }).click()
  await page.locator('button[data-action="add-assertion"]').click()
  await page.locator('button[data-action="add-assertion"]').click()
  await page.locator('select[name="assertion-type-0"]').selectOption('STATUS')
  await page.locator('select[name="assertion-operator-0"]').selectOption('EQUALS')
  await page.locator('input[name="assertion-expected-0"]').fill('201')
  await page.locator('select[name="assertion-type-1"]').selectOption('JSON_PATH')
  await page.locator('input[name="assertion-expression-1"]').fill('$.ok')
  await page.locator('select[name="assertion-operator-1"]').selectOption('EQUALS')
  await page.locator('input[name="assertion-expected-1"]').fill('true')
  await page.locator('button[data-action="add-assertion"]').click()
  await page.locator('select[name="assertion-type-2"]').selectOption('JSON_PATH')
  await page.locator('input[name="assertion-expression-2"]').fill('$.token')
  await page.locator('select[name="assertion-operator-2"]').selectOption('EQUALS')
  await page.locator('input[name="assertion-expected-2"]').fill('"wrong"')
  const failedCaseSaved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/api-definitions\/[^/]+\/cases$/))
  await page.locator('button[data-action="save-case"]').click()
  expect((await failedCaseSaved).ok()).toBeTruthy()

  await page.getByRole('button', { name: '运行中心' }).click()
  await expect(page.locator('[data-testid="run-center"]')).toBeVisible()
  const caseSelect = page.locator('.run-create-panel select').nth(1)
  await expect(caseSelect).toContainText(failedCaseName)
  await caseSelect.selectOption({ label: failedCaseName })
  const failedAssertionRun = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/runs$/))
  const failedReportBodies: string[] = []
  const failedReportListener = async (response: Response) => {
    const pathname = new URL(response.url()).pathname
    if (response.ok() && response.request().method() === 'GET' && /\/report$/.test(pathname)) {
      try { failedReportBodies.push(await response.text()) } catch { /* response may be superseded during polling */ }
    }
  }
  page.on('response', failedReportListener)
  await page.locator('button[data-action="run-case"]').click()
  const failedAssertionResponse = await failedAssertionRun
  if (!failedAssertionResponse.ok()) throw new Error(`断言失败 run 创建失败：HTTP ${failedAssertionResponse.status()} ${await failedAssertionResponse.text()}`)
  await waitForRun(page, /失败|FAILED/)
  await page.waitForTimeout(300)
  page.off('response', failedReportListener)
  expect(failedReportBodies.join('\n')).not.toContain(sensitiveSentinel)
  await page.getByRole('button', { name: '断言', exact: true }).click()
  await expect(page.locator('[data-testid="assertion-evidence-row"]')).toHaveCount(3)
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('STATUS')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('FAILED')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('实际：200')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(0)).toContainText('期望：201')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('JSON_PATH')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('PASSED')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('实际：true')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(1)).toContainText('期望：true')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(2)).toContainText('JSON_PATH')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(2)).toContainText('FAILED')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(2)).toContainText('实际：***')
  await expect(page.locator('[data-testid="assertion-evidence-row"]').nth(2)).toContainText('期望：wrong')
  await expect(page.locator('.assertion-evidence')).not.toContainText(sensitiveSentinel)

  await createEnvironment(page, `F1-09-不可达-${stamp}`, 'http://missing-target:8080')
  await page.getByRole('button', { name: '接口管理' }).click()
  await page.locator('[data-definition-id]').filter({ hasText: definitionName }).first().click()
  const failedSend = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/runs$/))
  await page.locator('button[data-action="send-request"]').click()
  const failedSendResponse = await failedSend
  if (!failedSendResponse.ok()) throw new Error(`连接失败 run 创建失败：HTTP ${failedSendResponse.status()} ${await failedSendResponse.text()}`)
  await waitForRun(page, /失败|FAILED/)
  await page.getByRole('button', { name: '日志', exact: true }).click()
  await expect(page.locator('[data-testid="step-error-summary"]')).toContainText(/Non HTTP response|UnknownHost|Connection|连接|failed/i)
  await expect(page.locator('[data-testid="step-error-summary"]')).not.toContainText(sensitiveSentinel)
})

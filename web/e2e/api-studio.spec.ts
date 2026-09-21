import { expect, test, type Page, type Response } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'F1-02-e2e-initial-password'

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

function platformSave(response: Response, method: string, pathPattern: RegExp): boolean {
  const pathname = new URL(response.url()).pathname
  return response.request().method() === method &&
    pathPattern.test(pathname)
}

async function createProject(page: Page, name: string) {
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText('暂无项目')
  await page.locator('button[data-action="create-project"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(name)
  const saved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects$/))
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  expect((await saved).ok()).toBeTruthy()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(name)
  await page.getByRole('button', { name: '关闭项目管理' }).click()
}

async function createRootModule(page: Page, name: string) {
  await page.locator('[data-action="create-root-module"]').click()
  await page.locator('input[name="new-module-name"]').fill(name)
  const saved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/modules$/))
  await page.locator('form.module-tree-form').getByRole('button', { name: '保存' }).click()
  expect((await saved).ok()).toBeTruthy()
  await expect(page.locator('.module-tree')).toContainText(name)
}

function definitionButton(page: Page, name: string) {
  return page.locator('[data-definition-id]').filter({ hasText: name }).first()
}

test('真实接口定义与用例保存、刷新重载闭环', async ({ page }) => {
  const stamp = Date.now()
  const projectName = `F1-06-${stamp}`
  const moduleName = `F1-06-根模块-${stamp}`
  const definitionName = `F1-06-订单查询-${stamp}`
  const postDefinitionName = `F1-06-订单创建-${stamp}`
  const caseName = `F1-06-冒烟用例-${stamp}`
  const updatedCaseName = `${caseName}-已修改`

  await login(page)
  await createProject(page, projectName)

  await page.getByRole('button', { name: '接口管理' }).click()
  await expect(page.locator('[data-testid="api-studio"]')).toBeVisible()
  await createRootModule(page, moduleName)
  await page.locator('.module-tree-row').filter({ hasText: moduleName }).click()

  await page.locator('button[data-action="create-definition"]').click()
  await page.locator('input[name="definition-name"]').fill(definitionName)
  await page.locator('input[name="definition-url"]').fill('/orders/{orderId}')
  await page.getByRole('button', { name: 'Path', exact: true }).click()
  await page.locator('input[placeholder="路径值或变量"]').fill('1001')
  await page.getByRole('button', { name: 'Query', exact: true }).click()
  await page.locator('button[data-action="add-query"]').click()
  await page.locator('input[placeholder="参数名"]').fill('tenant')
  await page.locator('input[placeholder="值或变量"]').fill('demo')
  await page.getByRole('button', { name: 'Header', exact: true }).click()
  await page.locator('button[data-action="add-header"]').click()
  await page.locator('input[placeholder="参数名"]').fill('X-Trace-Id')
  await page.locator('input[placeholder="值或变量"]').fill('trace-1')
  await expect(page.locator('button.send-request')).toBeEnabled()

  const definitionSaved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/api-definitions$/))
  await page.locator('button[data-action="save-definition"]').click()
  const savedGet = await definitionSaved
  expect(savedGet.ok()).toBeTruthy()
  const savedGetBody = await savedGet.json()
  expect(savedGetBody.method).toBe('GET')
  expect(savedGetBody.requestSpec.pathParams).toEqual([{ name: 'orderId', value: '1001' }])
  expect(savedGetBody.requestSpec.query).toEqual([{ name: 'tenant', value: 'demo', enabled: true }])
  expect(savedGetBody.requestSpec.headers).toEqual([{ name: 'X-Trace-Id', value: 'trace-1', enabled: true }])
  expect(savedGetBody.requestSpec.body).toEqual({ type: 'NONE' })
  await expect(page.locator('.endpoint-tree')).toContainText(definitionName)

  await page.locator('button[data-action="create-definition"]').click()
  await page.locator('input[name="definition-name"]').fill(postDefinitionName)
  await page.locator('select[name="definition-method"]').selectOption('POST')
  await page.locator('input[name="definition-url"]').fill('/orders')
  await page.getByRole('button', { name: 'Body', exact: true }).click()
  await page.locator('select[name="definition-body-type"]').selectOption('JSON')
  await page.locator('textarea[name="definition-body"]').fill(JSON.stringify({ orderId: '${orderId}' }))
  const postDefinitionSaved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/api-definitions$/))
  await page.locator('button[data-action="save-definition"]').click()
  const savedPost = await postDefinitionSaved
  expect(savedPost.ok()).toBeTruthy()
  const savedPostBody = await savedPost.json()
  expect(savedPostBody.method).toBe('POST')
  expect(savedPostBody.requestSpec.body).toEqual({ type: 'JSON', value: { orderId: '${orderId}' } })
  await page.locator('select[name="definition-method"]').selectOption('GET')
  await expect(page.locator('select[name="definition-body-type"]')).toHaveValue('NONE')
  await expect(page.locator('textarea[name="definition-body"]')).toHaveCount(0)

  await page.locator('button[data-action="refresh-studio"]').click()
  await expect(page.locator('.endpoint-tree')).toContainText(definitionName)

  await page.reload()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
  await page.getByRole('button', { name: '接口管理' }).click()
  await expect(definitionButton(page, definitionName)).toBeVisible()

  await page.getByRole('button', { name: '接口用例' }).click()
  await expect(page.locator('[data-testid="api-studio"]')).toBeVisible()
  await definitionButton(page, definitionName).click()
  await expect(page.locator('.endpoint-case-list')).toContainText('暂无用例')

  await page.locator('button[data-action="create-case"]').click()
  await page.locator('input[name="case-name"]').fill(caseName)
  await page.locator('textarea[name="case-spec"]').fill(JSON.stringify({
    pathParams: { orderId: '1001' }, query: { tenant: '${tenant}' }, headers: { 'X-Trace-Id': 'trace-1' }, body: { type: 'NONE' },
  }, null, 2))
  await page.getByRole('button', { name: '用例变量', exact: true }).click()
  await page.locator('textarea[name="case-variables"]').fill(JSON.stringify({ tenant: 'demo' }, null, 2))
  await page.getByRole('button', { name: '断言', exact: true }).click()
  await page.locator('button[data-action="add-assertion"]').click()
  await page.locator('select[name="assertion-type-0"]').selectOption('STATUS')
  await page.locator('select[name="assertion-operator-0"]').selectOption('EQUALS')
  await page.locator('input[name="assertion-expected-0"]').fill('200')
  const caseSaved = page.waitForResponse((response) => platformSave(response, 'POST', /\/api\/v1\/projects\/[^/]+\/api-definitions\/[^/]+\/cases$/))
  await page.locator('button[data-action="save-case"]').click()
  const savedCase = await caseSaved
  expect(savedCase.ok()).toBeTruthy()
  const savedCaseBody = await savedCase.json()
  expect(savedCaseBody.caseSpec).toMatchObject({
    pathParams: { orderId: '1001' },
    query: { tenant: '${tenant}' },
    headers: { 'X-Trace-Id': 'trace-1' },
    body: { type: 'NONE' },
  })
  expect(savedCaseBody.variables).toEqual({ tenant: 'demo' })
  expect(savedCaseBody.assertions).toEqual([{ type: 'STATUS', operator: 'EQUALS', expected: 200 }])
  await expect(page.locator('.endpoint-case-list')).toContainText(caseName)

  await page.locator('input[name="case-name"]').fill(updatedCaseName)
  const caseUpdated = page.waitForResponse((response) => platformSave(response, 'PUT', /\/api\/v1\/projects\/[^/]+\/api-definitions\/[^/]+\/cases\/[^/]+$/))
  await page.locator('button[data-action="save-case"]').click()
  expect((await caseUpdated).ok()).toBeTruthy()
  await expect(page.locator('.endpoint-case-list')).toContainText(updatedCaseName)

  await page.reload()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
  await page.getByRole('button', { name: '接口用例' }).click()
  await definitionButton(page, definitionName).click()
  await expect(page.locator('.endpoint-case-list')).toContainText(updatedCaseName)
})

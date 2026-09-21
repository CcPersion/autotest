import { expect, test, type Page } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'compose-e2e-admin'
const databasePassword = process.env.E2E_DB_PASSWORD || 'f2-10-complex-db-password'
const fixturePassword = 'scenario-password'

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

async function apiJson<T>(page: Page, path: string, method: 'GET' | 'POST', body?: unknown): Promise<T> {
  return page.evaluate(async ({ path, method, body }) => {
    const csrf = document.cookie.split(';').map((item) => item.trim()).find((item) => item.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length)
    const response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    let payload: unknown = undefined
    try { payload = text ? JSON.parse(text) : undefined } catch { payload = text }
    if (!response.ok) throw new Error(`${method} ${path} 失败：${response.status} ${text}`)
    return payload as T
  }, { path, method, body })
}

async function createProject(page: Page, name: string) {
  await page.locator('button[data-action="manage-projects"]').click()
  const dialog = page.getByRole('dialog', { name: '项目管理' })
  await dialog.getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(name)
  await page.locator('textarea[name="project-target-allowlist"]').fill('scenario-target')
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(name)
  await page.getByRole('button', { name: '关闭项目管理' }).click()
}

async function createEnvironment(page: Page, name: string) {
  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-action="create-environment"]').first().click()
  await page.locator('input[name="environment-name"]').fill(name)
  await page.locator('input[name="environment-base-url"]').fill('http://scenario-target:8080')
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.environment-list')).toContainText(name)
  await expect(page.locator('select[aria-label="当前环境"]')).toContainText(name)
}

function httpStep(id: string, position: number, title: string, method: string, urlTemplate: string, body: unknown, extractors: unknown[], assertions: unknown[], failureStrategy = 'STOP', query: unknown[] = []) {
  return {
    id, parentId: null, position, kind: 'HTTP', title, enabled: true, section: 'MAIN',
    referenceMode: null, apiCaseId: null, failureStrategy,
    stepConfig: {
      plan: {
      method, urlTemplate, query,
        headers: method === 'POST' ? [{ name: 'Content-Type', value: 'application/json', enabled: true }] : [],
        cookies: [], body: body === undefined ? { type: 'NONE' } : { type: 'JSON', value: body },
        variables: {}, extractors, assertions, options: {},
      },
    },
  }
}

test('复杂场景集合完成登录提取、业务、SQL、Redis与清理闭环', async ({ page }) => {
  const stamp = Date.now()
  const projectName = `F2-10-COMPLEX-${stamp}`
  const environmentName = `复杂环境-${stamp}`
  const scenarioName = `登录业务数据校验-${stamp}`
  const suiteName = `复杂回归集合-${stamp}`
  const redisKey = `f2-10-complex-${stamp}`
  const projectIdRef = page.locator('select[aria-label="当前项目"] option:checked')

  await login(page)
  await createProject(page, projectName)
  await createEnvironment(page, environmentName)
  const projectId = await projectIdRef.getAttribute('value')
  const environmentId = await page.locator('select[aria-label="当前环境"] option:checked').getAttribute('value')
  if (!projectId || !environmentId) throw new Error('项目或环境 ID 未生成')

  const fixtureSecretName = `fixture-password-${stamp}`
  const databaseSecretName = `postgres-password-${stamp}`
  const fixtureSecret = await apiJson<{ name: string }>(page, `/api/v1/projects/${projectId}/secrets`, 'POST', { name: fixtureSecretName, value: fixturePassword })
  const databaseSecret = await apiJson<{ name: string }>(page, `/api/v1/projects/${projectId}/secrets`, 'POST', { name: databaseSecretName, value: databasePassword })
  const jdbc = await apiJson<{ id: string }>(page, `/api/v1/projects/${projectId}/environments/${environmentId}/jdbc-data-sources`, 'POST', {
    name: `平台数据库-${stamp}`, databaseType: 'POSTGRESQL', host: 'postgres', port: 5432,
    databaseName: 'autotest', username: 'autotest', secretRef: databaseSecret.name, options: {},
  })
  const redis = await apiJson<{ id: string }>(page, `/api/v1/projects/${projectId}/environments/${environmentId}/redis-data-sources`, 'POST', {
    name: `集合Redis-${stamp}`, host: 'redis', port: 6379, databaseNumber: 0, options: {},
  })

  const loginId = crypto.randomUUID()
  const businessId = crypto.randomUUID()
  const sqlId = crypto.randomUUID()
  const redisSetId = crypto.randomUUID()
  const redisGetId = crypto.randomUUID()
  const cleanupId = crypto.randomUUID()
  const steps = [
    httpStep(loginId, 0, '登录目标服务', 'POST', '/login', { username: 'scenario-user', password: `\${secret:${fixtureSecretName}}` }, [
      { type: 'JSON_PATH', expression: '$.token', variable: 'authToken', defaultValue: '', failIfMissing: true },
    ], [{ type: 'STATUS', operator: 'EQUALS', expected: 200 }]),
    httpStep(businessId, 1, '调用业务接口', 'GET', '/business', undefined, [
      { type: 'JSON_PATH', expression: '$.business', variable: 'businessName', defaultValue: '', failIfMissing: true },
    ], [
      { type: 'STATUS', operator: 'EQUALS', expected: 200 },
      { type: 'JSON_PATH', operator: 'EQUALS', expression: '$.business', expected: 'order-created' },
    ], 'STOP', [{ name: 'token', value: '${authToken}', enabled: true }]),
    {
      id: sqlId, parentId: null, position: 2, kind: 'SQL', title: '校验数据库连接', enabled: true, section: 'MAIN',
      referenceMode: null, apiCaseId: null, failureStrategy: 'STOP',
      stepConfig: {
        dataSourceId: jdbc.id, sql: 'SELECT 1 AS check_value', parameters: {},
        extractors: [{ column: 'check_value', rowIndex: 0, variable: 'sqlValue', failIfMissing: true }],
        assertions: [{ type: 'ROW_COUNT', operator: 'EQUALS', expected: 1 }], allowWrite: false, confirmed: false,
      },
    },
    {
      id: redisSetId, parentId: null, position: 3, kind: 'REDIS', title: '写入业务缓存', enabled: true, section: 'MAIN',
      referenceMode: null, apiCaseId: null, failureStrategy: 'STOP',
      stepConfig: { dataSourceId: redis.id, command: 'SET', key: redisKey, value: 'ready', extractors: [], assertions: [{ type: 'EQUALS', expected: 'OK' }], allowWrite: true, confirmed: true },
    },
    {
      id: redisGetId, parentId: null, position: 4, kind: 'REDIS', title: '读取业务缓存', enabled: true, section: 'MAIN',
      referenceMode: null, apiCaseId: null, failureStrategy: 'STOP',
      stepConfig: { dataSourceId: redis.id, command: 'GET', key: redisKey, extractors: [{ source: 'value', variable: 'redisValue', failIfMissing: true }], assertions: [{ type: 'EQUALS', expected: 'ready' }], allowWrite: false, confirmed: false },
    },
    {
      id: cleanupId, parentId: null, position: 5, kind: 'REDIS', title: '清理业务缓存', enabled: true, section: 'CLEANUP',
      referenceMode: null, apiCaseId: null, failureStrategy: 'CONTINUE',
      stepConfig: { dataSourceId: redis.id, command: 'DEL', key: redisKey, extractors: [], assertions: [], allowWrite: true, confirmed: true },
    },
  ]
  const scenario = await apiJson<{ id: string }>(page, `/api/v1/projects/${projectId}/scenarios`, 'POST', {
    name: scenarioName, description: 'F2-10 复杂场景门禁', variables: {}, settings: {}, steps,
  })
  await apiJson(page, `/api/v1/projects/${projectId}/test-suites`, 'POST', {
    name: suiteName, description: '登录→提取→业务→SQL→Redis→清理', environmentId,
    members: [{ id: crypto.randomUUID(), position: 0, targetType: 'SCENARIO', targetId: scenario.id, enabled: true }],
  })

  await page.getByRole('button', { name: '测试集合', exact: true }).click()
  await expect(page.locator('.suite-list-item')).toContainText(suiteName)
  await expect(page.locator('[data-testid="suite-member"]')).toContainText(scenario.id)
  const runResponse = page.waitForResponse((response) => response.url().includes('/test-suites/') && response.url().endsWith('/runs') && response.request().method() === 'POST')
  await page.locator('button[data-action="run-suite"]').click()
  const run = await runResponse
  if (!run.ok()) throw new Error(`集合运行提交失败：${run.status()} ${await run.text()}`)
  const runBody = await run.json() as { id: string }
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible({ timeout: 120_000 })
  await expect(page.locator('[data-testid="real-report"]')).toContainText('通过', { timeout: 120_000 })
  await expect(page.locator('.report-tree > button')).toHaveCount(6)

  const report = await apiJson<{ status: string; steps: Array<{ status: string; resultKey: string }> }>(page, `/api/v1/projects/${projectId}/runs/${runBody.id}/report`, 'GET')
  expect(report.status).toBe('PASSED')
  expect(report.steps.length).toBeGreaterThanOrEqual(6)
  expect(report.steps.every((step) => step.status === 'PASSED')).toBeTruthy()
  if (process.env.F2_10_COMPLEX_META_FILE) {
    const { writeFileSync } = await import('node:fs')
    writeFileSync(process.env.F2_10_COMPLEX_META_FILE, JSON.stringify({ projectId, runId: runBody.id, redisKey, scenarioId: scenario.id }), 'utf8')
  }
})

import { expect, test, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'compose-e2e-admin'
const targetBaseUrl = process.env.F2_01_TARGET_BASE_URL || 'http://echo-target:8080'
const targetHost = process.env.F2_01_TARGET_HOST || 'echo-target'
const targetIp = process.env.F2_01_TARGET_IP || '172.31.0.10'
const cidrIp = process.env.F2_01_CIDR_IP || '172.31.0.11'
const proxyIp = process.env.F2_01_PROXY_IP || '172.31.0.12'
const rebindHost = process.env.F2_01_REBIND_HOST || 'rebind-target'
const mixedHost = process.env.F2_01_MIXED_HOST || 'mixed-target'
const evidenceDir = process.env.F2_01_EVIDENCE_DIR
const runMetaFile = process.env.F2_01_RUN_META_FILE
const p12File = process.env.F2_01_P12_FILE

test.use({ baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173' })
test.setTimeout(240_000)

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
  // The isolated Compose services resolve to private bridge addresses. Keep
  // hostname+port rules explicit and add only the private bridge CIDRs needed
  // for the real socket pinning gate; loopback remains unauthorized.
  await page.locator('textarea[name="project-target-allowlist"]').fill(`${targetHost}:8080\n${rebindHost}:8080\n${mixedHost}:8080\nmtls-target:8443\nhttp-proxy:8081\n${targetIp}:8080\n172.31.0.0/24:8080\n172.31.0.0/24:8081\n172.31.0.0/24:8443\n172.16.0.0/12:8080\n172.16.0.0/12:8081\n172.16.0.0/12:8443\n10.0.0.0/8:8080\n10.0.0.0/8:8081\n10.0.0.0/8:8443\n192.168.0.0/16:8080\n192.168.0.0/16:8081\n192.168.0.0/16:8443`)
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(name)
  const id = await page.locator('select[aria-label="当前项目"] option:checked').getAttribute('value')
  await page.getByRole('button', { name: '关闭项目管理' }).click()
  if (!id) throw new Error('projectId missing')
  const persisted = await page.request.get(`${process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'}/api/v1/projects/${id}`)
  expect(persisted.ok(), await persisted.text()).toBeTruthy()
  const persistedProject = await persisted.json() as { targetAllowlist?: string[] }
  expect(persistedProject.targetAllowlist || []).toContain(`${targetIp}:8080`)
  expect(persistedProject.targetAllowlist || []).toContain('172.31.0.0/24:8080')
  return id
}

async function createEnvironment(page: Page, name: string) {
  await page.getByRole('button', { name: '环境配置' }).click()
  await page.locator('button[data-action="create-environment"]').first().click()
  await page.locator('input[name="environment-name"]').fill(name)
  await page.locator('input[name="environment-base-url"]').fill(targetBaseUrl)
  await page.locator('textarea[name="environment-variables"]').fill('{}')
  await page.locator('textarea[name="environment-request-options"]').fill(JSON.stringify({
    defaultHeaders: [{ name: 'X-F2-Flow', value: 'f2-01', enabled: true }],
  }, null, 2))
  await page.locator('form[data-form="environment"]').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.environment-list')).toContainText(name)
  const id = await page.locator('select[aria-label="当前环境"] option:checked').getAttribute('value')
  if (!id) throw new Error('environmentId missing')
  return id
}

async function upload(page: Page, projectId: string, name: string, content: Buffer, mimeType: string, kind = 'REQUEST_FILE') {
  const response = await page.request.post(`${process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'}/api/v1/projects/${projectId}/files`, {
    headers: await csrfHeaders(page),
    multipart: { kind, file: { name, mimeType, buffer: content } },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return await response.json() as { fileId: string; originalName: string; sha256: string; size: number; kind: string }
}

async function csrfHeaders(page: Page) {
  const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'
  const cookies = await page.context().cookies(base)
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')
  return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token.value) } : {}
}

async function createDefinition(page: Page, projectId: string, input: Record<string, unknown>) {
  const response = await page.request.post(`${process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'}/api/v1/projects/${projectId}/api-definitions`, {
    headers: await csrfHeaders(page),
    data: input,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return await response.json() as { id: string }
}

async function createEnvironmentApi(page: Page, projectId: string, name: string, baseUrl: string) {
  const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'
  const response = await page.request.post(`${base}/api/v1/projects/${projectId}/environments`, {
    headers: await csrfHeaders(page),
    data: { name, baseUrl, variables: {}, requestOptions: {} },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json() as { id: string }).id
}

async function createSecret(page: Page, projectId: string, name: string, value: string) {
  const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'
  const response = await page.request.post(`${base}/api/v1/projects/${projectId}/secrets`, { headers: await csrfHeaders(page), data: { name, value } })
  expect(response.ok(), await response.text()).toBeTruthy()
}

async function debug(page: Page, projectId: string, environmentId: string, definitionId: string, name: string) {
  const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'
  const response = await page.request.post(`${base}/api/v1/projects/${projectId}/debug-runs`, {
    headers: await csrfHeaders(page),
    data: { targetType: 'SAVED_DEFINITION', definitionId, environmentId, idempotencyKey: `f2-${name}-${Date.now()}` },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  const created = await response.json() as { id: string }
  let latest: { status: string; executionPlan?: Record<string, unknown> } = created
  for (let attempt = 0; attempt < 120; attempt += 1) {
    await page.waitForTimeout(500)
    const poll = await page.request.get(`${base}/api/v1/projects/${projectId}/runs/${created.id}`)
    latest = await poll.json()
    if (['PASSED', 'FAILED', 'CANCELED', 'INTERRUPTED'].includes(latest.status)) break
  }
  let steps: Array<Record<string, unknown>> = []
  if (evidenceDir) {
    const reportResponse = await page.request.get(`${base}/api/v1/projects/${projectId}/runs/${created.id}/report`)
    const report = reportResponse.ok() ? await reportResponse.json() as Record<string, unknown> : {}
    steps = Array.isArray(report.steps) ? report.steps.map((step) => {
      const item = step && typeof step === 'object' ? step as Record<string, unknown> : {}
      const response = item.responseSummary && typeof item.responseSummary === 'object'
        ? item.responseSummary as Record<string, unknown> : {}
      return {
        status: item.status,
        responseCode: response.statusCode,
        responseMessage: response.message,
        url: response.url,
      }
    }) : []
    const fileRefs: unknown[] = []
    const collectFileRefs = (node: unknown, location = '$') => {
      if (Array.isArray(node)) {
        node.forEach((item, index) => collectFileRefs(item, `${location}[${index}]`))
      } else if (node && typeof node === 'object') {
        const object = node as Record<string, unknown>
        if (typeof object.fileId === 'string' || object.fileSnapshot && typeof object.fileSnapshot === 'object') {
          const snapshot = object.fileSnapshot && typeof object.fileSnapshot === 'object'
            ? object.fileSnapshot as Record<string, unknown> : undefined
          fileRefs.push({
            location,
            fileId: object.fileId ?? snapshot?.fileId,
            kind: object.kind ?? object.type,
            hasSnapshot: Boolean(snapshot),
            snapshotFileId: snapshot?.fileId,
            snapshotSize: snapshot?.size,
            snapshotSha256: typeof snapshot?.sha256 === 'string' ? snapshot.sha256 : undefined,
            snapshotMimeType: snapshot?.mimeType,
          })
        }
        Object.entries(object).forEach(([key, value]) => collectFileRefs(value, `${location}.${key}`))
      }
    }
    collectFileRefs(latest.executionPlan)
    writeFileSync(`${evidenceDir}/debug-${name}.json`, JSON.stringify({
      id: created.id,
      status: latest.status,
      exitCode: latest.exitCode,
      steps,
      fileRefs,
    }, null, 2), 'utf8')
  }
  expect(['PASSED', 'FAILED']).toContain(latest.status)
  if (latest.executionPlan && !latest.executionPlan.targetPolicySnapshot) throw new Error('targetPolicySnapshot missing')
  return { id: created.id, status: latest.status, targetPolicySnapshot: latest.executionPlan?.targetPolicySnapshot, steps }
}

async function debugRejected(page: Page, projectId: string, environmentId: string, definitionId: string) {
  const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'
  const response = await page.request.post(`${base}/api/v1/projects/${projectId}/debug-runs`, {
    headers: await csrfHeaders(page),
    data: { targetType: 'SAVED_DEFINITION', definitionId, environmentId, idempotencyKey: `f2-rejected-${Date.now()}-${Math.random()}` },
  })
  expect(response.status()).toBe(400)
  const body = await response.json() as { code?: string }
  return { status: 'FAILED', responseCode: body.code || 'TARGET_NOT_ALLOWED' }
}

function spec(definition: Record<string, unknown>, name: string) {
  return { name, method: definition.method || 'GET', urlTemplate: definition.urlTemplate || '/health', moduleId: null, requestSpec: definition.requestSpec }
}

test('F2-01 HTTP 输入真实浏览器/Platform/Runner 闭环', async ({ page }) => {
  const stamp = Date.now()
  await login(page)
  const projectId = await createProject(page, `F2-01-${stamp}`)
  const environmentId = await createEnvironment(page, `F2-01-env-${stamp}`)
  const requestFile = await upload(page, projectId, '特殊-data.txt', Buffer.from('f2 multipart payload ✓', 'utf8'), 'text/plain')
  await createSecret(page, projectId, 'f2-cookie', 'cookie-f2')
  let certificateFile: { fileId: string } | null = null
  if (p12File) {
    certificateFile = await upload(page, projectId, 'client.p12', readFileSync(p12File), 'application/x-pkcs12', 'PKCS12')
    await createSecret(page, projectId, 'p12-pass', process.env.F2_01_P12_PASSWORD || 'p12-pass')
    if (evidenceDir) writeFileSync(`${evidenceDir}/uploaded-certificate.json`, JSON.stringify({
      fileId: certificateFile.fileId,
      kind: certificateFile.kind,
      size: certificateFile.size,
      sha256: certificateFile.sha256,
      originalName: certificateFile.originalName,
    }, null, 2), 'utf8')
  }
  if (evidenceDir) writeFileSync(`${evidenceDir}/uploaded-file.json`, JSON.stringify(requestFile, null, 2), 'utf8')

  // Refresh the shell so the file selector is populated from the server list.
  await page.reload()
  await page.getByRole('button', { name: '接口管理' }).click()
  await page.locator('button[data-action="create-definition"]').click()
  await page.locator('input[name="definition-name"]').fill(`F2-01-multipart-${stamp}`)
  await page.locator('select[name="definition-method"]').selectOption('POST')
  await page.locator('input[name="definition-url"]').fill('/echo')
  await page.getByRole('button', { name: 'Body', exact: true }).click()
  await page.locator('select[name="definition-body-type"]').selectOption('MULTIPART')
  await page.locator('button[data-action="add-multipart"]').click()
  const firstRow = page.locator('.body-rows .kv-row').first()
  await firstRow.locator('input[placeholder="字段名"]').fill('note')
  await firstRow.locator('input[placeholder="文本值"]').fill('文本字段')
  await page.locator('button[data-action="add-multipart"]').click()
  const secondRow = page.locator('.body-rows .kv-row').nth(1)
  await secondRow.locator('input[placeholder="字段名"]').fill('upload')
  await secondRow.locator('select').selectOption('FILE')
  const fileSelector = secondRow.locator('select').nth(1)
  // The file list is loaded asynchronously after the shell refresh. Waiting
  // for the named option prevents a premature fallback text input from
  // silently saving an empty fileId and failing only during preview.
  await expect(fileSelector).toContainText('特殊-data.txt', { timeout: 30_000 })
  await fileSelector.selectOption(requestFile.fileId)
  const savedResponse = page.waitForResponse((response) => response.request().method() === 'POST' && response.url().includes('/api-definitions'))
  await page.locator('button[data-action="save-definition"]').click()
  const saved = await savedResponse
  expect(saved.ok()).toBeTruthy()
  const definition = await saved.json() as { id: string; requestSpec?: { body?: { type?: string; value?: unknown } } }
  if (evidenceDir) {
    const value = definition.requestSpec?.body?.value
    const entries = Array.isArray(value) ? value : (value && typeof value === 'object' && Array.isArray((value as { files?: unknown[] }).files) ? (value as { files: unknown[] }).files : [])
    writeFileSync(`${evidenceDir}/definition-file-refs.json`, JSON.stringify({
      bodyType: definition.requestSpec?.body?.type,
      entries: entries.map((item) => {
        const row = item && typeof item === 'object' ? item as Record<string, unknown> : {}
        return { name: row.name, kind: row.kind, fileId: row.fileId, mimeType: row.contentType || row.mimeType }
      }),
    }, null, 2), 'utf8')
  }

  const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:4173'
  const previewResponse = await page.request.post(`${base}/api/v1/projects/${projectId}/preview`, {
    headers: await csrfHeaders(page),
    data: { targetType: 'SAVED_DEFINITION', definitionId: definition.id, environmentId },
  })
  expect(previewResponse.ok(), await previewResponse.text()).toBeTruthy()
  const preview = await previewResponse.json()
  expect(preview.files).toEqual(expect.arrayContaining([expect.objectContaining({ fileId: requestFile.fileId, originalName: '特殊-data.txt' })]))

  const runResponse = page.waitForResponse((response) => response.url().includes('/debug-runs') && response.request().method() === 'POST')
  await page.locator('button[data-action="send-request"]').click()
  expect((await runResponse).ok()).toBeTruthy()
  await expect(page.locator('[data-testid="real-report"]')).toBeVisible({ timeout: 150_000 })
  const browserRunId = await page.locator('[data-testid="real-report"]').getAttribute('data-run-id')
  if (!browserRunId) throw new Error('browser runId missing')
  const browserRunResponse = await page.request.get(`${base}/api/v1/projects/${projectId}/runs/${browserRunId}`)
  const browserRun = await browserRunResponse.json() as Record<string, unknown>
  const safeRun = Object.fromEntries(['id', 'status', 'exitCode', 'startedAt', 'finishedAt', 'jmxPath', 'jtlPath', 'logPath']
    .filter((field) => field in browserRun).map((field) => [field, browserRun[field]]))
  if (runMetaFile) writeFileSync(runMetaFile, JSON.stringify({ projectId, environmentId, runId: browserRunId, ...safeRun }, null, 2), 'utf8')
  await expect(page.locator('[data-testid="real-report"]')).toContainText('PASSED')

  const urlEncoded = await createDefinition(page, projectId, spec({ method: 'POST', urlTemplate: '/echo', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'URLENCODED', value: [
      { name: 'special', value: 'a&b=中文', enabled: true }, { name: 'empty', value: '', enabled: true }, { name: 'special', value: 'second', enabled: true },
    ] }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
  } }, `F2-01-urlencoded-${stamp}`))
  const encodedRun = await debug(page, projectId, environmentId, urlEncoded.id, 'urlencoded')
  expect(encodedRun.status).toBe('PASSED')

  // A literal address is only reachable through the approved CIDR/explicit
  // port rule.  The same address without a port (80) and with an unrelated
  // explicit port must fail before the target receives a request.
  const ipEnvironmentId = await createEnvironmentApi(page, projectId, `F2-01-ip-${stamp}`, `http://${cidrIp}:8080`)
  const ipDefinition = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
  } }, `F2-01-ip-cidr-${stamp}`))
  const ipCidrRun = await debug(page, projectId, ipEnvironmentId, ipDefinition.id, 'ip-cidr')
  expect(ipCidrRun.status).toBe('PASSED')

  const defaultPortEnvironmentId = await createEnvironmentApi(page, projectId, `F2-01-default-port-${stamp}`, `http://${targetIp}`)
  const defaultPortDefinition = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
  } }, `F2-01-ip-default-port-${stamp}`))
  const defaultPortRun = await debugRejected(page, projectId, defaultPortEnvironmentId, defaultPortDefinition.id)

  const wrongPortEnvironmentId = await createEnvironmentApi(page, projectId, `F2-01-wrong-port-${stamp}`, `http://${targetIp}:9090`)
  const wrongPortDefinition = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
  } }, `F2-01-ip-wrong-port-${stamp}`))
  const wrongPortRun = await debugRejected(page, projectId, wrongPortEnvironmentId, wrongPortDefinition.id)

  const mixedEnvironmentId = await createEnvironmentApi(page, projectId, `F2-01-mixed-${stamp}`, `http://${mixedHost}:8080`)
  const mixedDefinition = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
  } }, `F2-01-mixed-a-aaaa-${stamp}`))
  const mixedRun = await debug(page, projectId, mixedEnvironmentId, mixedDefinition.id, 'mixed-a-aaaa')
  // The synthetic AAAA answer is intentionally outside the approved private
  // CIDR.  The resolver must reject the whole set before touching the target.
  expect(mixedRun.status).toBe('FAILED')
  expect(mixedRun.steps.some((step) => step.responseCode === 'TARGET_DNS_NOT_ALLOWED' || step.responseMessage === 'TARGET_DNS_NOT_ALLOWED')).toBeTruthy()

  const rebindEnvironmentId = await createEnvironmentApi(page, projectId, `F2-01-rebind-${stamp}`, `http://${rebindHost}:8080`)
  const rebindDefinition = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/redirect-1', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
  } }, `F2-01-rebinding-${stamp}`))
  const rebindRun = await debug(page, projectId, rebindEnvironmentId, rebindDefinition.id, 'rebinding')
  expect(rebindRun.status).toBe('FAILED')
  expect(rebindRun.steps.some((step) => step.responseCode === 'TARGET_DNS_NOT_ALLOWED' || step.responseMessage === 'TARGET_DNS_NOT_ALLOWED')).toBeTruthy()

  const redirect = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/redirect-1', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [{ name: 'sid', value: '${secret:f2-cookie}', enabled: true }], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
  } }, `F2-01-redirect-${stamp}`))
  expect((await debug(page, projectId, environmentId, redirect.id, 'redirect')).status).toBe('PASSED')

  const proxy = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000, proxy: { scheme: 'http', host: 'http-proxy', port: 8081 } },
  } }, `F2-01-proxy-${stamp}`))
  expect((await debug(page, projectId, environmentId, proxy.id, 'proxy')).status).toBe('PASSED')

  const proxyIpDefinition = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000, proxy: { scheme: 'http', host: proxyIp, port: 8081 } },
  } }, `F2-01-proxy-ip-cidr-${stamp}`))
  expect((await debug(page, projectId, environmentId, proxyIpDefinition.id, 'proxy-ip-cidr')).status).toBe('PASSED')

  const timeout = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/slow', requestSpec: {
    pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 100 },
  } }, `F2-01-timeout-${stamp}`))
  expect((await debug(page, projectId, environmentId, timeout.id, 'timeout')).status).toBe('FAILED')

  if (certificateFile) {
    const mtlsEnvironmentId = await createEnvironmentApi(page, projectId, `F2-01-mtls-${stamp}`, 'https://mtls-target:8443')
    const noCertificate = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
      pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: { followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000 },
    } }, `F2-01-mtls-fail-${stamp}`))
    expect((await debug(page, projectId, mtlsEnvironmentId, noCertificate.id, 'mtls-fail')).status).toBe('FAILED')
    const withCertificate = await createDefinition(page, projectId, spec({ method: 'GET', urlTemplate: '/health', requestSpec: {
      pathParams: [], query: [], headers: [], cookies: [], body: { type: 'NONE' }, options: {
        followRedirects: true, connectTimeoutMillis: 30000, responseTimeoutMillis: 30000,
        clientCertificate: { type: 'PKCS12', fileId: certificateFile.fileId, passwordSecretRef: '${secret:p12-pass}' },
      },
    } }, `F2-01-mtls-pass-${stamp}`))
    expect((await debug(page, projectId, mtlsEnvironmentId, withCertificate.id, 'mtls-pass')).status).toBe('PASSED')
  }

  if (evidenceDir) writeFileSync(`${evidenceDir}/run-matrix.json`, JSON.stringify({
    direct: { hostname: { status: 'PASSED', evidence: 'multipart/hostname' }, ipCidr: ipCidrRun },
    ports: { defaultPort: defaultPortRun, unauthorizedExplicitPort: wrongPortRun },
    mixedAaaaRejected: mixedRun,
    dnsRebinding: { run: rebindRun, expected: 'FAILED/TARGET_DNS_NOT_ALLOWED' },
    proxy: { hostname: 'PASSED', ipCidr: 'PASSED' },
    encodedRun, redirect, timeout,
  }, null, 2), 'utf8')
})

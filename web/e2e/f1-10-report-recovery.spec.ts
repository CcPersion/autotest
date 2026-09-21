import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'compose-e2e-admin'
const sensitiveSentinel = 'f1-10-sensitive-sentinel'

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

test('重启后重新登录并在浏览器中查看同一 runId 报告', async ({ page }) => {
  const metadataPath = process.env.F1_10_RUN_META_FILE
  if (!metadataPath) throw new Error('缺少 F1_10_RUN_META_FILE')
  const metadata = JSON.parse(readFileSync(metadataPath, 'utf8')) as { projectId: string; runId: string }
  expect(metadata.projectId).toBeTruthy()
  expect(metadata.runId).toBeTruthy()

  await login(page)
  const reportPage = await page.goto(`/api/v1/projects/${metadata.projectId}/runs/${metadata.runId}/report`)
  expect(reportPage?.ok()).toBeTruthy()
  const reportText = await page.locator('body').innerText()
  const report = JSON.parse(reportText) as {
    runId: string
    status: string
    steps: Array<{ responseSummary?: unknown; assertions?: Array<{ passed?: boolean }> }>
  }
  expect(report.runId).toBe(metadata.runId)
  expect(report.status).toBe('PASSED')
  expect(report.steps.length).toBeGreaterThan(0)
  expect(report.steps[0].responseSummary).toBeTruthy()
  expect(report.steps[0].assertions?.some((assertion) => assertion.passed === true)).toBeTruthy()
  expect(reportText).not.toContain(sensitiveSentinel)

  const evidenceDirectory = process.env.F1_10_EVIDENCE_DIR
  if (evidenceDirectory) {
    await page.screenshot({ path: `${evidenceDirectory}/f1-10-report-after-restart.png`, fullPage: true })
  }
})

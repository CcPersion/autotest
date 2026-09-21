import { expect, test } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const initialPassword = process.env.E2E_ADMIN_PASSWORD || 'F1-02-e2e-initial-password'
const changedPassword = process.env.E2E_CHANGED_PASSWORD || 'F1-02-e2e-changed-password'

test.use({ baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173' })

test('完成真实 Session 登录、刷新、退出与改密闭环', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)

  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill('wrong-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('alert')).toHaveText('用户名或密码错误')
  await expect(page.getByText('用户不存在')).toHaveCount(0)

  await page.getByLabel('密码').fill(initialPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()

  await page.reload()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()

  await page.getByRole('button', { name: '账户菜单' }).click()
  await page.getByRole('menuitem', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login/)
  const anonymousMe = await page.evaluate(async () => (await fetch('/api/v1/auth/me')).status)
  expect(anonymousMe).toBe(401)

  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(initialPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()

  await page.getByRole('button', { name: '账户菜单' }).click()
  await page.getByRole('menuitem', { name: '修改密码' }).click()
  await page.getByRole('dialog', { name: '修改密码' }).getByLabel('当前密码').fill(initialPassword)
  await page.getByRole('dialog', { name: '修改密码' }).getByLabel('新密码').fill(changedPassword)
  await page.getByRole('dialog', { name: '修改密码' }).getByRole('button', { name: '保存并重新登录' }).click()
  await expect(page).toHaveURL(/\/login/)

  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(initialPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('alert')).toHaveText('用户名或密码错误')
  await page.getByLabel('密码').fill(changedPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
})

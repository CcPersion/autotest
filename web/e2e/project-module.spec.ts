import { expect, test, type Page } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME || 'admin@example.com'
const password = process.env.E2E_ADMIN_PASSWORD || 'F1-02-e2e-initial-password'

test.use({ baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173' })

async function login(page: Page) {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: 'AI 工作台' })).toBeVisible()
}

async function createRootModule(page: Page, name: string) {
  await page.locator('[data-action="create-root-module"]').click()
  await page.locator('input[name="new-module-name"]').fill(name)
  await page.locator('form.module-tree-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.module-tree')).toContainText(name)
}

async function createChildModule(page: Page, parentName: string, name: string) {
  const parentLabel = page.locator('.module-tree-row > strong').filter({ hasText: parentName }).first()
  const parent = parentLabel.locator('xpath=ancestor::div[@data-node-id][1]')
  const parentId = await parent.getAttribute('data-node-id')
  expect(parentId).toBeTruthy()
  await parent.locator('button[data-node-action="add"]').click()
  await page.locator('input[name="new-module-name"]').fill(name)
  await page.locator('form.module-tree-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.module-tree')).toContainText(name)
  return parentId as string
}

test('真实项目与模块页面闭环', async ({ page }) => {
  const projectName = `F1-03-${Date.now()}`
  const rootA = '根A'
  const rootB = '根B'
  const child = '子模块'
  const grandchild = '孙模块'

  await login(page)

  await expect(page.locator('select[aria-label="当前项目"]')).toContainText('暂无项目')
  await page.locator('button[data-action="create-project"]').click()
  await page.getByRole('dialog', { name: '项目管理' }).getByRole('button', { name: '新建项目' }).click()
  await page.locator('input[name="project-name"]').fill(projectName)
  await page.locator('form.project-form').getByRole('button', { name: '保存' }).click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
  await page.getByRole('button', { name: '关闭项目管理' }).click()

  await page.getByRole('button', { name: '接口管理' }).click()
  await expect(page.locator('[data-testid="module-tree"]')).toBeVisible()
  await createRootModule(page, rootA)
  await createRootModule(page, rootB)
  await createChildModule(page, rootA, child)
  await createChildModule(page, child, grandchild)

  const rootBNode = page.locator('.module-tree-row > strong').filter({ hasText: /^根B$/ })
    .locator('xpath=ancestor::div[@data-node-id][1]')
  const grandchildNode = page.locator('.module-tree-row > strong').filter({ hasText: /^孙模块$/ })
    .locator('xpath=ancestor::div[@data-node-id][1]')
  const grandchildId = await grandchildNode.getAttribute('data-node-id')
  expect(grandchildId).toBeTruthy()
  await grandchildNode.dragTo(rootBNode)
  await expect(rootBNode.locator(`.module-tree-node[data-node-id="${grandchildId}"]`)).toHaveCount(1)

  await page.reload()
  await page.getByRole('button', { name: '接口管理' }).click()
  await expect(page.locator('.module-tree > .module-tree-node').nth(0)).toContainText(rootA)
  await expect(page.locator('.module-tree > .module-tree-node').nth(1)).toContainText(rootB)
  const refreshedRootB = page.locator('.module-tree-row > strong').filter({ hasText: /^根B$/ })
    .locator('xpath=ancestor::div[@data-node-id][1]')
  await expect(refreshedRootB.locator(`.module-tree-node[data-node-id="${grandchildId}"]`)).toHaveCount(1)

  page.once('dialog', (dialog) => dialog.accept())
  const refreshedRootBId = await refreshedRootB.getAttribute('data-node-id')
  await refreshedRootB.locator(`button[data-node-action="remove"][data-node-id="${refreshedRootBId}"]`).click()
  await expect(page.getByRole('alert')).toContainText('子模块')
  await expect(page.getByRole('alert')).toContainText('接口')

  await page.locator('button[data-action="manage-projects"]').click()
  const projectItem = page.locator('.project-list-item').filter({ hasText: projectName }).first()
  page.once('dialog', (dialog) => dialog.accept())
  await projectItem.locator('button[data-action="archive-project"]').click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText('暂无项目')
  await page.getByRole('button', { name: '关闭项目管理' }).click()

  await page.locator('button[data-action="manage-projects"]').click()
  await page.locator('button[data-action="show-archived"]').click()
  const archivedItem = page.locator('.project-list-item').filter({ hasText: projectName }).first()
  await archivedItem.locator('button[data-action="restore-project"]').click()
  await expect(page.locator('select[aria-label="当前项目"]')).toContainText(projectName)
})

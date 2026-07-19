import { test, expect } from '../../../frontend/test-support/playwright.mjs'
import {
  guestAuthInitScript,
  installApiMocks,
  seedLocalStorage,
} from './support/api-mocks'

test('首页加载工作台核心元素', async ({ page }) => {
  await installApiMocks(page)
  await seedLocalStorage(page, guestAuthInitScript())

  await page.goto('/learn')

  await expect(page).toHaveTitle(/智眸学伴|Visionary\s*Tutor/)
  await expect(page.getByTestId('workbench-ready')).toBeVisible()
})

test('未鉴权访问学习报告重定向至 auth', async ({ page }) => {
  await installApiMocks(page)
  await page.goto('/learning-report')

  await expect(page).toHaveURL(/\/auth/)
})

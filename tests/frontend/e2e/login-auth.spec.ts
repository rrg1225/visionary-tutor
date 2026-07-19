import { test, expect } from '../../../frontend/node_modules/@playwright/test'
import {
  guestAuthInitScript,
  installApiMocks,
  MOCK_USER,
  MOCK_USER_TOKEN,
  seedLocalStorage,
} from './support/api-mocks'

test.describe('Login & auth flow', () => {
  test('guest can sign in and persist registered session without real backend', async ({ page }) => {
    await installApiMocks(page)
    await seedLocalStorage(page, guestAuthInitScript())

    await page.goto('/auth?mode=login&redirect=/learn')

    await page.locator('.form-tabs').getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
    await page.locator('#login-username').fill('e2e_user')
    await page.locator('#login-password').fill('e2e-pass-123')
    await page.locator('form.auth-form button[type="submit"]').click()

    await page.waitForURL((url) => url.pathname === '/learn')
    await expect.poll(() => page.evaluate(() => localStorage.getItem('vt_token'))).toBe(MOCK_USER_TOKEN)

    const token = await page.evaluate(() => localStorage.getItem('vt_token'))
    const isGuest = await page.evaluate(() => localStorage.getItem('vt_is_guest'))
    const userRaw = await page.evaluate(() => localStorage.getItem('vt_user'))

    expect(token).toBe(MOCK_USER_TOKEN)
    expect(isGuest).toBe('false')
    expect(JSON.parse(userRaw || '{}').username).toBe(MOCK_USER.username)
  })

  test('invalid credentials surface form error from mocked API', async ({ page }) => {
    await installApiMocks(page, {
      'POST /auth/login': async (route) => {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: '用户名或密码错误' }),
        })
      },
    })
    await seedLocalStorage(page, guestAuthInitScript())

    await page.goto('/auth?mode=login')
    await page.locator('#login-username').fill('wrong')
    await page.locator('#login-password').fill('wrong')
    await page.locator('form.auth-form button[type="submit"]').click()

    await expect(page.locator('.form-error')).toHaveText('用户名或密码错误')
    await expect(page).toHaveURL(/\/auth/)
  })
})

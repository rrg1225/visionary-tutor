import { test, expect } from '../../../frontend/node_modules/@playwright/test'
import {
  guestAuthInitScript,
  installApiMocks,
  registeredAuthInitScript,
  seedLocalStorage,
} from './support/api-mocks'

function incompleteProfileStorage() {
  const storage = registeredAuthInitScript()
  const key = 'visionary-tutor-user-profile:user:42'
  const profile = JSON.parse(storage[key])
  storage[key] = JSON.stringify({
    ...profile,
    onboardingComplete: false,
    isComplete: false,
    onboardingAnswerCount: 0,
  })
  return storage
}

test.describe('Account and onboarding protection', () => {
  test('off-topic onboarding answer is rejected without advancing progress', async ({ page }) => {
    await installApiMocks(page, {
      'POST /profile/validate-onboarding-answer': async (route) => {
        const { answer } = route.request().postDataJSON() as { answer: string }
        const valid = answer.includes('深度学习')
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            valid,
            reason: valid ? '' : '这段回答与本轮问题关联不足，请说明正在学习的课程或近期目标。',
            aiUsed: false,
          }),
        })
      },
    })
    await seedLocalStorage(page, incompleteProfileStorage())

    await page.goto('/onboarding')
    await page.getByRole('button', { name: /分步对话建档/ }).click()
    await expect(page.locator('.progress-row')).toContainText('0/4')

    const answerInput = page.locator('.chat-input-row input')
    await answerInput.fill('今天天气很好，晚饭想吃火锅')
    await page.locator('.chat-input-row button[type="submit"]').click()

    await expect(page.getByRole('alert')).toContainText('关联不足')
    await expect(page.locator('.progress-row')).toContainText('0/4')

    await answerInput.fill('我在学习深度学习，希望能独立完成计算机视觉项目')
    await page.locator('.chat-input-row button[type="submit"]').click()
    await expect(page.locator('.progress-row')).toContainText('1/4')
  })

  test('registration keeps email optional and uses graphic captcha plus terms agreement', async ({ page }) => {
    await installApiMocks(page)
    await seedLocalStorage(page, guestAuthInitScript())

    await page.goto('/auth?mode=register')
    await page.locator('#reg-email').fill('nickname@qq.com')
    await expect(page.getByRole('alert')).toContainText('QQ 邮箱的 @qq.com 前应为 5–12 位数字')
    await page.locator('#reg-email').fill('1234567890@qq.com')
    await expect(page.getByText('QQ 邮箱的 @qq.com 前应为 5–12 位数字', { exact: true })).toHaveCount(0)
    await expect(page.locator('#reg-email-code')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '发送验证码' })).toHaveCount(0)
    await expect(page.getByRole('img', { name: /图形验证码/ })).toBeVisible()
    await expect(page.getByRole('checkbox', { name: /已阅读并同意/ })).toBeVisible()
  })

  test('forgot-password page keeps unknown-email result generic', async ({ page }) => {
    await installApiMocks(page, {
      'POST /auth/password-reset/request': async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            accepted: true,
            message: '如果该邮箱已注册，重置邮件将在几分钟内送达',
          }),
        })
      },
    })
    await seedLocalStorage(page, guestAuthInitScript())

    await page.goto('/auth?mode=reset')
    await page.locator('#reset-email').fill('unknown@example.com')
    await page.getByRole('button', { name: '发送重置邮件' }).click()

    await expect(page.getByRole('status')).toContainText('如果该邮箱已注册')
  })
})

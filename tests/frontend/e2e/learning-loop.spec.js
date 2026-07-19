import { test, expect } from '../../../frontend/test-support/playwright.mjs'
import {
  guestAuthInitScript,
  installApiMocks,
  seedLocalStorage,
} from './support/api-mocks'

test.describe('learning loop workbench', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('desktop workbench shows agent output panel and resource tab', async ({ page }) => {
    await installApiMocks(page)
    await seedLocalStorage(page, guestAuthInitScript())

    await page.goto('/learn')

    const agentPanel = page.getByRole('tablist', { name: 'Agent 产出区面板' })
    await expect(agentPanel).toBeVisible()
    await expect(agentPanel.getByRole('button', { name: '资源预览', exact: true })).toBeVisible()
  })
})

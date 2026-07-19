import { test, expect } from '../../../frontend/node_modules/@playwright/test'
import {
  guestAuthInitScript,
  installApiMocks,
  seedLocalStorage,
} from './support/api-mocks'

test.describe('RAG knowledge flow', () => {
  test('chat stream shows grounded RAG citations when knowledge base is healthy', async ({ page }) => {
    await installApiMocks(page)
    await seedLocalStorage(page, guestAuthInitScript())

    await page.goto('/learn')

    await expect(page.getByTestId('workbench-ready')).toBeVisible()
    await expect(page.getByTestId('kb-disconnected-banner')).toHaveCount(0)

    const chatInput = page.locator('.chat-input input.vt-input')
    await chatInput.fill('帮我理解 CNN 中 padding 和 stride 对特征图尺寸的影响')
    await page.getByTestId('btn-send-chat').click()

    await expect(page.locator('.message.assistant').getByText('(N-K+2P)/S+1 推导。').first()).toBeVisible()
  })

  test('degraded knowledge base shows disconnected banner without crashing UI', async ({ page }) => {
    await installApiMocks(page, {
      'GET /health': async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ status: 'UP', chromaAvailable: false }),
        })
      },
      'POST /stream/chat': async (route) => {
        const body = [
          'event: rag_context',
          `data: ${JSON.stringify({ grounded: false, ragStatus: 'UNAVAILABLE', citations: [] })}`,
          '',
          'event: content',
          'data: {"chunk":"知识库暂不可用，以下为通用辅导。"}',
          '',
          'event: complete',
          'data: {"finishReason":"stop"}',
          '',
        ].join('\n')
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
          body,
        })
      },
    })
    await seedLocalStorage(page, guestAuthInitScript())

    await page.goto('/learn')
    // Public health no longer leaks Chroma topology; degraded state becomes
    // authoritative only after this request's rag_context stream event.
    await expect(page.getByTestId('kb-disconnected-banner')).toHaveCount(0)

    const chatInput = page.locator('.chat-input input.vt-input')
    await chatInput.fill('卷积输出尺寸怎么算？')
    await page.getByTestId('btn-send-chat').click()

    await expect(page.getByTestId('kb-disconnected-banner')).toBeVisible()
    await expect(page.getByText('知识库暂不可用，以下为通用辅导。').first()).toBeVisible({ timeout: 15_000 })
  })
})

import { defineConfig, devices } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

// Use a dedicated strict port. Playwright treats some HTTP 4xx responses as
// "available", so reusing Vite's common 4173 port can attach to an unrelated service.
const port = Number(process.env.PLAYWRIGHT_PORT || 14373)
const baseURL = `http://127.0.0.1:${port}`

// Node proxy clients do not consistently support wildcard entries such as 127.*.
// Exact loopback entries keep Playwright's readiness probe away from local proxies.
const noProxy = ['127.0.0.1', 'localhost', process.env.NO_PROXY, process.env.no_proxy]
  .filter(Boolean)
  .join(',')
process.env.NO_PROXY = noProxy
process.env.no_proxy = noProxy

const msPlaywrightDir = process.env.PLAYWRIGHT_BROWSERS_PATH && process.env.PLAYWRIGHT_BROWSERS_PATH !== '0'
  ? process.env.PLAYWRIGHT_BROWSERS_PATH
  : path.join(process.env.LOCALAPPDATA || '', 'ms-playwright')

const hasBundledChromiumHeadlessShell = (() => {
  try {
    return fs.existsSync(msPlaywrightDir)
      && fs.readdirSync(msPlaywrightDir).some((entry) =>
        entry.startsWith('chromium_headless_shell-')
        && fs.existsSync(path.join(msPlaywrightDir, entry, 'chrome-headless-shell-win64', 'chrome-headless-shell.exe'))
      )
  } catch {
    return false
  }
})()

const systemChromePaths = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
const hasSystemChrome = systemChromePaths.some((chromePath) => fs.existsSync(chromePath))
const browserChannel = process.env.PLAYWRIGHT_CHANNEL || (!hasBundledChromiumHeadlessShell && hasSystemChrome ? 'chrome' : undefined)

export default defineConfig({
  testDir: '../tests/frontend/e2e',
  timeout: 60_000,
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['list']],
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...(browserChannel ? { channel: browserChannel } : {}),
      },
    },
  ],
  webServer: {
    command: `npm run preview -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 60_000,
  },
})

import {defineConfig} from '@playwright/test'
import {BASE_URL} from './src/support/config'

// Mirrors CiviForm's playwright.config.ts conventions:
// https://github.com/civiform/civiform/blob/main/browser-test/playwright.config.ts
export default defineConfig({
  timeout: 90_000, // 90s — matches CiviForm
  testDir: './src',
  testMatch: '**/*.test.ts',

  // Fail fast on committed test.only() in CI
  forbidOnly: !!process.env.CI,

  // Screenshot baselines live alongside the tests (committed to git)
  snapshotPathTemplate: './image_snapshots/{arg}{ext}',

  // Sequential — same as CiviForm (Play server isn't horizontally scaled locally)
  fullyParallel: false,
  workers: 1,

  // 1 retry in CI to handle transient flakiness
  retries: process.env.CI === 'true' ? 1 : 0,

  outputDir: './tmp/test-output',

  expect: {
    toHaveScreenshot: {
      // Allow up to 2% pixel diff before failing (font rendering varies by OS)
      maxDiffPixelRatio: 0.02,
    },
  },

  use: {
    baseURL: BASE_URL,
    // Capture full traces on first retry — valuable for CI debugging
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    // Don't use page.waitForTimeout() — use waitForSelector / waitForURL instead
    actionTimeout: 10_000,
  },

  globalSetup: './src/setup/global-setup.ts',

  projects: [
    {
      name: 'chromium',
      use: {
        browserName: 'chromium',
        viewport: {width: 1280, height: 720},
      },
    },
    {
      name: 'mobile-chrome',
      use: {
        browserName: 'chromium',
        viewport: {width: 390, height: 844}, // iPhone 14
      },
    },
  ],
})

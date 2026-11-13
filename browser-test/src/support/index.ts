/**
 * Browser test support helpers for cf-sandbox-builder.
 *
 * Mirrors CiviForm's browser-test/src/support/index.ts conventions:
 * - validateScreenshot()  → element or full-page screenshot comparison
 * - validateAccessibility() → axe-core a11y audit
 *
 * Usage:
 *   import { validateScreenshot, validateAccessibility } from '../support'
 */

import {test as base, expect, Page, request} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {
  BASE_URL,
  FIXED_CLOCK_INSTANT,
  PORTAL_EMAIL,
  PORTAL_PASSWORD,
} from './config'

/**
 * Shared Playwright test fixture that freezes the browser's `Date` clock to
 * `FIXED_CLOCK_INSTANT` (matching the server's injected `java.time.Clock`)
 * without pausing timers.
 */
export const test = base.extend<{frozenBrowserClock: void}>({
  frozenBrowserClock: [
    async ({context}, use) => {
      await context.clock.setFixedTime(new Date(FIXED_CLOCK_INSTANT))
      await use()
    },
    {auto: true},
  ],
})

function extractCsrfToken(html: string): string {
  const match = html.match(/name="csrfToken"\s+value="([^"]+)"/)
  if (!match) {
    throw new Error('Could not find csrfToken input in HTML response')
  }
  return match[1]
}

/**
 * Restores the server's in-memory sandboxes to their seeded baseline state via
 * `POST /dev/resetInMemorySandboxes`.
 */
export async function resetTestState(): Promise<void> {
  const ctx = await request.newContext({baseURL: BASE_URL})
  try {
    const loginPage = await ctx.get('/login')
    const login = await ctx.post('/login', {
      form: {
        csrfToken: extractCsrfToken(await loginPage.text()),
        email: PORTAL_EMAIL,
        password: PORTAL_PASSWORD,
      },
    })
    expect(login.ok(), 'portal login for state reset').toBeTruthy()

    const devPage = await ctx.get('/dev')
    const reset = await ctx.post('/dev/resetInMemorySandboxes', {
      form: {csrfToken: extractCsrfToken(await devPage.text())},
    })
    expect(reset.status(), 'reset in-memory sandboxes').toBe(200)
  } finally {
    await ctx.dispose()
  }
}

/**
 * Takes a screenshot of the full page (or a specific locator) and compares it
 * against the committed baseline in image_snapshots/.
 *
 * Pass a descriptive name — it becomes the snapshot filename.
 * e.g. validateScreenshot(page, 'dashboard-empty') →
 *      image_snapshots/dashboard-empty-1.png
 *
 * Mirrors CiviForm's validateScreenshot() helper.
 */
export async function validateScreenshot(
  pageOrLocator: Page | ReturnType<Page['locator']>,
  name: string,
): Promise<void> {
  await expect(pageOrLocator).toHaveScreenshot(`${name}.png`)
}

/**
 * Runs an axe-core accessibility audit on the current page state.
 * Fails the test if any violations are found.
 *
 * Mirrors CiviForm's validateAccessibility() helper.
 *
 * @param page     - Playwright page
 * @param exclude  - CSS selectors to exclude from audit (use sparingly)
 */
export async function validateAccessibility(
  page: Page,
  exclude: string[] = [],
): Promise<void> {
  const builder = new AxeBuilder({page})
  for (const selector of exclude) {
    builder.exclude(selector)
  }
  const results = await builder.analyze()
  expect(results.violations).toEqual([])
}

/**
 * Waits for the page URL to include the given path fragment.
 * Use instead of page.waitForTimeout().
 */
export async function waitForPath(page: Page, pathFragment: string): Promise<void> {
  await page.waitForURL(`**${pathFragment}**`)
}

/**
 * Logs into the portal as the shared admin account.
 * Call this at the start of any test that requires portal auth.
 */
export async function loginAsAdmin(page: Page): Promise<void> {
  await page.goto('/login')
  await page.waitForSelector('input[name="email"]')
  await page.fill('input[name="email"]', PORTAL_EMAIL)
  await page.fill('input[name="password"]', PORTAL_PASSWORD)
  await page.click('button[type="submit"]')
  // Wait until we're redirected away from /login to the dashboard (/ or /sandboxes)
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

/**
 * Fills the 2-step "Create new demo" wizard with the given values.
 * Assumes the modal is already open (call openCreateModal() first).
 */
export async function fillCreateWizard(
  page: Page,
  opts: {
    cityName: string
    subdomain: string
    pin: string
    adminEmail?: string
    expirationDays?: number
    googleAnalyticsId?: string
  },
): Promise<void> {
  // Step 1: CiviForm setup
  await page.fill('[name="cityName"]', opts.cityName)
  if (opts.expirationDays !== undefined) {
    await page.fill('[name="expirationDays"]', String(opts.expirationDays))
  }
  await page.click('button:has-text("Next")')

  // Step 2: AWS setup + submit
  await page.fill('[name="adminEmail"]', opts.adminEmail ?? 'test@example.com')
  // Subdomain may be pre-filled via JS — clear and re-type
  await page.fill('[name="subdomain"]', opts.subdomain)
  await page.fill('[name="pin"]', opts.pin)
  if (opts.googleAnalyticsId) {
    await page.fill('[name="googleAnalyticsId"]', opts.googleAnalyticsId)
  }
  await page.click('button:has-text("Provision demo")')
}

/**
 * Opens the "Create new demo" modal from the dashboard.
 */
export async function openCreateModal(page: Page): Promise<void> {
  await page.click('button:has-text("Create new demo")')
  await page.waitForSelector('#create-modal.is-visible .usa-modal__main')
}

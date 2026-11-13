/**
 * Browser tests for the Login Page (/login).
 *
 * Covers:
 * - Page structure (dark navy bg, card, form fields, stubs)
 * - Correct credentials → redirect to /sandboxes
 * - Wrong credentials → error message, email pre-filled
 * - Already-authenticated → skip login, go to /sandboxes
 * - Logout → clear session, return to /login
 *
 * Follows CiviForm conventions:
 * - test.step() for sub-steps
 * - page.waitForSelector() — never page.waitForTimeout()
 * - validateScreenshot() for visual regression
 * - validateAccessibility() for axe-core
 */

import {expect} from '@playwright/test'
import {
  test,
  validateScreenshot,
  validateAccessibility,
  loginAsAdmin,
  resetTestState,
} from '../support'
import {PORTAL_EMAIL, PORTAL_PASSWORD} from '../support/config'

test.describe('Login Page', () => {
  test.beforeAll(async () => {
    await resetTestState()
  })

  test.beforeEach(async ({page}) => {
    // Start each test unauthenticated
    await page.context().clearCookies()
    await page.goto('/login')
    await page.waitForSelector('input[name="email"]')
  })

  // ── Visual regression ─────────────────────────────────────────────────────

  test('Login page renders correctly', async ({page}) => {
    await test.step('Take full-page screenshot', async () => {
      await validateScreenshot(page, 'login-page')
    })
  })

  // ── Accessibility ─────────────────────────────────────────────────────────

  test('Login page passes accessibility audit', async ({page}) => {
    await validateAccessibility(page)
  })

  // ── Auth flows ────────────────────────────────────────────────────────────

  test('Correct credentials redirect to /sandboxes', async ({page}) => {
    await test.step('Fill correct credentials', async () => {
      await page.fill('input[name="email"]', PORTAL_EMAIL)
      await page.fill('input[name="password"]', PORTAL_PASSWORD)
    })
    await test.step('Submit form', async () => {
      await page.click('button[type="submit"]')
    })
    await test.step('Should land on dashboard', async () => {
      await page.waitForURL((url) => !url.pathname.startsWith('/login'))
      expect(new URL(page.url()).pathname).toMatch(/^\/(sandboxes)?$/)
      await expect(page.getByRole('button', {name: /create new demo/i})).toBeVisible()
    })
  })

  test('Wrong password shows error and pre-fills email', async ({page}) => {
    await test.step('Fill wrong password', async () => {
      await page.fill('input[name="email"]', PORTAL_EMAIL)
      await page.fill('input[name="password"]', 'definitely-wrong')
    })
    await test.step('Submit form', async () => {
      await page.click('button[type="submit"]')
    })
    await test.step('Error message shown', async () => {
      await page.waitForSelector('[role="alert"]')
      await expect(page.locator('[role="alert"]')).toContainText('Incorrect email or password')
    })
    await test.step('Email field pre-filled', async () => {
      await expect(page.locator('input[name="email"]')).toHaveValue(PORTAL_EMAIL)
    })
    await test.step('URL stays on /login', async () => {
      expect(page.url()).toContain('/login')
    })
  })

  test('Wrong email shows error', async ({page}) => {
    await page.fill('input[name="email"]', 'notadmin@example.com')
    await page.fill('input[name="password"]', PORTAL_PASSWORD)
    await page.click('button[type="submit"]')

    await page.waitForSelector('[role="alert"]')
    await expect(page.locator('[role="alert"]')).toContainText('Incorrect email or password')
    expect(page.url()).toContain('/login')
  })

  test('Already-authenticated user visiting /login is redirected to /sandboxes', async ({page}) => {
    // Log in first
    await loginAsAdmin(page)

    // Now navigate back to /login — should be auto-redirected
    await page.goto('/login')
    await page.waitForURL((url) => !url.pathname.startsWith('/login'))
    expect(new URL(page.url()).pathname).toMatch(/^\/(sandboxes)?$/)
    await expect(page.getByRole('button', {name: /create new demo/i})).toBeVisible()
  })

  test('Unauthenticated user visiting /sandboxes is redirected to /login', async ({page}) => {
    await page.goto('/sandboxes')
    await page.waitForURL('**/login**')
    expect(page.url()).toContain('/login')
  })

  // ── Logout flow ───────────────────────────────────────────────────────────

  test('After logout, /sandboxes redirects back to /login', async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/logout')
    await page.waitForURL('**/login**')

    // Session cleared — /sandboxes should reject them
    await page.goto('/sandboxes')
    await page.waitForURL('**/login**')
    expect(page.url()).toContain('/login')
  })
})

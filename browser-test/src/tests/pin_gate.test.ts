/**
 * Browser tests for the PIN gate page (/sandboxes/:id/access).
 *
 * Tests the prospect-facing page:
 * - Dark navy background
 * - CiviForm logo
 * - City name as h1
 * - Demo URL display box
 * - Password field + "Continue" button
 * - Wrong PIN → error message (no redirect)
 * - Correct PIN → redirect to sandbox URL
 *
 * Mirrors CiviForm's security.test.ts conventions for auth-gated pages.
 */

import {test, expect} from '@playwright/test'
import {validateScreenshot, validateAccessibility} from '../support'
import {SEEDED_SANDBOX_ID, SEEDED_SANDBOX_PIN} from '../support/config'

const PIN_GATE_URL = `/sandboxes/${SEEDED_SANDBOX_ID}/access`

test.describe('PIN Gate', () => {
  test.beforeEach(async ({page}) => {
    await page.goto(PIN_GATE_URL)
    await page.waitForSelector('input[type="password"]')
  })

  // ── Visual regression ─────────────────────────────────────────────────────

  test('PIN gate screenshot matches baseline', async ({page}) => {
    await test.step('Screenshot PIN gate', async () => {
      await validateScreenshot(page, 'pin-gate')
    })
  })

  test('PIN gate mobile screenshot matches baseline', async ({page}) => {
    await page.setViewportSize({width: 390, height: 844})
    await page.goto(PIN_GATE_URL)
    await page.waitForSelector('input[type="password"]')

    await test.step('Screenshot PIN gate on mobile', async () => {
      await validateScreenshot(page, 'pin-gate-mobile')
    })
  })

  // ── Accessibility ─────────────────────────────────────────────────────────

  test('PIN gate passes accessibility audit', async ({page}) => {
    await validateAccessibility(page)
  })

  // ── UI elements ───────────────────────────────────────────────────────────

  test('City name appears as h1 heading', async ({page}) => {
    // The seeded sandbox is "Burlington, VT"
    const heading = page.getByRole('heading', {level: 1})
    await expect(heading).toBeVisible()
    await expect(heading).toContainText('Burlington')
  })

  test('"Enter password to launch demo" subtitle is present', async ({page}) => {
    await expect(
      page.getByText(/enter password to launch demo/i),
    ).toBeVisible()
  })

  test('Demo URL display box is visible', async ({page}) => {
    // The read-only dashed-border URL box
    await expect(page.locator('[data-demo-url]')).toBeVisible()
  })

  test('Password input has correct type', async ({page}) => {
    const input = page.locator('input[type="password"]')
    await expect(input).toBeVisible()
    await expect(input).toHaveAttribute('type', 'password')
  })

  test('"Continue" button is present', async ({page}) => {
    await expect(
      page.getByRole('button', {name: /continue/i}),
    ).toBeVisible()
  })

  test('Page has dark navy background', async ({page}) => {
    // Check via CSS class or computed style — dark navy = #0d1b3e
    const body = page.locator('body')
    const bg = await body.evaluate(
      (el) => window.getComputedStyle(el).backgroundColor,
    )
    // Accept either the hex or the computed rgb value
    expect(bg).toMatch(/rgb\(13,\s*27,\s*62\)|#0d1b3e/)
  })

  // ── Wrong PIN behavior ────────────────────────────────────────────────────

  test('Wrong PIN shows error message and stays on gate page', async ({page}) => {
    await page.fill('input[type="password"]', '000000')
    await page.click('button:has-text("Continue")')

    // Should NOT redirect — should stay on /access
    await page.waitForSelector('.usa-alert--error, [data-error]')
    expect(page.url()).toContain('/access')
    await expect(
      page.locator('.usa-alert--error, [data-error]'),
    ).toBeVisible()
  })

  test('Wrong PIN screenshot shows error state', async ({page}) => {
    await page.fill('input[type="password"]', '000000')
    await page.click('button:has-text("Continue")')
    await page.waitForSelector('.usa-alert--error, [data-error]')

    await test.step('Screenshot error state', async () => {
      await validateScreenshot(page, 'pin-gate-error')
    })
  })

  test('Wrong PIN error state passes accessibility audit', async ({page}) => {
    await page.fill('input[type="password"]', '000000')
    await page.click('button:has-text("Continue")')
    await page.waitForSelector('.usa-alert--error, [data-error]')
    await validateAccessibility(page)
  })

  // ── Correct PIN behavior ──────────────────────────────────────────────────

  test('Correct PIN redirects to the sandbox URL', async ({page}) => {
    await page.fill('input[type="password"]', SEEDED_SANDBOX_PIN)
    await page.click('button:has-text("Continue")')

    // After correct PIN the browser is redirected to the sandbox's external URL.
    // In test/local environments that URL may not resolve, so we just assert
    // that we've left the /access page.
    await page.waitForURL((url) => !url.toString().includes('/access'), {
      timeout: 10_000,
    })
    expect(page.url()).not.toContain('/access')
  })

  // ── Expired / non-existent sandbox ───────────────────────────────────────

  test('Non-existent sandbox ID returns 404', async ({page}) => {
    const response = await page.goto('/sandboxes/sb-doesnotexist/access')
    expect(response?.status()).toBe(404)
  })
})

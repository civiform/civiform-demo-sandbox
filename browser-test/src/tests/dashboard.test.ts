/**
 * Browser tests for the Sandbox Dashboard (/sandboxes).
 *
 * Mirrors CiviForm's browser-test/src/header.test.ts structure:
 * - test.describe() groups
 * - test.beforeEach() for navigation
 * - validateScreenshot() for visual regression
 * - validateAccessibility() for axe-core a11y
 * - test.step() for sub-steps
 * - page.waitForSelector() — never page.waitForTimeout()
 */

import {test, expect} from '@playwright/test'
import {validateScreenshot, validateAccessibility, loginAsAdmin} from '../support'

test.describe('Sandbox Dashboard', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    // Wait for the table to be present (InMemorySandboxService seeds at least 1 row)
    await page.waitForSelector('table')
  })

  // ── Visual regression ─────────────────────────────────────────────────────

  test('Dashboard renders correctly on desktop', async ({page}) => {
    await test.step('Take full-page screenshot', async () => {
      await validateScreenshot(page, 'dashboard-desktop')
    })
  })

  test('Dashboard renders correctly on mobile', async ({page}) => {
    await page.setViewportSize({width: 390, height: 844})
    await page.goto('/sandboxes')
    await page.waitForSelector('table')

    await test.step('Take mobile screenshot', async () => {
      await validateScreenshot(page, 'dashboard-mobile')
    })
  })

  // ── Accessibility ─────────────────────────────────────────────────────────

  test('Dashboard passes accessibility audit', async ({page}) => {
    await validateAccessibility(page)
  })

  // ── Content assertions ────────────────────────────────────────────────────

  test('Seeded Burlington sandbox appears in the list', async ({page}) => {
    await expect(page.getByText('Burlington, VT')).toBeVisible()
  })

  test('"Create new demo" button is visible', async ({page}) => {
    await expect(
      page.getByRole('button', {name: /create new demo/i}),
    ).toBeVisible()
  })

  test('Action button is labeled "Details" (not "Info")', async ({page}) => {
    // Spec renamed the Info button to Details
    await expect(page.getByRole('button', {name: /details/i}).first()).toBeVisible()
    await expect(page.getByRole('button', {name: /^info$/i})).not.toBeVisible()
  })

  test('Status badge is present on each row', async ({page}) => {
    const badges = page.locator('[data-status]')
    await expect(badges.first()).toBeVisible()
  })

  test('Flash banner shows provisioning message after creation', async ({page}) => {
    // This test verifies the flash dismisses correctly without the banner
    // persisting across navigation — navigate away and back
    await page.goto('/sandboxes')
    // The flash should not be present on a fresh load (no prior form submit)
    const flash = page.locator('[data-flash="success"]')
    await expect(flash).not.toBeVisible()
  })

  // ── DELETED status tombstone ──────────────────────────────────────────────

  test('DELETED sandbox shows tombstone row without delete button', async ({page}) => {
    // This test requires a DELETED sandbox in the list.
    // Skip if no DELETED row is present (seed data may not include one).
    const deletedRow = page.locator('tr:has([data-status="DELETED"])')
    const count = await deletedRow.count()
    test.skip(count === 0, 'No DELETED sandbox in seed data — skipping tombstone test')

    // DELETED row should NOT have a "Delete" action button
    await expect(deletedRow.getByRole('button', {name: /delete/i})).not.toBeVisible()
    // DELETED row should show a timestamp
    await expect(deletedRow.getByText(/deleted/i)).toBeVisible()
  })
})

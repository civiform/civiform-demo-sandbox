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

import {expect} from '@playwright/test'
import {
  test,
  validateScreenshot,
  validateAccessibility,
  loginAsAdmin,
  resetTestState,
} from '../support'

test.describe('Sandbox Dashboard', () => {
  test.beforeAll(async () => {
    await resetTestState()
  })

  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    // Wait for the table to be present (InMemorySandboxService seeds at least 1 row)
    await page.waitForSelector('table')
  })

  // ── Visual regression ─────────────────────────────────────────────────────

  test('Dashboard renders correctly', async ({page}) => {
    await test.step('Take full-page screenshot', async () => {
      await validateScreenshot(page, 'dashboard')
    })
  })

  // ── Accessibility ─────────────────────────────────────────────────────────

  test('Dashboard passes accessibility audit', async ({page}) => {
    await validateAccessibility(page)
  })

  // ── Content assertions ────────────────────────────────────────────────────

  test('Options button opens modal with Extend, Re-provision, and Edit actions', async ({ page }) => {
    const firstRow = page.locator('tbody tr').first()
    const firstRowCity = (await firstRow.locator('td').first().innerText()).trim()
    const optionsButton = firstRow.getByRole('button', {name: /^options$/i})
    await expect(optionsButton).toBeVisible()

    await test.step('Click Options button to open Options modal', async () => {
      await optionsButton.click()
      const optionsModal = page.locator('#options-modal')
      await expect(optionsModal.locator('.usa-modal__content')).toBeVisible()
      await expect(optionsModal.getByRole('button', {name: /extend/i})).toBeVisible()
      await expect(optionsModal.getByRole('button', { name: /re-provision/i })).toBeVisible()
      await expect(optionsModal.getByRole('button', {name: /edit/i})).toBeVisible()
      await validateAccessibility(page, ['table', 'footer'])
    })

    await test.step('Click Edit option to open Edit Sandbox Settings modal', async () => {
      await page.locator('#options-modal').getByRole('button', {name: /edit/i}).click()
      const editModal = page.locator('#edit-modal')
      await expect(editModal.locator('.usa-modal__content')).toBeVisible()
      await expect(page.locator('#edit-cityName')).toHaveValue(firstRowCity)
      await expect(page.locator('#edit-expirationDate')).not.toHaveValue('')
      await expect(page.locator('#edit-civiformVersion')).not.toHaveValue('')
      await expect(page.locator('#edit-past-expiration-warning')).toBeHidden()
      await validateAccessibility(page, ['table', 'footer'])
    })

    await test.step('Entering a past expiration date shows deletion warning', async () => {
      await page.locator('#edit-expirationDate').fill('2020-01-01')
      await expect(page.locator('#edit-past-expiration-warning')).toBeVisible()
      await expect(page.locator('#edit-past-expiration-warning')).toContainText(/expired/i)
      await validateAccessibility(page, ['table', 'footer'])
    })
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
    // DELETED row should show a timestamp (e.g. "Deleted Oct 1, 2026")
    await expect(deletedRow.getByText(/deleted\s+[a-z]{3}/i)).toBeVisible()
  })
})

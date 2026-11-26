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

import {expect, Locator, Page} from '@playwright/test'
import {
  test,
  validateScreenshot,
  validateAccessibility,
  loginAsAdmin,
  resetTestState,
  tabWithinDialog,
} from '../support'

async function tabToElement(
  page: Page,
  target: Locator,
  maxTabs = 25,
): Promise<void> {
  for (let i = 0; i < maxTabs; i++) {
    if (await target.evaluate((el) => document.activeElement === el)) {
      return
    }
    await page.keyboard.press('Tab')
  }
  await expect(target).toBeFocused()
}

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

  test('Options button opens modal with Extend, Re-provision, and Edit actions', async ({
    page,
  }) => {
    const firstRow = page.locator('tbody tr').first()
    const firstRowCity = (
      await firstRow.locator('td').first().innerText()
    ).trim()
    const optionsButton = firstRow.getByRole('button', {name: /^options$/i})
    await expect(optionsButton).toBeVisible()

    await test.step('Click Options button to open Options modal', async () => {
      await optionsButton.click()
      const optionsModal = page.locator('#options-modal')
      await expect(optionsModal.locator('.usa-modal__content')).toBeVisible()
      await expect(
        optionsModal.getByRole('button', {name: /extend/i}),
      ).toBeVisible()
      await expect(
        optionsModal.getByRole('button', {name: /re-provision/i}),
      ).toBeVisible()
      await expect(
        optionsModal.getByRole('button', {name: /edit/i}),
      ).toBeVisible()
      await validateAccessibility(page, ['table', 'footer'])
    })

    await test.step('Click Edit option to open Edit Sandbox Settings modal', async () => {
      await page
        .locator('#options-modal')
        .getByRole('button', {name: /edit/i})
        .click()
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
      await expect(page.locator('#edit-past-expiration-warning')).toContainText(
        /expired/i,
      )
      await validateAccessibility(page, ['table', 'footer'])
    })
  })

  // ── Modal keyboard focus management & focus trap ─────────────────────────

  test('Details modal steals focus to close button and traps Tab traversal', async ({
    page,
  }) => {
    const firstRow = page.locator('tbody tr').first()
    const detailsButton = firstRow.getByRole('button', {name: /^details$/i})
    const infoModal = page.locator('#info-modal')
    const closeButton = infoModal.locator('.usa-modal__close')

    await test.step('Tab to Details button and press Enter to open modal', async () => {
      await tabToElement(page, detailsButton)
      await expect(detailsButton).toBeFocused()
      await page.keyboard.press('Enter')
      await expect(infoModal.locator('.usa-modal__content')).toBeVisible()
      await expect(closeButton).toBeFocused()
      await validateAccessibility(page, ['table', 'footer'])
    })

    await test.step('Tab circulates strictly within Details modal without escaping to background UI', async () => {
      const expectedCycle = [
        infoModal.locator('#info-url'),
        infoModal.getByRole('button', {name: /^copy$/i}).first(),
        infoModal.locator('#info-pin'),
        infoModal.getByRole('button', {name: /toggle password visibility/i}),
        infoModal.getByRole('button', {name: /^copy$/i}).nth(1),
        infoModal.locator('#info-ga-id'),
        infoModal.locator('#copy-all-btn'),
        closeButton,
        infoModal.locator('#info-url'),
      ]

      for (const expectedTarget of expectedCycle) {
        await tabWithinDialog(page, '#info-modal', 'Tab')
        await expect(expectedTarget).toBeFocused()
      }

      // Reverse traversal with Shift+Tab wraps back to close button and Copy All
      await tabWithinDialog(page, '#info-modal', 'Shift+Tab')
      await expect(closeButton).toBeFocused()
      await tabWithinDialog(page, '#info-modal', 'Shift+Tab')
      await expect(infoModal.locator('#copy-all-btn')).toBeFocused()

      // Background controls are natively inert and cannot steal focus while dialog is open
      await firstRow
        .getByRole('button', {name: /^options$/i})
        .evaluate((el: HTMLElement) => el.focus())
      await expect(infoModal.locator('#copy-all-btn')).toBeFocused()
    })

    await test.step('Dismissing modal via Escape and backdrop click restores focus to Details button', async () => {
      await page.keyboard.press('Escape')
      await expect(infoModal.locator('.usa-modal__content')).not.toBeVisible()
      await expect(detailsButton).toBeFocused()

      // Re-open and dismiss by clicking the dialog backdrop
      await page.keyboard.press('Enter')
      await expect(closeButton).toBeFocused()
      await page.mouse.click(5, 5)
      await expect(infoModal.locator('.usa-modal__content')).not.toBeVisible()
      await expect(detailsButton).toBeFocused()
    })
  })

  test('Options, Extend, Re-provision, Edit, and Delete modals manage and trap focus', async ({
    page,
  }) => {
    const firstRow = page.locator('tbody tr').first()
    const optionsButton = firstRow.getByRole('button', {name: /^options$/i})
    const deleteButton = firstRow.getByRole('button', {name: /delete/i})

    await test.step('Options modal focuses close button and traps Tab traversal', async () => {
      await tabToElement(page, optionsButton)
      await page.keyboard.press('Enter')
      const optionsModal = page.locator('#options-modal')
      const optionsClose = optionsModal.locator('.usa-modal__close')
      await expect(optionsClose).toBeFocused()

      const optionsCycle = [
        optionsModal.getByRole('button', {name: /^extend$/i}),
        optionsModal.getByRole('button', {name: /^re-provision$/i}),
        optionsModal.getByRole('button', {name: /^edit$/i}),
        optionsModal.getByRole('button', {name: /^cancel$/i}),
        optionsClose,
        optionsModal.getByRole('button', {name: /^extend$/i}),
      ]
      for (const target of optionsCycle) {
        await tabWithinDialog(page, '#options-modal', 'Tab')
        await expect(target).toBeFocused()
      }
    })

    await test.step('Selecting Extend moves focus to Extend modal close button and traps focus', async () => {
      // Currently focused on Extend button inside options-modal
      await page.keyboard.press('Enter')
      const extendModal = page.locator('#extend-modal')
      const extendClose = extendModal.locator('.usa-modal__close')
      await expect(extendClose).toBeFocused()

      const extendCycle = [
        extendModal.locator('#extend-days'),
        extendModal.getByRole('button', {name: /extend & re-provision/i}),
        extendModal.getByRole('button', {name: /^cancel$/i}),
        extendClose,
      ]
      for (const target of extendCycle) {
        await tabWithinDialog(page, '#extend-modal', 'Tab')
        await expect(target).toBeFocused()
      }

      // Closing chained modal restores focus to original Options button on dashboard
      await page.keyboard.press('Enter')
      await expect(extendModal.locator('.usa-modal__content')).not.toBeVisible()
      await expect(optionsButton).toBeFocused()
    })

    await test.step('Selecting Re-provision traps focus and restores focus to Options button', async () => {
      await page.keyboard.press('Enter')
      await expect(
        page.locator('#options-modal .usa-modal__close'),
      ).toBeFocused()
      await page.keyboard.press('Tab') // Extend
      await page.keyboard.press('Tab') // Re-provision
      await page.keyboard.press('Enter')

      const reprovisionModal = page.locator('#reprovision-modal')
      const reprovisionClose = reprovisionModal.locator('.usa-modal__close')
      await expect(reprovisionClose).toBeFocused()

      const reprovisionCycle = [
        reprovisionModal.getByRole('button', {name: /^re-provision demo$/i}),
        reprovisionModal.getByRole('button', {name: /^cancel$/i}),
        reprovisionClose,
      ]
      for (const target of reprovisionCycle) {
        await tabWithinDialog(page, '#reprovision-modal', 'Tab')
        await expect(target).toBeFocused()
      }

      await page.keyboard.press('Escape')
      await expect(
        reprovisionModal.locator('.usa-modal__content'),
      ).not.toBeVisible()
      await expect(optionsButton).toBeFocused()
    })

    await test.step('Selecting Edit traps focus at both boundaries and restores focus to Options button', async () => {
      await page.keyboard.press('Enter')
      await expect(
        page.locator('#options-modal .usa-modal__close'),
      ).toBeFocused()
      await page.keyboard.press('Tab') // Extend
      await page.keyboard.press('Tab') // Re-provision
      await page.keyboard.press('Tab') // Edit
      await page.keyboard.press('Enter')

      const editModal = page.locator('#edit-modal')
      const editClose = editModal.locator('.usa-modal__close')
      await expect(editClose).toBeFocused()

      // Forward from close button -> cityName
      await tabWithinDialog(page, '#edit-modal', 'Tab')
      await expect(editModal.locator('#edit-cityName')).toBeFocused()

      // Backward from cityName -> close button -> wraps to Cancel (last control)
      await tabWithinDialog(page, '#edit-modal', 'Shift+Tab')
      await expect(editClose).toBeFocused()
      await tabWithinDialog(page, '#edit-modal', 'Shift+Tab')
      await expect(
        editModal.getByRole('button', {name: /^cancel$/i}),
      ).toBeFocused()

      await page.keyboard.press('Enter')
      await expect(editModal.locator('.usa-modal__content')).not.toBeVisible()
      await expect(optionsButton).toBeFocused()
    })

    await test.step('Delete modal focuses close button, traps Tab, and restores focus to Delete button', async () => {
      await tabToElement(page, deleteButton)
      await expect(deleteButton).toBeFocused()
      await page.keyboard.press('Enter')

      const deleteModal = page.locator('#delete-modal')
      const deleteClose = deleteModal.locator('.usa-modal__close')
      await expect(deleteClose).toBeFocused()
      await validateAccessibility(page, ['table', 'footer'])

      const deleteCycle = [
        deleteModal.getByRole('button', {name: /^delete demo$/i}),
        deleteModal.getByRole('button', {name: /^cancel$/i}),
        deleteClose,
        deleteModal.getByRole('button', {name: /^delete demo$/i}),
      ]
      for (const target of deleteCycle) {
        await tabWithinDialog(page, '#delete-modal', 'Tab')
        await expect(target).toBeFocused()
      }

      await page.keyboard.press('Escape')
      await expect(deleteModal.locator('.usa-modal__content')).not.toBeVisible()
      await expect(deleteButton).toBeFocused()
    })
  })

  // ── DELETED status tombstone ──────────────────────────────────────────────

  test('DELETED sandbox shows tombstone row without delete button', async ({
    page,
  }) => {
    // This test requires a DELETED sandbox in the list.
    // Skip if no DELETED row is present (seed data may not include one).
    const deletedRow = page.locator('tr:has([data-status="DELETED"])')
    await expect(deletedRow).toHaveCount(1)
    // DELETED row should NOT have a "Delete" action button
    await expect(
      deletedRow.getByRole('button', {name: /delete/i}),
    ).not.toBeVisible()
    // DELETED row should show a timestamp (e.g. "Deleted Oct 1, 2026")
    await expect(deletedRow.getByText(/deleted\s+[a-z]{3}/i)).toBeVisible()
  })
})

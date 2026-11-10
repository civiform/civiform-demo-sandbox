/**
 * Browser tests for Dashboard Modals.
 *
 * Covers:
 * 1. Details Modal (#info-modal)
 *    - Open via Details button on Burlington row
 *    - Heading shows 'Sandbox Details: Burlington, VT'
 *    - Demo URL field is visible and readonly
 *    - Password field is visible, type=password, readonly
 *    - Toggle password visibility button switches between password and text
 *    - 'Copy All' button is visible
 *    - Close X button dismisses modal
 *    - Clicking overlay dismisses modal
 *    - Escape key dismisses modal
 *    - Accessibility audit passes
 *
 * 2. Extend Modal (#extend-modal)
 *    - Open via Options -> Extend
 *    - Heading shows 'Extend Demo:'
 *    - Days input defaults to 30, min=1, max=90
 *    - Has 'Extend Demo' submit button and 'Cancel' button
 *    - Cancel dismisses modal
 *    - Close X dismisses modal
 *    - Escape key dismisses modal
 *    - Accessibility audit passes
 *
 * 3. Delete Modal (#delete-modal)
 *    - Open via red delete button on first row
 *    - Heading shows 'Delete demo?'
 *    - Confirmation text mentions city name
 *    - Has 'Delete demo' submit button (usa-button--secondary) and 'Cancel' button
 *    - Cancel dismisses modal
 *    - Close X dismisses modal
 *    - Escape key dismisses modal
 *    - Accessibility audit passes
 *
 * 4. Reprovision Modal (#reprovision-modal)
 *    - Open via Options -> Reprovision
 *    - Heading shows 'Re-provision demo?'
 *    - Has 'Re-provision demo' submit button and 'Cancel' button
 *    - Cancel dismisses modal
 *    - Close X dismisses modal
 *    - Escape key dismisses modal
 *    - Accessibility audit passes
 */

import {test, expect, Page} from '@playwright/test'
import {validateAccessibility, loginAsAdmin} from '../support'

// ── Helpers ─────────────────────────────────────────────────────────────────

async function openDetailsModal(page: Page): Promise<void> {
  const detailsBtn = page
    .locator('tbody tr')
    .filter({hasText: 'Burlington, VT'})
    .first()
    .getByRole('button', {name: /details/i})
  await detailsBtn.click()
  await expect(page.locator('#info-modal.is-visible .usa-modal__content')).toBeVisible()
}

async function openOptionsModal(page: Page): Promise<void> {
  const optionsBtn = page
    .locator('tbody tr')
    .first()
    .getByRole('button', {name: /^options$/i})
  await optionsBtn.click()
  await expect(page.locator('#options-modal.is-visible .usa-modal__content')).toBeVisible()
}

async function openExtendModal(page: Page): Promise<void> {
  await openOptionsModal(page)
  await page.locator('#options-modal').getByRole('button', {name: /^extend$/i}).click()
  await expect(page.locator('#extend-modal.is-visible .usa-modal__content')).toBeVisible()
}

async function openDeleteModal(page: Page): Promise<void> {
  const deleteBtn = page
    .locator('tbody tr')
    .first()
    .locator('button.bg-red-600')
  await deleteBtn.click()
  await expect(page.locator('#delete-modal.is-visible .usa-modal__content')).toBeVisible()
}

async function openReprovisionModal(page: Page): Promise<void> {
  await openOptionsModal(page)
  await page.locator('#options-modal').getByRole('button', {name: /^reprovision$/i}).click()
  await expect(page.locator('#reprovision-modal.is-visible .usa-modal__content')).toBeVisible()
}

// ── 1. Details Modal ────────────────────────────────────────────────────────

test.describe('Details Modal (#info-modal)', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  test('Clicking Details button on Burlington row opens the modal', async ({page}) => {
    await test.step('Click Details on Burlington row', async () => {
      const detailsBtn = page
        .locator('tbody tr')
        .filter({hasText: 'Burlington, VT'})
        .first()
        .getByRole('button', {name: /details/i})
      await detailsBtn.click()
    })

    await test.step('Modal content is visible', async () => {
      await expect(page.locator('#info-modal.is-visible .usa-modal__content')).toBeVisible()
    })
  })

  test('Modal heading shows "Sandbox Details: Burlington, VT"', async ({page}) => {
    await openDetailsModal(page)
    await expect(page.locator('#info-modal-title')).toHaveText('Sandbox Details: Burlington, VT')
  })

  test('Demo URL field (#info-url) is visible and readonly', async ({page}) => {
    await openDetailsModal(page)
    const urlInput = page.locator('#info-url')
    await expect(urlInput).toBeVisible()
    await expect(urlInput).toHaveAttribute('readonly', '')
    await expect(urlInput).not.toHaveValue('')
  })

  test('Password field (#info-pin) is visible, type=password, and readonly', async ({page}) => {
    await openDetailsModal(page)
    const pinInput = page.locator('#info-pin')
    await expect(pinInput).toBeVisible()
    await expect(pinInput).toHaveAttribute('type', 'password')
    await expect(pinInput).toHaveAttribute('readonly', '')
    await expect(pinInput).not.toHaveValue('')
  })

  test('Toggle password visibility button toggles type between password and text', async ({page}) => {
    await openDetailsModal(page)
    const pinInput = page.locator('#info-pin')
    const toggleBtn = page.locator('#info-modal [aria-label="Toggle password visibility"]')

    await expect(pinInput).toHaveAttribute('type', 'password')

    await test.step('Click toggle to reveal password', async () => {
      await toggleBtn.click()
      await expect(pinInput).toHaveAttribute('type', 'text')
    })

    await test.step('Click toggle again to hide password', async () => {
      await toggleBtn.click()
      await expect(pinInput).toHaveAttribute('type', 'password')
    })
  })

  test('"Copy All" button (#copy-all-btn) is visible', async ({page}) => {
    await openDetailsModal(page)
    const copyAllBtn = page.locator('#copy-all-btn')
    await expect(copyAllBtn).toBeVisible()
    await expect(copyAllBtn).toHaveText(/copy all/i)
  })

  test('Close X button dismisses modal', async ({page}) => {
    await openDetailsModal(page)
    await page.locator('#info-modal [aria-label="Close this window"]').click()
    await expect(page.locator('#info-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#info-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Clicking overlay dismisses modal', async ({page}) => {
    await openDetailsModal(page)
    await page.locator('#info-modal .usa-modal-overlay').click({position: {x: 10, y: 10}})
    await expect(page.locator('#info-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#info-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Escape key dismisses modal', async ({page}) => {
    await openDetailsModal(page)
    await page.keyboard.press('Escape')
    await expect(page.locator('#info-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#info-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Accessibility audit passes', async ({page}) => {
    await openDetailsModal(page)
    await validateAccessibility(page, ['table', 'footer'])
  })
})

// ── 2. Extend Modal ─────────────────────────────────────────────────────────

test.describe('Extend Modal (#extend-modal)', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  test('Open via Options button -> Extend in options modal', async ({page}) => {
    await test.step('Open Options modal', async () => {
      await openOptionsModal(page)
    })

    await test.step('Click Extend', async () => {
      await page.locator('#options-modal').getByRole('button', {name: /^extend$/i}).click()
      await expect(page.locator('#extend-modal.is-visible .usa-modal__content')).toBeVisible()
    })
  })

  test('Modal heading shows "Extend Demo:"', async ({page}) => {
    await openExtendModal(page)
    await expect(page.locator('#extend-modal-title')).toContainText('Extend Demo:')
    await expect(page.locator('#extend-city-name')).not.toBeEmpty()
  })

  test('Days input (#extend-days) defaults to 30, min=1, max=90', async ({page}) => {
    await openExtendModal(page)
    const daysInput = page.locator('#extend-days')
    await expect(daysInput).toBeVisible()
    await expect(daysInput).toHaveValue('30')
    await expect(daysInput).toHaveAttribute('min', '1')
    await expect(daysInput).toHaveAttribute('max', '90')
  })

  test('Has "Extend Demo" submit button and "Cancel" button', async ({page}) => {
    await openExtendModal(page)
    const submitBtn = page.locator('#extend-modal button[type="submit"]')
    const cancelBtn = page.locator('#extend-modal').getByRole('button', {name: /^cancel$/i})

    await expect(submitBtn).toBeVisible()
    await expect(submitBtn).toHaveText(/extend demo/i)
    await expect(cancelBtn).toBeVisible()
  })

  test('Cancel dismisses modal', async ({page}) => {
    await openExtendModal(page)
    await page.locator('#extend-modal').getByRole('button', {name: /^cancel$/i}).click()
    await expect(page.locator('#extend-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#extend-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Close X dismisses modal', async ({page}) => {
    await openExtendModal(page)
    await page.locator('#extend-modal [aria-label="Close this window"]').click()
    await expect(page.locator('#extend-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#extend-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Escape key dismisses modal', async ({page}) => {
    await openExtendModal(page)
    await page.keyboard.press('Escape')
    await expect(page.locator('#extend-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#extend-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Accessibility audit passes', async ({page}) => {
    await openExtendModal(page)
    await validateAccessibility(page, ['table', 'footer'])
  })
})

// ── 3. Delete Modal ─────────────────────────────────────────────────────────

test.describe('Delete Modal (#delete-modal)', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  test('Clicking red delete button on first row opens delete-modal', async ({page}) => {
    await test.step('Click red delete button', async () => {
      await page.locator('tbody tr').first().locator('button.bg-red-600').click()
    })

    await test.step('Delete modal is visible', async () => {
      await expect(page.locator('#delete-modal.is-visible .usa-modal__content')).toBeVisible()
    })
  })

  test('Modal heading shows "Delete demo?"', async ({page}) => {
    await openDeleteModal(page)
    await expect(page.locator('#delete-modal-title')).toHaveText('Delete demo?')
  })

  test('Confirmation text mentions city name', async ({page}) => {
    const firstRowCity = (await page.locator('tbody tr').first().locator('td').first().innerText()).trim()
    await openDeleteModal(page)
    await expect(page.locator('#delete-city-name')).toHaveText(firstRowCity)
    await expect(page.locator('#delete-modal .usa-prose')).toContainText(firstRowCity)
  })

  test('Has "Delete demo" submit button (usa-button--secondary) and "Cancel" button', async ({page}) => {
    await openDeleteModal(page)
    const deleteBtn = page.locator('#delete-modal button[type="submit"]')
    const cancelBtn = page.locator('#delete-modal').getByRole('button', {name: /^cancel$/i})

    await expect(deleteBtn).toBeVisible()
    await expect(deleteBtn).toHaveText(/delete demo/i)
    await expect(deleteBtn).toHaveClass(/usa-button--secondary/)
    await expect(cancelBtn).toBeVisible()
  })

  test('Cancel dismisses modal', async ({page}) => {
    await openDeleteModal(page)
    await page.locator('#delete-modal').getByRole('button', {name: /^cancel$/i}).click()
    await expect(page.locator('#delete-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#delete-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Close X dismisses modal', async ({page}) => {
    await openDeleteModal(page)
    await page.locator('#delete-modal [aria-label="Close this window"]').click()
    await expect(page.locator('#delete-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#delete-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Escape key dismisses modal', async ({page}) => {
    await openDeleteModal(page)
    await page.keyboard.press('Escape')
    await expect(page.locator('#delete-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#delete-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Accessibility audit passes', async ({page}) => {
    await openDeleteModal(page)
    await validateAccessibility(page, ['table', 'footer'])
  })
})

// ── 4. Reprovision Modal ────────────────────────────────────────────────────

test.describe('Reprovision Modal (#reprovision-modal)', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  test('Open via Options button -> Reprovision in options modal', async ({page}) => {
    await test.step('Open Options modal', async () => {
      await openOptionsModal(page)
    })

    await test.step('Click Reprovision', async () => {
      await page.locator('#options-modal').getByRole('button', {name: /^reprovision$/i}).click()
      await expect(page.locator('#reprovision-modal.is-visible .usa-modal__content')).toBeVisible()
    })
  })

  test('Modal heading shows "Re-provision demo?"', async ({page}) => {
    await openReprovisionModal(page)
    await expect(page.locator('#reprovision-modal-title')).toHaveText('Re-provision demo?')
  })

  test('Confirmation text mentions city name', async ({page}) => {
    const firstRowCity = (await page.locator('tbody tr').first().locator('td').first().innerText()).trim()
    await openReprovisionModal(page)
    await expect(page.locator('#reprovision-city-name')).toHaveText(firstRowCity)
    await expect(page.locator('#reprovision-modal .usa-prose')).toContainText(firstRowCity)
  })

  test('Has "Re-provision demo" submit button and "Cancel" button', async ({page}) => {
    await openReprovisionModal(page)
    const submitBtn = page.locator('#reprovision-modal button[type="submit"]')
    const cancelBtn = page.locator('#reprovision-modal').getByRole('button', {name: /^cancel$/i})

    await expect(submitBtn).toBeVisible()
    await expect(submitBtn).toHaveText(/re-provision demo/i)
    await expect(cancelBtn).toBeVisible()
  })

  test('Cancel dismisses modal', async ({page}) => {
    await openReprovisionModal(page)
    await page.locator('#reprovision-modal').getByRole('button', {name: /^cancel$/i}).click()
    await expect(page.locator('#reprovision-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#reprovision-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Close X dismisses modal', async ({page}) => {
    await openReprovisionModal(page)
    await page.locator('#reprovision-modal [aria-label="Close this window"]').click()
    await expect(page.locator('#reprovision-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#reprovision-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Escape key dismisses modal', async ({page}) => {
    await openReprovisionModal(page)
    await page.keyboard.press('Escape')
    await expect(page.locator('#reprovision-modal')).toHaveClass(/is-hidden/)
    await expect(page.locator('#reprovision-modal.is-visible .usa-modal__content')).not.toBeVisible()
  })

  test('Accessibility audit passes', async ({page}) => {
    await openReprovisionModal(page)
    await validateAccessibility(page, ['table', 'footer'])
  })
})

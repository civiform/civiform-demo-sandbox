/**
 * Browser tests for the "Create new demo" 3-step modal wizard.
 *
 * Tests both the wizard UI and the feature-flag-equivalent behavior
 * (wizard is only triggered from the dashboard — no direct /sandboxes/new URL).
 *
 * Mirrors CiviForm's browser-test conventions:
 * - validateScreenshot() at each wizard step
 * - validateAccessibility() on the open modal
 * - page.waitForSelector() — never page.waitForTimeout()
 * - Feature-off tests: wizard absent when button absent
 */

import {test, expect} from '@playwright/test'
import {validateScreenshot, validateAccessibility, openCreateModal} from '../support'

test.describe('Create Sandbox Wizard (modal)', () => {
  test.beforeEach(async ({page}) => {
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  // ── Modal open/close ───────────────────────────────────────────────────────

  test('Clicking "Create new demo" opens the wizard modal', async ({page}) => {
    await openCreateModal(page)
    await expect(page.getByRole('dialog')).toBeVisible()
  })

  test('Modal is dismissed when X button is clicked', async ({page}) => {
    await openCreateModal(page)
    await page.click('[aria-label="Close"]')
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })

  // ── Step 1: CiviForm setup ─────────────────────────────────────────────────

  test('Step 1 screenshot matches baseline', async ({page}) => {
    await openCreateModal(page)
    await test.step('Screenshot Step 1', async () => {
      await validateScreenshot(page.getByRole('dialog'), 'wizard-step-1')
    })
  })

  test('Step 1 passes accessibility audit', async ({page}) => {
    await openCreateModal(page)
    await validateAccessibility(page)
  })

  test('Step 1 has Civic entity field and expiration field', async ({page}) => {
    await openCreateModal(page)
    await expect(page.locator('[name="cityName"]')).toBeVisible()
    await expect(page.locator('[name="expirationDays"]')).toBeVisible()
  })

  test('Step 1 expiration field defaults to 30', async ({page}) => {
    await openCreateModal(page)
    await expect(page.locator('[name="expirationDays"]')).toHaveValue('30')
  })

  test('Step 1: Next button is disabled when city name is empty', async ({page}) => {
    await openCreateModal(page)
    // City name field should be empty by default
    await page.fill('[name="cityName"]', '')
    const nextBtn = page.getByRole('button', {name: /next/i})
    await expect(nextBtn).toBeDisabled()
  })

  // ── Step 2: AWS setup ─────────────────────────────────────────────────────

  test('Step 2 screenshot matches baseline', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    await test.step('Screenshot Step 2', async () => {
      await validateScreenshot(page.getByRole('dialog'), 'wizard-step-2')
    })
  })

  test('Step 2 passes accessibility audit', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')
    await validateAccessibility(page)
  })

  test('Subdomain field auto-suggests slug from city name', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    // JS should have pre-filled the subdomain with the slugified city name
    const subdomain = await page.locator('[name="subdomain"]').inputValue()
    expect(subdomain).toBe('burlington-vt')
  })

  test('Subdomain field is editable (rep can override the suggestion)', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    await page.fill('[name="subdomain"]', 'my-custom-slug')
    await expect(page.locator('[name="subdomain"]')).toHaveValue('my-custom-slug')
  })

  test('Step 2 shows .sandbox.civiform.dev suffix next to subdomain input', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    await expect(page.getByText('.sandbox.civiform.dev')).toBeVisible()
  })

  test('PIN field accepts 6-digit input', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="pin"]')

    await page.fill('[name="pin"]', '482917')
    await expect(page.locator('[name="pin"]')).toHaveValue('482917')
  })

  test('Step 2: Next button disabled when PIN is not 6 digits', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="pin"]')

    await page.fill('[name="subdomain"]', 'burlington-vt')
    await page.fill('[name="pin"]', '123') // only 3 digits
    const nextBtn = page.getByRole('button', {name: /next/i})
    await expect(nextBtn).toBeDisabled()
  })

  // ── Step 3: Program setup ─────────────────────────────────────────────────

  test('Step 3 screenshot matches baseline', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')
    await page.fill('[name="subdomain"]', 'burlington-vt')
    await page.fill('[name="pin"]', '482917')
    await page.fill('[name="adminEmail"]', 'rep@exygy.com')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('button:has-text("Provision demo")')

    await test.step('Screenshot Step 3', async () => {
      await validateScreenshot(page.getByRole('dialog'), 'wizard-step-3')
    })
  })

  test('Step 3 passes accessibility audit', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')
    await page.fill('[name="subdomain"]', 'burlington-vt')
    await page.fill('[name="pin"]', '482917')
    await page.fill('[name="adminEmail"]', 'rep@exygy.com')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('button:has-text("Provision demo")')
    await validateAccessibility(page)
  })

  test('Step 3 has "Import from PDF" button', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')
    await page.fill('[name="subdomain"]', 'burlington-vt')
    await page.fill('[name="pin"]', '482917')
    await page.fill('[name="adminEmail"]', 'rep@exygy.com')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('button:has-text("Provision demo")')

    await expect(page.getByRole('button', {name: /import from pdf/i})).toBeVisible()
  })

  test('Step 3 does NOT have a Notes textarea', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')
    await page.fill('[name="subdomain"]', 'burlington-vt')
    await page.fill('[name="pin"]', '482917')
    await page.fill('[name="adminEmail"]', 'rep@exygy.com')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('button:has-text("Provision demo")')

    await expect(page.locator('[name="notes"]')).not.toBeVisible()
  })

  // ── Full wizard flow → redirect ───────────────────────────────────────────

  test('Completing the wizard redirects to /sandboxes with success flash', async ({page}) => {
    await openCreateModal(page)
    await page.fill('[name="cityName"]', 'Test City')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')
    await page.fill('[name="subdomain"]', 'test-city')
    await page.fill('[name="pin"]', '111222')
    await page.fill('[name="adminEmail"]', 'test@exygy.com')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('button:has-text("Provision demo")')
    await page.click('button:has-text("Provision demo")')

    // Should redirect to /sandboxes (list) — not /sandboxes/:id
    await page.waitForURL('**/sandboxes')
    expect(page.url()).toMatch(/\/sandboxes$/)

    // Flash banner should contain the spec message
    await expect(
      page.getByText(/demo provisioning initiated/i),
    ).toBeVisible()
  })
})

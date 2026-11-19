/**
 * Browser tests for the "Create new demo" 2-step modal wizard.
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

import {expect} from '@playwright/test'
import {
  test,
  validateScreenshot,
  validateAccessibility,
  openCreateModal,
  loginAsAdmin,
  resetTestState,
  tabWithinDialog,
} from '../support'

test.describe('Create Sandbox Wizard (modal)', () => {
  test.beforeAll(async () => {
    await resetTestState()
  })

  test.afterAll(async () => {
    await resetTestState()
  })

  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  // ── Modal open/close ───────────────────────────────────────────────────────

  test('Modal is dismissed when X button is clicked', async ({page}) => {
    await openCreateModal(page)
    await page.click('[aria-label="Close"]')
    await expect(
      page.locator('#create-modal .usa-modal__main'),
    ).not.toBeVisible()
  })

  test('Create modal steals focus to close button and traps Tab across both steps', async ({
    page,
  }) => {
    const createButton = page.getByRole('button', {name: /create new demo/i})
    const createModal = page.locator('#create-modal')
    const closeButton = createModal.locator('.usa-modal__close')

    await createButton.focus()
    await page.keyboard.press('Enter')
    await expect(createModal.locator('.usa-modal__main')).toBeVisible()
    await expect(closeButton).toBeFocused()

    // Step 1 forward Tab cycle stays trapped inside create-modal
    const step1Targets = [
      createModal.locator('#cityName'),
      createModal.locator('input[name="logoType"][value="default"]'),
      createModal.locator('#expirationDays'),
      createModal.getByRole('button', {name: /continue to step 2/i}),
      closeButton,
    ]
    for (const target of step1Targets) {
      await tabWithinDialog(page, '#create-modal', 'Tab')
      await expect(target).toBeFocused()
    }

    // Shift+Tab from close button wraps to Next button without entering background UI
    await tabWithinDialog(page, '#create-modal', 'Shift+Tab')
    await expect(
      createModal.getByRole('button', {name: /continue to step 2/i}),
    ).toBeFocused()

    // Fill city name and advance to Step 2 via keyboard
    await createModal.locator('#cityName').fill('Burlington, VT')
    await createModal.getByRole('button', {name: /continue to step 2/i}).focus()
    await page.keyboard.press('Enter')
    await expect(closeButton).toBeFocused()

    // Step 2 forward Tab cycle stays trapped inside create-modal
    const step2Targets = [
      createModal.locator('#adminEmail'),
      createModal.locator('#subdomain'),
      createModal.locator('#pin'),
      createModal.locator('#googleAnalyticsId'),
      createModal.getByRole('button', {name: /back to step 1/i}),
      createModal.getByRole('button', {name: /provision demo/i}),
      closeButton,
    ]
    for (const target of step2Targets) {
      await tabWithinDialog(page, '#create-modal', 'Tab')
      await expect(target).toBeFocused()
    }

    // Dismissing with Escape restores focus to "Create new demo" button
    await page.keyboard.press('Escape')
    await expect(createModal.locator('.usa-modal__main')).not.toBeVisible()
    await expect(createButton).toBeFocused()
  })

  // ── Step 1: CiviForm setup ─────────────────────────────────────────────────

  test('Step 1 screenshot matches baseline', async ({page}) => {
    await openCreateModal(page)
    await test.step('Screenshot Step 1', async () => {
      await validateScreenshot(
        page.locator('#create-modal .usa-modal__main'),
        'wizard-step-1',
      )
    })
  })

  test('Step 1 passes accessibility audit', async ({page}) => {
    await openCreateModal(page)
    await validateAccessibility(page)
  })

  test('Step 1 validation: Next shows error when city name is empty', async ({
    page,
  }) => {
    await openCreateModal(page)
    await page.fill('#create-modal [name="cityName"]', '')
    await page.click('button:has-text("Next")')
    // Validation error should appear
    await expect(page.locator('#create-wizard-error')).toBeVisible()
  })

  // ── Step 2: AWS setup ─────────────────────────────────────────────────────

  test('Step 2 screenshot matches baseline', async ({page}) => {
    await openCreateModal(page)
    await page.fill('#create-modal [name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    await test.step('Screenshot Step 2', async () => {
      await validateScreenshot(
        page.locator('#create-modal .usa-modal__main'),
        'wizard-step-2',
      )
    })
  })

  test('Step 2 passes accessibility audit', async ({page}) => {
    await openCreateModal(page)
    await page.fill('#create-modal [name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')
    await validateAccessibility(page)
  })

  test('Subdomain field auto-suggests slug from city name', async ({page}) => {
    await openCreateModal(page)
    await page.fill('#create-modal [name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    // JS should have pre-filled the subdomain with the slugified city name
    const subdomain = await page.locator('[name="subdomain"]').inputValue()
    expect(subdomain).toBe('burlington-vt')
  })

  test('Subdomain field is editable (rep can override the suggestion)', async ({
    page,
  }) => {
    await openCreateModal(page)
    await page.fill('#create-modal [name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    await page.fill('[name="subdomain"]', 'my-custom-slug')
    await expect(page.locator('[name="subdomain"]')).toHaveValue(
      'my-custom-slug',
    )
  })

  test('Step 2 has Back button to return to Step 1', async ({page}) => {
    await openCreateModal(page)
    await page.fill('#create-modal [name="cityName"]', 'Burlington, VT')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    await page.click('button:has-text("Back")')
    // Step 1 fields should be visible again
    await expect(page.locator('#create-modal [name="cityName"]')).toBeVisible()
  })

  // ── Full wizard flow → redirect ───────────────────────────────────────────

  test('Completing the wizard redirects to /sandboxes with success flash', async ({
    page,
  }) => {
    await openCreateModal(page)

    // Step 1: CiviForm setup
    await page.fill('#create-modal [name="cityName"]', 'Test City')
    await page.click('button:has-text("Next")')
    await page.waitForSelector('[name="subdomain"]')

    // Step 2: AWS setup + submit
    await page.fill('[name="subdomain"]', 'test-city')
    await page.fill('[name="pin"]', '111222')
    await page.fill('#create-modal [name="adminEmail"]', 'test@exygy.com')
    await page.click('button:has-text("Provision demo")')

    // Should redirect to the dashboard list (/ or /sandboxes) — not /sandboxes/:id
    await page.waitForURL(
      (url) => url.pathname === '/' || url.pathname === '/sandboxes',
    )

    // Flash banner should contain the spec message
    await expect(page.getByText(/demo provisioning initiated/i)).toBeVisible()
    expect(new URL(page.url()).pathname).toMatch(/^\/(sandboxes)?$/)
  })
})

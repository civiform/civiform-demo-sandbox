/**
 * Browser tests for the Dev Tools page (/dev) and Sandbox Dashboard footer link.
 *
 * Verifies:
 * - Sandbox list footer contains a "Dev tools" link navigating to /dev
 * - Dev tools page passes axe-core WCAG accessibility audit
 * - Only the Durable Jobs tool is rendered, listing registered durable jobs
 * - Clicking "Run job" executes the selected durable job immediately and displays a confirmation
 */

import {expect} from '@playwright/test'
import {
  test,
  validateAccessibility,
  loginAsAdmin,
  waitForPath,
  resetTestState,
} from '../support'

test.describe('Dev Tools Auth', () => {
  test.beforeAll(async () => {
    await resetTestState()
  })

  test('Unauthenticated access to /dev redirects to /login', async ({page}) => {
    await page.goto('/dev')
    await waitForPath(page, '/login')
  })
})

test.describe('Dev Tools', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
  })

  test('Footer link on sandbox list navigates to Dev tools page', async ({page}) => {
    await page.goto('/sandboxes')
    await page.waitForSelector('footer')

    const devToolsLink = page.locator('footer #dev-tools-link')
    await expect(devToolsLink).toBeVisible()
    await expect(devToolsLink).toHaveText('Dev tools')

    await devToolsLink.click()
    await waitForPath(page, '/dev')
    await expect(page.getByRole('heading', {name: 'Dev tools', level: 1})).toBeVisible()
  })

  test('Dev tools page passes accessibility audit and lists registered durable jobs', async ({
    page,
  }) => {
    await page.goto('/dev')
    await page.waitForSelector('#durable-job-select')

    await validateAccessibility(page)

    const select = page.locator('#durable-job-select')
    await expect(select).toBeVisible()

    const optionValues = await select.locator('option').evaluateAll((options) =>
      options.map((opt) => (opt as HTMLOptionElement).value),
    )
    expect(optionValues).toContain('DESTROY_EXPIRED_SANDBOXES')
    expect(optionValues).toContain('OLD_JOB_CLEANUP')
    expect(optionValues).not.toContain('TEST')
  })

  test('Selecting a durable job and clicking Run job executes it immediately', async ({page}) => {
    await page.goto('/dev')
    await page.waitForSelector('#durable-job-select')

    await page.selectOption('#durable-job-select', 'OLD_JOB_CLEANUP')
    await page.click('#run-durable-job-button')

    await waitForPath(page, '/dev')
    const statusBanner = page.getByRole('status')
    await expect(statusBanner).toBeVisible()
    await expect(statusBanner).toContainText('Ran durable job: OLD_JOB_CLEANUP')

    await validateAccessibility(page)
  })
})

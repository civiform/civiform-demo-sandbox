/**
 * Browser tests for sortable table headers on the Sandbox Dashboard (/sandboxes).
 *
 * Verifies:
 * - Three column headers ('Civic entity', 'Status', 'Days Remaining') have data-sortable attribute
 * - 'Actions' column header does not have data-sortable attribute
 * - Clicking 'Civic entity' header sets aria-sort attribute
 * - Clicking the same header twice toggles sort direction
 * - Table has 4 columns per row across header and data rows
 * - Table body has at least one data row
 * - Accessibility audit passes on the table
 */

import {test, expect, Locator} from '@playwright/test'
import {validateAccessibility, loginAsAdmin} from '../support'

/**
 * Clicks a sortable column header.
 * USWDS progressively enhances `th[data-sortable]` by inserting a button inside it;
 * clicking the button directly or the header itself triggers the sort.
 */
async function clickSortableHeader(header: Locator): Promise<void> {
  const button = header.locator('button')
  if ((await button.count()) > 0) {
    await button.first().click()
  } else {
    await header.click()
  }
}

test.describe('Sortable Table Headers', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  test('Three column headers have data-sortable attribute', async ({page}) => {
    const sortableHeaders = page.locator('th[data-sortable]')
    await expect(sortableHeaders).toHaveCount(3)

    await expect(
      page.getByRole('columnheader', {name: /civic entity/i}),
    ).toHaveAttribute('data-sortable')
    await expect(
      page.getByRole('columnheader', {name: /status/i}),
    ).toHaveAttribute('data-sortable')
    await expect(
      page.getByRole('columnheader', {name: /days remaining/i}),
    ).toHaveAttribute('data-sortable')
  })

  test('Actions column does NOT have data-sortable attribute', async ({page}) => {
    const actionsHeader = page.getByRole('columnheader', {name: /actions/i})
    await expect(actionsHeader).toBeVisible()
    await expect(actionsHeader).not.toHaveAttribute('data-sortable')
  })

  test("Clicking 'Civic entity' header adds aria-sort attribute", async ({page}) => {
    const civicEntityHeader = page.getByRole('columnheader', {name: /civic entity/i})
    await expect(civicEntityHeader).toBeVisible()

    await test.step("Click 'Civic entity' header", async () => {
      await clickSortableHeader(civicEntityHeader)
    })

    await test.step('Verify aria-sort attribute is set', async () => {
      await expect(civicEntityHeader).toHaveAttribute(
        'aria-sort',
        /^(ascending|descending)$/,
      )
    })
  })

  test('Clicking the same header twice toggles sort direction', async ({page}) => {
    const civicEntityHeader = page.getByRole('columnheader', {name: /civic entity/i})
    await expect(civicEntityHeader).toBeVisible()

    await test.step('First click sets initial sort direction', async () => {
      await clickSortableHeader(civicEntityHeader)
      await expect(civicEntityHeader).toHaveAttribute(
        'aria-sort',
        /^(ascending|descending)$/,
      )
    })

    const initialSort = await civicEntityHeader.getAttribute('aria-sort')
    const expectedToggledSort =
      initialSort === 'ascending' ? 'descending' : 'ascending'

    await test.step('Second click toggles sort direction', async () => {
      await clickSortableHeader(civicEntityHeader)
      await expect(civicEntityHeader).toHaveAttribute('aria-sort', expectedToggledSort)
    })
  })

  test('Table has correct column count (4 columns per row)', async ({page}) => {
    const headerCols = page.locator('table thead tr th')
    await expect(headerCols).toHaveCount(4)

    const rows = page.locator('table tbody tr')
    const rowCount = await rows.count()
    expect(rowCount).toBeGreaterThan(0)

    for (let i = 0; i < rowCount; i++) {
      await expect(rows.nth(i).locator('td')).toHaveCount(4)
    }
  })

  test('Table body has at least one data row', async ({page}) => {
    const rows = page.locator('table tbody tr')
    await expect(rows.first()).toBeVisible()
    expect(await rows.count()).toBeGreaterThanOrEqual(1)
  })

  test('Accessibility audit passes on the table', async ({page}) => {
    await validateAccessibility(page)
  })
})

/**
 * Browser tests for the Sandbox Dashboard stats bar (/sandboxes).
 *
 * Tests the summary stats bar:
 * - Stats bar container (#sandbox-stats) is visible
 * - 'Total demos' card shows a number >= 1
 * - 'Active' card shows a number >= 1
 * - 'Expiring in ≤ 5 days' card is visible
 * - Total count >= Active count (total includes all statuses)
 * - Stats bar has 3 child cards (3 direct child divs)
 * - Stats bar passes accessibility audit
 */

import {test, expect} from '@playwright/test'
import {validateAccessibility, loginAsAdmin} from '../support'

test.describe('Sandbox Dashboard Stats Bar', () => {
  test.beforeEach(async ({page}) => {
    await loginAsAdmin(page)
    await page.goto('/sandboxes')
    await page.waitForSelector('table')
  })

  // ── Accessibility ─────────────────────────────────────────────────────────

  test('Stats bar passes accessibility audit', async ({page}) => {
    await validateAccessibility(page)
  })

  // ── Layout & Structure ───────────────────────────────────────────────────

  test('Stats bar container (#sandbox-stats) is visible', async ({page}) => {
    const statsBar = page.locator('#sandbox-stats')
    await expect(statsBar).toBeVisible()
    await expect(statsBar).toHaveClass(/grid grid-cols-3 gap-4 mb-8/)
  })

  test('Stats bar has 3 child cards', async ({page}) => {
    const cards = page.locator('#sandbox-stats > div')
    await expect(cards).toHaveCount(3)
  })

  // ── Content & Metrics ────────────────────────────────────────────────────

  test("'Total demos' card shows a number >= 1", async ({page}) => {
    const totalCard = page.locator('#sandbox-stats > div').filter({hasText: 'Total demos'})
    await expect(totalCard).toBeVisible()

    const countText = await totalCard.locator('span.text-2xl').innerText()
    const count = parseInt(countText.trim(), 10)
    expect(count).toBeGreaterThanOrEqual(1)
  })

  test("'Active' card shows a number >= 1", async ({page}) => {
    const activeCard = page.locator('#sandbox-stats > div').filter({hasText: 'Active'})
    await expect(activeCard).toBeVisible()

    const countText = await activeCard.locator('span.text-2xl').innerText()
    const count = parseInt(countText.trim(), 10)
    expect(count).toBeGreaterThanOrEqual(1)
  })

  test("'Expiring in ≤ 5 days' card is visible", async ({page}) => {
    const expiringCard = page.locator('#sandbox-stats > div').filter({hasText: 'Expiring in ≤ 5 days'})
    await expect(expiringCard).toBeVisible()
  })

  test('Total count is greater than or equal to active count', async ({page}) => {
    const totalCard = page.locator('#sandbox-stats > div').filter({hasText: 'Total demos'})
    const activeCard = page.locator('#sandbox-stats > div').filter({hasText: 'Active'})

    const totalCountText = await totalCard.locator('span.text-2xl').innerText()
    const activeCountText = await activeCard.locator('span.text-2xl').innerText()

    const totalCount = parseInt(totalCountText.trim(), 10)
    const activeCount = parseInt(activeCountText.trim(), 10)

    expect(totalCount).toBeGreaterThanOrEqual(activeCount)
  })
})

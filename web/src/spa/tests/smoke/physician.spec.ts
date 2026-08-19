/**
 * @smoke — Investigator / physician journey.
 *
 * Participant management → subject record → visit. Uses the seeded Default
 * Study cohort (subjects M-001…). The deep eCRF data-entry fill+save flow is
 * exercised by the component suite (CrfItemWidget / CrfEntryView vitest);
 * this spec guards the navigational journey an investigator actually walks.
 */
import { test, expect } from '@playwright/test'
import { loginAndGoto } from '../support/auth'

test.describe('@smoke Investigator', () => {
  test('participant matrix lists study subjects', async ({ page }) => {
    await loginAndGoto(page, 'investigator', '/subjects')
    await expect(page.getByRole('link', { name: /M-\d+/ }).first()).toBeVisible({ timeout: 15_000 })
  })

  test('exports the (filtered) subject matrix to CSV', async ({ page }) => {
    await loginAndGoto(page, 'investigator', '/subjects')
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByTestId('subject-matrix-export').click(),
    ])
    expect(download.suggestedFilename()).toMatch(/^subjects-.*\.csv$/)
  })

  test('opens a subject record and its visits', async ({ page }) => {
    await loginAndGoto(page, 'investigator', '/subjects')
    const subject = page.getByRole('link', { name: /M-\d+/ }).first()
    await subject.click()
    await expect(page).toHaveURL(/\/subjects\/M-\d+/)
    await expect(page.getByText(/Seite nicht gefunden/i)).toHaveCount(0)
    // The record should surface the subject's scheduled visits (events).
    await expect(page.locator('a[href*="/events/"]').first()).toBeVisible({ timeout: 15_000 })
  })

  test('opens a visit from a subject record', async ({ page }) => {
    await loginAndGoto(page, 'investigator', '/subjects')
    await page.getByRole('link', { name: /M-\d+/ }).first().click()
    await expect(page).toHaveURL(/\/subjects\/M-\d+/)
    const visit = page.locator('a[href*="/events/"]').first()
    await visit.click()
    await expect(page).toHaveURL(/\/events\/[\w-]+/)
    await expect(page.getByText(/Seite nicht gefunden/i)).toHaveCount(0)
  })
})

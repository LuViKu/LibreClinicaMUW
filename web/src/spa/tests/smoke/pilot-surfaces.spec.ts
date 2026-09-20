/**
 * @smoke — the authenticated surfaces the two pilots added.
 *
 * The reconciliation inbox, the due-visits list and the dataset export. Each
 * of these shipped with unit and integration coverage; what none of them had
 * was a check that the page is reachable and renders against a real backend.
 * The due-visits endpoint in particular spent a while with ten passing tests
 * and no route at all.
 */
import { test, expect } from '@playwright/test'
import { loginAndGoto } from '../support/auth'

test.describe('@smoke Image reconciliation inbox', () => {
  test('a data manager can open it', async ({ page }) => {
    await loginAndGoto(page, 'dataManager', '/ingest-inbox')
    await expect(page.getByText(/Seite nicht gefunden/i)).toHaveCount(0)
    // Either rows or the empty state — both mean the view loaded and the
    // backend answered. A spinner that never resolves does not.
    await expect(
      page.locator('[data-testid="ingest-row"], [data-testid="inbox-empty"], [data-testid="inbox-grid"], p'),
    ).not.toHaveCount(0, { timeout: 15_000 })
  })
})

test.describe('@smoke Staff upload (DR-029)', () => {
  test('the inbox links to it and it opens in staff mode', async ({ page }) => {
    await loginAndGoto(page, 'dataManager', '/ingest-inbox')
    await page.getByTestId('inbox-upload-link').click()
    await expect(page).toHaveURL(/\/ingest-inbox\/upload$/)
    const workbench = page.getByTestId('upload-workbench')
    await expect(workbench).toBeVisible({ timeout: 15_000 })
    await expect(workbench).toHaveAttribute('data-mode', 'staff')
    // Behind a login the day's visits come from the due-visits endpoint, no
    // institutional switch involved — the list is there, possibly empty.
    await expect(page.getByTestId('todays-visits')).toBeVisible({ timeout: 15_000 })
  })
})

test.describe('@smoke Due visits', () => {
  test('an investigator can open it and it queries a window', async ({ page }) => {
    await loginAndGoto(page, 'investigator', '/due-visits')
    await expect(page.getByTestId('due-reload')).toBeVisible({ timeout: 15_000 })
    // The window defaults around today and must reach into the past, because
    // a missed visit is the reason to look at this page.
    const from = await page.locator('#dv-from').inputValue()
    const to = await page.locator('#dv-to').inputValue()
    const today = new Date().toISOString().slice(0, 10)
    expect(from < today, `from (${from}) should precede today`).toBe(true)
    expect(to > today, `to (${to}) should follow today`).toBe(true)
    // The request resolved one way or the other; an error banner would mean
    // the endpoint is unreachable, which is what this smoke exists to catch.
    await expect(page.getByTestId('due-error')).toHaveCount(0)
  })

  test('is reachable from the home catalogue', async ({ page }) => {
    await loginAndGoto(page, 'investigator', '/')
    await page.getByRole('link', { name: /Fällige Visiten|Due visits/i }).first().click()
    await expect(page).toHaveURL(/\/due-visits/)
  })
})

test.describe('@smoke Dataset export', () => {
  test('the quick ODM export serves a non-empty file', async ({ page }) => {
    await loginAndGoto(page, 'dataManager', '/datasets')
    const button = page.getByTestId('dataset-quick-odm')
    await expect(button).toBeVisible({ timeout: 15_000 })

    // Assert on the network rather than the download tab the view opens:
    // a popup is awkward to catch headlessly, and what matters is that the
    // server produced bytes. An export that reports success and serves an
    // empty file is the exact defect this pilot work started from.
    const [trigger] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('datasets:quick-odm'), { timeout: 120_000 }),
      button.click(),
    ])
    expect(trigger.status(), await trigger.text()).toBe(200)

    const { downloadUrl } = (await trigger.json()) as { downloadUrl: string }
    expect(downloadUrl, 'the export should name a download').toBeTruthy()

    // page.request shares the browser context's cookies, so the session carries.
    const file = await page.request.get(downloadUrl)
    expect(file.status(), 'the export download should succeed').toBeLessThan(400)
    expect((await file.body()).length, 'the export must not be an empty file').toBeGreaterThan(0)
  })
})

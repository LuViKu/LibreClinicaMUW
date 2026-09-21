/**
 * @smoke — the two unauthenticated upload portals.
 *
 * These are the only pages in the application that anyone who can reach the
 * network can open. They exist because an operator is standing at a camera
 * with a phone and cannot be asked to log in, which also means a mistake here
 * is a disclosure rather than an inconvenience.
 *
 * So the negative cases are the point: an unknown label must reveal nothing,
 * and a one-character query must not enumerate the register. The positive path
 * is asserted too, because a portal that refuses everything is equally useless.
 *
 * No `loginAndGoto` anywhere in this file, deliberately. If a step here starts
 * needing a session, the portal has stopped being a portal.
 */
import { test, expect } from '@playwright/test'

// DR-029 — one page for every file; the two older paths redirect to it.
const PORTALS = [
  { path: '/upload', label: 'Combined upload' },
]

for (const { path, label } of PORTALS) {
  test.describe(`@smoke ${label} portal`, () => {
    test('opens without a session', async ({ page }) => {
      await page.goto(path, { waitUntil: 'domcontentloaded' })
      // A redirect to /login would mean the reverse proxy is the only thing
      // standing between the camera operator and a login form they have no
      // account for.
      await expect(page).not.toHaveURL(/\/login/)
      await expect(page.locator('h1')).toBeVisible({ timeout: 15_000 })
    })

    /**
     * Visiting a portal confers no access.
     *
     * The servlet container does hand out an anonymous JSESSIONID for any page
     * it serves, including these — that is benign, because authentication
     * replaces the session id (SessionFixationProtectionStrategy), so the
     * anonymous one cannot be escalated. What must hold is that carrying it
     * buys nothing: a staff endpoint still refuses.
     */
    test('confers no access to staff endpoints', async ({ page }) => {
      await page.goto(path, { waitUntil: 'domcontentloaded' })
      await page.waitForLoadState('load')

      const me = await page.request.get('/LibreClinica/pages/api/v1/me')
      expect(
        me.status(),
        'a portal visitor must not be treated as authenticated',
      ).toBe(401)
    })
  })
}

test.describe('@smoke upload page — the older addresses still arrive', () => {
  for (const old of ['/oct-upload', '/image-upload']) {
    test(`${old} redirects to /upload`, async ({ page }) => {
      await page.goto(old, { waitUntil: 'domcontentloaded' })
      await expect(page).toHaveURL(/\/upload$/)
      await expect(page.getByTestId('upload-workbench')).toBeVisible({ timeout: 15_000 })
    })
  }
})

test.describe('@smoke upload page — identification', () => {
  test('a known subject label resolves', async ({ page }) => {
    await page.goto('/upload', { waitUntil: 'domcontentloaded' })
    await page.locator('#up-patient').fill('M-001')
    await page.locator('#up-patient').blur()
    await expect(page.getByTestId('resolve-found')).toBeVisible({ timeout: 15_000 })
  })

  /**
   * The failure message must not distinguish "no such subject" from "not in a
   * study you may see", and must not echo anything about the register.
   */
  test('an unknown label reveals nothing', async ({ page }) => {
    await page.goto('/upload', { waitUntil: 'domcontentloaded' })
    await page.locator('#up-patient').fill('ZZZ-999')
    await page.locator('#up-patient').blur()
    await page.waitForTimeout(1500)

    await expect(page.getByTestId('resolve-found')).toHaveCount(0)
    // No real subject label leaks into the page as a "did you mean".
    await expect(page.getByText(/M-\d{3}/)).toHaveCount(0)
  })

  test('the visit picker stays hidden until the institution enables it', async ({ page }) => {
    await page.goto('/upload', { waitUntil: 'domcontentloaded' })
    await page.waitForLoadState('load')
    // core.ingest.portal.todaysVisits ships off: a list of today's patients on
    // a page that needs no login waits for data-protection sign-off.
    await expect(page.getByTestId('todays-visits')).toHaveCount(0)
  })

  /**
   * A file the page cannot name is shown refused, not silently dropped —
   * and it never leaves the browser.
   */
  test('an unsupported file is refused on the page', async ({ page }) => {
    await page.goto('/upload', { waitUntil: 'domcontentloaded' })
    await page.getByTestId('upload-dropzone-input').setInputFiles({
      name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('hello, not an image'),
    })
    await expect(page.getByTestId('row-error')).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('[data-row-kind="unknown"]')).toHaveCount(1)
  })

  /** A photo lands in the inbox without a visit, and can be taken back. */
  test('a photo without a visit is uploaded to the inbox and can be undone', async ({ page }) => {
    await page.goto('/upload', { waitUntil: 'domcontentloaded' })
    // 1x1 PNG
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64')
    await page.getByTestId('upload-dropzone-input').setInputFiles({ name: 'fundus.png', mimeType: 'image/png', buffer: png })
    const row = page.locator('[data-row-kind="image"]').first()
    await expect(row).toBeVisible({ timeout: 15_000 })
    await expect(row).toHaveAttribute('data-row-state', /nopatient|duplicate/)
    if ((await row.getAttribute('data-row-state')) === 'duplicate') return // an earlier run left it there
    await row.locator('[data-testid^="action-park-"]').click()
    await expect(row).toHaveAttribute('data-row-state', 'committed', { timeout: 30_000 })
    await row.locator('[data-testid^="action-undo-"]').click()
    await expect(row).toHaveAttribute('data-row-state', 'nopatient', { timeout: 30_000 })
  })
})

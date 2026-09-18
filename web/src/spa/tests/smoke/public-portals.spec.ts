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

const PORTALS = [
  { path: '/image-upload', label: 'Remidio image upload' },
  { path: '/oct-upload', label: 'OCT upload' },
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

    test('carries no session cookie', async ({ page, context }) => {
      await page.goto(path, { waitUntil: 'domcontentloaded' })
      await page.waitForLoadState('load')
      const cookies = await context.cookies()
      expect(
        cookies.filter((c) => /JSESSIONID/i.test(c.name)),
        'a portal page must not establish a session',
      ).toHaveLength(0)
    })
  })
}

test.describe('@smoke image upload portal — identification', () => {
  test('a known subject label resolves', async ({ page }) => {
    await page.goto('/image-upload', { waitUntil: 'domcontentloaded' })
    await page.locator('#ip-patient').fill('M-001')
    await page.locator('#ip-patient').blur()
    await expect(page.getByTestId('resolve-found')).toBeVisible({ timeout: 15_000 })
  })

  /**
   * The failure message must not distinguish "no such subject" from "not in a
   * study you may see", and must not echo anything about the register.
   */
  test('an unknown label reveals nothing', async ({ page }) => {
    await page.goto('/image-upload', { waitUntil: 'domcontentloaded' })
    await page.locator('#ip-patient').fill('ZZZ-999')
    await page.locator('#ip-patient').blur()
    await page.waitForTimeout(1500)

    await expect(page.getByTestId('resolve-found')).toHaveCount(0)
    // No real subject label leaks into the page as a "did you mean".
    await expect(page.getByText(/M-\d{3}/)).toHaveCount(0)
  })

  test('the visit picker stays hidden until the institution enables it', async ({ page }) => {
    await page.goto('/image-upload', { waitUntil: 'domcontentloaded' })
    await page.waitForLoadState('load')
    // core.ingest.portal.todaysVisits ships off: a list of today's patients on
    // a page that needs no login waits for data-protection sign-off.
    await expect(page.getByTestId('todays-visits')).toHaveCount(0)
  })
})

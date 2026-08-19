/**
 * @smoke — Per-role route reachability.
 *
 * The cheapest regression net: log in as each seeded role against the real
 * backend and confirm their key routes render a real view (heading visible,
 * not the login bounce, not the 404 page). Catches guard/role-gating
 * regressions, broken bootstrap, and dead routes — the classes of failure
 * component tests can't see.
 */
import { test, expect } from '@playwright/test'
import { loginAndGoto, login } from '../support/auth'

test.describe('@smoke role route reachability', () => {
  test('Administrator reaches system-level admin routes', async ({ page }) => {
    // manual_admin is a pure system admin (no study assignment), so it covers
    // the study-agnostic routes: user management, sites, and study *creation*.
    for (const path of ['/manage-users', '/sites', '/studies/new']) {
      await loginAndGoto(page, 'admin', path)
    }
  })

  test('Data Manager reaches build + library routes', async ({ page }) => {
    for (const path of ['/crf-library', '/build-study', '/event-definitions']) {
      await loginAndGoto(page, 'dataManager', path)
    }
  })

  test('Investigator reaches subjects + home', async ({ page }) => {
    for (const path of ['/', '/subjects']) {
      await loginAndGoto(page, 'investigator', path)
    }
  })

  test('CRC reaches subjects + home', async ({ page }) => {
    for (const path of ['/', '/subjects']) {
      await loginAndGoto(page, 'crc', path)
    }
  })

  test('a non-privileged role is kept out of admin routes', async ({ page }) => {
    // Negative check: an Investigator hitting /manage-users must NOT land on
    // the admin view — the guard should redirect (home or login), never render it.
    await login(page.context(), 'investigator')
    await page.goto('/manage-users', { waitUntil: 'domcontentloaded' })
    await expect(page).not.toHaveURL(/\/manage-users$/)
  })
})

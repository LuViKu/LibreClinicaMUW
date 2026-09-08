/**
 * @a11y — Browser-level accessibility harness.
 *
 * Logs in against the real backend (mock-mode auth was removed in milestone
 * 13) and runs `@axe-core/playwright` over each reachable route, asserting no
 * WCAG 2.2 AA violations — including colour contrast, which jsdom-based Vitest
 * cannot see. Route-level pages only; record-detail a11y (a specific CRF-entry
 * / signing screen) is a follow-up once those views have stable seeded ids.
 *
 * Runs against the SPA on the Vite dev server (base `/`, proxying the backend);
 * see playwright.config.ts + tests/support/auth.ts.
 */
import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { login, type Role } from '../support/auth'

async function scan(page: import('@playwright/test').Page) {
  return new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'])
    .analyze()
}

/** (role, path, label) tuples covering the reachable route-level surface. */
const CASES: { role: Role | null; path: string; label: string }[] = [
  { role: null, path: '/login', label: 'Login' },
  { role: 'investigator', path: '/', label: 'Home (landing tiles)' },
  { role: 'investigator', path: '/subjects', label: 'Subject Matrix' },
  { role: 'investigator', path: '/subjects/new', label: 'Add Subject' },
  { role: 'monitor', path: '/notes', label: 'Notes & Discrepancies' },
  { role: 'dataManager', path: '/crf-library', label: 'CRF Library' },
  { role: 'dataManager', path: '/build-study', label: 'Build Study' },
  { role: 'dataManager', path: '/event-definitions', label: 'Event Definitions' },
  { role: 'admin', path: '/manage-users', label: 'Manage Users' },
  { role: 'admin', path: '/sites', label: 'Sites' },
  { role: 'admin', path: '/studies/new', label: 'Create Study' },
]

for (const { role, path, label } of CASES) {
  test(`@a11y ${label} passes WCAG 2.2 AA`, async ({ page }) => {
    if (role) await login(page.context(), role)
    await page.goto(path, { waitUntil: 'domcontentloaded' })
    // Guard against silently scanning a login-bounce (a clean page) and
    // reporting false coverage: a role-gated route must actually render.
    if (role) await expect(page, `${label} should be reachable by ${role}`).not.toHaveURL(/\/login/)
    // Let async view data paint before the scan.
    await page.waitForLoadState('load')
    await page.waitForTimeout(800)
    const { violations } = await scan(page)
    expect(violations, `${label}: ${violations.map((v) => v.id).join(', ')}`).toEqual([])
  })
}

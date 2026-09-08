/**
 * Visual capture — full-page screenshots of key screens per role, for visual
 * review + as reference baselines. Opt-in (VISUAL_CAPTURE=1) so it doesn't run
 * in the default e2e gate. Writes to tests/visual/__shots__/<name>.png.
 *
 * A pixel-diff regression gate (expect(page).toHaveScreenshot) should generate
 * its baselines in the CI Linux env to avoid cross-platform font-rendering
 * diffs; this capture spec is the reviewable precursor.
 */
import { test } from '@playwright/test'
import { login, type Role } from '../support/auth'
import { resolve } from 'node:path'
import { mkdirSync } from 'node:fs'

const OUT = resolve(process.cwd(), 'tests/visual/__shots__')
const SCREENS: { role: Role | null; path: string; name: string }[] = [
  { role: null, path: '/login', name: '00-login' },
  // Admin — study creation + settings + user management
  { role: 'admin', path: '/studies/new', name: 'admin-01-create-study' },
  { role: 'admin', path: '/manage-users', name: 'admin-02-manage-users' },
  { role: 'admin', path: '/sites', name: 'admin-03-sites' },
  // Data Manager / study manager — settings + setup + export
  { role: 'dataManager', path: '/studies/S_DEFAULTS1/edit', name: 'dm-01-study-edit' },
  { role: 'dataManager', path: '/studies/S_DEFAULTS1/parameters', name: 'dm-02-study-parameters' },
  { role: 'dataManager', path: '/build-study', name: 'dm-03-build-study' },
  { role: 'dataManager', path: '/crf-library', name: 'dm-04-crf-library' },
  { role: 'dataManager', path: '/event-definitions', name: 'dm-05-event-definitions' },
  { role: 'dataManager', path: '/rules', name: 'dm-06-rules' },
  { role: 'dataManager', path: '/group-classes', name: 'dm-07-group-classes' },
  { role: 'dataManager', path: '/datasets', name: 'dm-08-datasets-export' },
  // Investigator / physician — participants, visit, sign, notes (bug fix)
  { role: 'investigator', path: '/subjects', name: 'phys-01-subject-matrix' },
  { role: 'investigator', path: '/subjects/M-001', name: 'phys-02-subject-detail' },
  { role: 'investigator', path: '/subjects/M-001/sign', name: 'phys-03-sign' },
  { role: 'investigator', path: '/notes', name: 'phys-04-notes' },
]

test.describe('visual capture', () => {
  test.beforeEach(() => {
    test.skip(!process.env.VISUAL_CAPTURE, 'set VISUAL_CAPTURE=1 to capture screenshots')
  })
  for (const { role, path, name } of SCREENS) {
    test(`capture ${name}`, async ({ page }) => {
      mkdirSync(OUT, { recursive: true })
      if (role) await login(page.context(), role)
      await page.goto(path, { waitUntil: 'domcontentloaded' })
      await page.waitForLoadState('load')
      await page.waitForTimeout(1200)
      await page.screenshot({ path: resolve(OUT, `${name}.png`), fullPage: true })
    })
  }
})

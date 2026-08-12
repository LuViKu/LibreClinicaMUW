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
  { role: 'dataManager', path: '/', name: '01-dm-home' },
  { role: 'dataManager', path: '/crf-library', name: '02-crf-library' },
  { role: 'dataManager', path: '/build-study', name: '03-build-study' },
  { role: 'dataManager', path: '/event-definitions', name: '04-event-definitions' },
  { role: 'investigator', path: '/subjects', name: '05-subject-matrix' },
  { role: 'admin', path: '/manage-users', name: '06-manage-users' },
  { role: 'admin', path: '/sites', name: '07-sites' },
  { role: 'admin', path: '/studies/new', name: '08-create-study' },
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

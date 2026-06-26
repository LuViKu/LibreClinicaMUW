/**
 * App-manual screenshot capture harness.
 *
 * Drives the running SPA as each role and saves named, full-page screenshots
 * into docs/manuals/app-manual/screenshots/<role>/<id>.png. The filenames here
 * are the ones referenced by the per-role manual markdown, so re-running this
 * regenerates every figure in place (e.g. for a design-agent restyle).
 *
 * This is NOT part of the test suite — it is a documentation tool. It is opt-in
 * via MANUAL_CAPTURE=1 so a normal `pnpm test:e2e` skips it.
 *
 * Prerequisites:
 *   - The SPA dev server running (pnpm dev, http://127.0.0.1:5173) with the
 *     backend reachable through its proxy, seeded with demo study data.
 *   - Demo credentials per role, supplied via env (NEVER hard-code secrets):
 *       MANUAL_ADMIN_USER / MANUAL_ADMIN_PASS
 *       MANUAL_DM_USER    / MANUAL_DM_PASS
 *       MANUAL_MONITOR_USER / MANUAL_MONITOR_PASS
 *       MANUAL_INV_USER   / MANUAL_INV_PASS
 *       MANUAL_CRC_USER   / MANUAL_CRC_PASS
 *     A role with no creds set is skipped (logged), so you can capture a subset.
 *
 * Run:
 *   MANUAL_CAPTURE=1 \
 *   MANUAL_ADMIN_USER=... MANUAL_ADMIN_PASS=... [other roles...] \
 *   pnpm exec playwright test tests/manual/capture-manual.spec.ts
 */
import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const BASE = process.env.MANUAL_BASE_URL ?? 'http://127.0.0.1:5173/LibreClinica/app'
// cwd is web/src/spa when playwright runs; repo root is three levels up.
const SHOT_ROOT = resolve(process.cwd(), '../../../docs/manuals/app-manual/screenshots')

type Screen = { id: string; path: string; note?: string }

/** Direct-navigable screens per role (no record id needed). Detail views that
 *  need an id are reached via click-through helpers below. */
const ROLES: Record<
  string,
  { dir: string; userEnv: string; passEnv: string; screens: Screen[] }
> = {
  Administrator: {
    dir: 'administrator',
    userEnv: 'MANUAL_ADMIN_USER',
    passEnv: 'MANUAL_ADMIN_PASS',
    screens: [
      { id: '00-home', path: '/' },
      { id: '01-subject-matrix', path: '/subjects' },
      { id: '02-manage-users', path: '/manage-users' },
      { id: '03-sites', path: '/sites' },
      { id: '04-build-study', path: '/build-study' },
      { id: '05-event-definitions', path: '/event-definitions' },
      { id: '06-crf-library', path: '/crf-library' },
      { id: '07-rules', path: '/rules' },
      { id: '08-group-classes', path: '/group-classes' },
      { id: '09-modalities', path: '/modalities' },
      { id: '10-datasets', path: '/datasets' },
      { id: '11-import-crf-data', path: '/import-crf-data' },
      { id: '12-system-audit-log', path: '/system/audit-log' },
      { id: '13-system-status', path: '/admin/system-status' },
      { id: '14-password-policy', path: '/admin/password-policy' },
      { id: '15-app-config', path: '/admin/config' },
      { id: '16-scheduled-jobs', path: '/admin/jobs' },
    ],
  },
  'Data Manager': {
    dir: 'data-manager',
    userEnv: 'MANUAL_DM_USER',
    passEnv: 'MANUAL_DM_PASS',
    screens: [
      { id: '00-home', path: '/' },
      { id: '01-subject-matrix', path: '/subjects' },
      { id: '02-build-study', path: '/build-study' },
      { id: '03-event-definitions', path: '/event-definitions' },
      { id: '04-crf-library', path: '/crf-library' },
      { id: '05-rules', path: '/rules' },
      { id: '06-group-classes', path: '/group-classes' },
      { id: '07-notes-discrepancies', path: '/notes' },
      { id: '08-study-audit-log', path: '/audit-log' },
      { id: '09-datasets', path: '/datasets' },
      { id: '10-create-dataset', path: '/datasets/new' },
      { id: '11-import-crf-data', path: '/import-crf-data' },
    ],
  },
  Monitor: {
    dir: 'monitor',
    userEnv: 'MANUAL_MONITOR_USER',
    passEnv: 'MANUAL_MONITOR_PASS',
    screens: [
      { id: '00-home', path: '/' },
      { id: '01-subject-matrix', path: '/subjects' },
      { id: '02-sdv', path: '/sdv' },
      { id: '03-notes-discrepancies', path: '/notes' },
      { id: '04-study-audit-log', path: '/audit-log' },
      { id: '05-datasets', path: '/datasets' },
    ],
  },
  Investigator: {
    dir: 'investigator',
    userEnv: 'MANUAL_INV_USER',
    passEnv: 'MANUAL_INV_PASS',
    screens: [
      { id: '00-home', path: '/' },
      { id: '01-subject-matrix', path: '/subjects' },
      { id: '02-add-subject', path: '/subjects/new' },
    ],
  },
  CRC: {
    dir: 'crc',
    userEnv: 'MANUAL_CRC_USER',
    passEnv: 'MANUAL_CRC_PASS',
    screens: [
      { id: '00-home', path: '/' },
      { id: '01-subject-matrix', path: '/subjects' },
      { id: '02-add-subject', path: '/subjects/new' },
    ],
  },
}

/** Authenticate by replaying the SPA's own j_spring_security_check POST, then
 *  let the SPA bootstrap from the session cookie. Mirrors auth.ts localLogin. */
async function login(page: import('@playwright/test').Page, user: string, pass: string) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  const result = await page.evaluate(
    async ([u, p]) => {
      const body = new URLSearchParams({ j_username: u, j_password: p })
      const r = await fetch('/LibreClinica/j_spring_security_check', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body.toString(),
      })
      return { url: r.url, ok: r.ok }
    },
    [user, pass],
  )
  if (/errorLogin|errorLocked|2faOutdated/.test(result.url)) {
    throw new Error(`login failed for ${user}: ${result.url}`)
  }
}

/** Best-effort settle: SPA route transition + data fetch + fonts. */
async function settle(page: import('@playwright/test').Page) {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.waitForTimeout(600)
  await page.evaluate(() => (document as any).fonts?.ready).catch(() => {})
}

async function shot(page: import('@playwright/test').Page, dir: string, id: string) {
  const out = resolve(SHOT_ROOT, dir)
  mkdirSync(out, { recursive: true })
  await page.screenshot({ path: resolve(out, `${id}.png`), fullPage: true })
}

test('capture: common (login & study picker)', async ({ page }) => {
  test.skip(!process.env.MANUAL_CAPTURE, 'set MANUAL_CAPTURE=1 to run the capture harness')
  // Anonymous public screens.
  for (const [id, path] of [['00-login', '/login'], ['01-first-login', '/first-login']]) {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' }).catch(() => {})
    await settle(page)
    await shot(page, 'common', id)
  }
  // Study picker needs auth — capture with whichever role creds are available.
  const u = process.env.MANUAL_ADMIN_USER ?? process.env.MANUAL_DM_USER ?? process.env.MANUAL_INV_USER
  const p = process.env.MANUAL_ADMIN_PASS ?? process.env.MANUAL_DM_PASS ?? process.env.MANUAL_INV_PASS
  if (u && p) {
    await login(page, u, p)
    await page.goto(`${BASE}/pick-study`, { waitUntil: 'domcontentloaded' }).catch(() => {})
    await settle(page)
    await shot(page, 'common', '02-pick-study')
  }
  expect(true).toBe(true)
})

for (const [role, cfg] of Object.entries(ROLES)) {
  test(`capture: ${role}`, async ({ page }) => {
    test.skip(!process.env.MANUAL_CAPTURE, 'set MANUAL_CAPTURE=1 to run the capture harness')
    const user = process.env[cfg.userEnv]
    const pass = process.env[cfg.passEnv]
    test.skip(!user || !pass, `no creds for ${role} (${cfg.userEnv}/${cfg.passEnv})`)

    await login(page, user!, pass!)

    const failures: string[] = []
    for (const s of cfg.screens) {
      try {
        await page.goto(`${BASE}${s.path}`, { waitUntil: 'domcontentloaded' })
        await settle(page)
        await shot(page, cfg.dir, s.id)
      } catch (e) {
        failures.push(`${s.id} (${s.path}): ${(e as Error).message}`)
      }
    }

    // Click-through detail captures (Investigator/CRC/Admin): first subject → its first event → first CRF.
    if (['Investigator', 'CRC', 'Administrator'].includes(role)) {
      try {
        await page.goto(`${BASE}/subjects`, { waitUntil: 'domcontentloaded' })
        await settle(page)
        const firstRow = page.locator('table tbody tr a, [data-testid="subject-row"] a').first()
        if (await firstRow.count()) {
          await firstRow.click()
          await settle(page)
          await shot(page, cfg.dir, '20-subject-detail')
        }
      } catch (e) {
        failures.push(`subject-detail click-through: ${(e as Error).message}`)
      }
    }

    if (failures.length) {
      console.log(`\n[${role}] ${failures.length} screen(s) could not be captured:\n  ` + failures.join('\n  '))
    }
    // The harness is best-effort; an unreachable screen (e.g. empty seed) is logged, not fatal.
    expect(true).toBe(true)
  })
}

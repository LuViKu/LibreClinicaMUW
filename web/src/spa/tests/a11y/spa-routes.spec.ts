/**
 * Phase E.9 — Browser-level a11y harness.
 *
 * Runs against the SPA served by Vite (`pnpm dev` at port 5173). Each
 * route is loaded with the dev role-switcher mock identity so guards
 * pass, then `@axe-core/playwright` scans the page for WCAG 2.2 AA
 * violations including colour contrast (the dimension Vitest cannot
 * see in jsdom).
 *
 * Run locally:
 *   pnpm dev            # in one terminal
 *   pnpm test:a11y:e2e  # in another
 *
 * In CI: spin Vite + run this spec as a smoke gate per Phase E.9 §
 * Verification gates.
 */
import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const BASE = 'http://127.0.0.1:5173/LibreClinica/app'

/**
 * Pin a mock user into sessionStorage before navigating so the
 * router guard treats the visit as authenticated. The role determines
 * which role-gated routes are reachable.
 */
async function loginAs(page: Parameters<typeof test>[0]['page'] extends infer P ? P : never, role: 'Investigator' | 'Monitor' | 'Data Manager') {
  await page.goto(BASE + '/login', { waitUntil: 'load' })
  await page.evaluate((r) => {
    const presets: Record<string, unknown> = {
      Investigator:  { username: 'user_demo', displayName: 'Dr. user_demo', email: 'user_demo@meduniwien.ac.at', role: 'Investigator', siteLabel: 'München', source: 'sso', mfaSatisfied: true, profileComplete: true },
      Monitor:       { username: 'monitor_demo', displayName: 'Mona Demo', email: 'monitor_demo@example.org', role: 'Monitor', siteLabel: null, source: 'local', mfaSatisfied: false, profileComplete: true },
      'Data Manager':{ username: 'dm_demo', displayName: 'Dora Manager', email: 'dm_demo@meduniwien.ac.at', role: 'Data Manager', siteLabel: null, source: 'sso', mfaSatisfied: true, profileComplete: true },
    }
    window.sessionStorage.setItem('libreclinica.mock_user', JSON.stringify(presets[r]))
  }, role)
}

async function scan(page: Parameters<typeof test>[0]['page'] extends infer P ? P : never) {
  return new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'])
    .analyze()
}

test.describe('@a11y — Anonymous routes', () => {
  test('LoginView passes WCAG 2.2 AA', async ({ page }) => {
    await page.goto(BASE + '/login')
    const results = await scan(page)
    expect(results.violations).toEqual([])
  })
})

test.describe('@a11y — Investigator workflow', () => {
  test.beforeEach(async ({ page }) => loginAs(page, 'Investigator'))

  test('Home (landing tiles)', async ({ page }) => {
    await page.goto(BASE + '/')
    expect((await scan(page)).violations).toEqual([])
  })

  test('Subject Matrix', async ({ page }) => {
    await page.goto(BASE + '/subjects')
    await page.waitForSelector('table')
    expect((await scan(page)).violations).toEqual([])
  })

  test('Add Subject', async ({ page }) => {
    await page.goto(BASE + '/subjects/new')
    await page.waitForSelector('form')
    expect((await scan(page)).violations).toEqual([])
  })

  test('CRF Entry', async ({ page }) => {
    await page.goto(BASE + '/event-crfs/EC_M001_V1_DEMO')
    await page.waitForSelector('form')
    expect((await scan(page)).violations).toEqual([])
  })

  test('Sign Subject', async ({ page }) => {
    await page.goto(BASE + '/subjects/M-001/sign')
    expect((await scan(page)).violations).toEqual([])
  })
})

test.describe('@a11y — Monitor workflow', () => {
  test.beforeEach(async ({ page }) => loginAs(page, 'Monitor'))

  test('SDV table', async ({ page }) => {
    await page.goto(BASE + '/sdv')
    await page.waitForSelector('table')
    expect((await scan(page)).violations).toEqual([])
  })

  test('Notes & Discrepancies', async ({ page }) => {
    await page.goto(BASE + '/notes')
    await page.waitForSelector('table')
    expect((await scan(page)).violations).toEqual([])
  })

  test('Study Audit Log', async ({ page }) => {
    await page.goto(BASE + '/audit-log')
    expect((await scan(page)).violations).toEqual([])
  })
})

test.describe('@a11y — Data Manager workflow', () => {
  test.beforeEach(async ({ page }) => loginAs(page, 'Data Manager'))

  test('Build Study', async ({ page }) => {
    await page.goto(BASE + '/build-study')
    expect((await scan(page)).violations).toEqual([])
  })

  test('Manage Users', async ({ page }) => {
    await page.goto(BASE + '/manage-users')
    await page.waitForSelector('table')
    expect((await scan(page)).violations).toEqual([])
  })

  test('Import CRF Data wizard', async ({ page }) => {
    await page.goto(BASE + '/import-crf-data')
    expect((await scan(page)).violations).toEqual([])
  })
})

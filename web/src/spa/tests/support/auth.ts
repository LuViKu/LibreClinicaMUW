/**
 * E2E real-login support (2026-08).
 *
 * Mock-mode auth was removed in milestone 13 — the backend is the single
 * source of truth. So e2e specs authenticate exactly like the SPA does:
 * POST the Spring form-login filter, then bind the session's active study.
 * Because {@link main.ts} awaits `bootstrap()` (GET /me) BEFORE mounting,
 * a session cookie established here makes a cold `page.goto()` of a
 * role-guarded route resolve authenticated (no login bounce).
 *
 * The requests go through the Vite proxy (`/LibreClinica/*` → :8080), so
 * they share the browser context's cookie jar with subsequent page loads.
 */
import type { BrowserContext, Page, APIRequestContext } from '@playwright/test'
import { expect } from '@playwright/test'

export type Role = 'admin' | 'dataManager' | 'investigator' | 'crc' | 'monitor'

/**
 * Seeded demo accounts (all password `12345678`, context=demo), one per
 * role, all enrolled in the Default Study. Kept here as the single source
 * of truth for the smoke + a11y suites.
 */
export const CREDENTIALS: Record<Role, { username: string; password: string; studyOid: string }> = {
  admin: { username: 'manual_admin', password: '12345678', studyOid: '' }, // system admin: no study assignment
  dataManager: { username: 'manual_dm', password: '12345678', studyOid: 'S_DEFAULTS1' },
  investigator: { username: 'manual_investigator', password: '12345678', studyOid: 'S_DEFAULTS1' },
  crc: { username: 'manual_crc', password: '12345678', studyOid: 'S_DEFAULTS1' },
  monitor: { username: 'manual_monitor', password: '12345678', studyOid: 'S_DEFAULTS1' },
}

const CTX = '/LibreClinica'

/**
 * Establish an authenticated session in the given browser context and bind
 * the active study. Uses the context's request client so the JSESSIONID
 * cookie is shared with pages opened afterwards.
 */
export async function login(context: BrowserContext, role: Role): Promise<void> {
  const { username, password, studyOid } = CREDENTIALS[role]
  const req: APIRequestContext = context.request
  const loginRes = await req.post(`${CTX}/j_spring_security_check`, {
    form: { j_username: username, j_password: password },
  })
  // Spring redirects to /MainMenu on success, /login?...error on failure.
  expect(loginRes.url(), `login as ${username} should not land on an error page`).not.toMatch(/error/i)
  // Binding an active study is best-effort: a pure system Administrator has no
  // study_user_role, so this legitimately 4xx's for that account. Study-scoped
  // specs assert reachability themselves; system-level admin routes don't need it.
  if (studyOid) {
    await req.post(`${CTX}/pages/api/v1/me/activeStudy`, {
      data: { oid: studyOid },
      headers: { 'content-type': 'application/json' },
    }).catch(() => undefined)
  }
}

/**
 * Self-cleanup: disable every CRF whose name starts with one of {@link prefixes}.
 * Smoke specs that create CRFs call this in afterAll so a local dev DB doesn't
 * accumulate test fixtures across runs (CI's DB is ephemeral, but locals reuse
 * one). Disable is the only CRF-level teardown the API exposes — it removes the
 * CRF from the default library view. Best-effort: never throws.
 */
export async function purgeCrfsByPrefix(context: BrowserContext, prefixes: string[]): Promise<void> {
  const req = context.request
  const res = await req.get(`${CTX}/pages/api/v1/crfs`).catch(() => undefined)
  if (!res || !res.ok()) return
  const crfs = (await res.json().catch(() => [])) as { oid: string; name: string }[]
  for (const c of crfs) {
    if (prefixes.some((p) => (c.name ?? '').startsWith(p))) {
      await req.post(`${CTX}/pages/api/v1/crfs/${encodeURIComponent(c.oid)}/disable`, {
        data: {},
        headers: { 'content-type': 'application/json' },
      }).catch(() => undefined)
    }
  }
}

/**
 * Log in as {@link role} and navigate to {@link path} (root-relative),
 * asserting we landed on a real view rather than the login bounce or the
 * NotFound page. Returns once the level-1 heading is visible.
 */
export async function loginAndGoto(page: Page, role: Role, path: string): Promise<void> {
  await login(page.context(), role)
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await expect(page, `role ${role} should reach ${path}, not bounce to login`).not.toHaveURL(/\/login/)
  const h1 = page.getByRole('heading', { level: 1 })
  await expect(h1.first()).toBeVisible({ timeout: 15_000 })
  await expect(h1.first(), `${path} should not be the 404 page`).not.toHaveText(/Seite nicht gefunden|Not Found/i)
}

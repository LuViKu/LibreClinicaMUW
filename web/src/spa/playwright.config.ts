import { defineConfig, devices } from '@playwright/test'

/**
 * Phase E.9 — Playwright config.
 *
 * `pnpm test:e2e` runs everything. `pnpm test:a11y:e2e` runs only the
 * @a11y-tagged tests. `pnpm test:smoke:e2e` runs the per-role smoke flows.
 *
 * Base URL: the SPA's dev/build base is `/` (see vite.config.ts), served by
 * the Vite dev server on :5173 which proxies `/LibreClinica/*` to the Spring
 * backend. Specs use root-relative paths (`/crf-library`, …) and log in
 * against the real backend through that proxy (see tests/support/auth.ts).
 * Override the target with E2E_BASE_URL to point at a WAR-served SPA.
 */
export default defineConfig({
  testDir: './tests',
  // Serial: these specs log in against a single dev server + backend; a burst
  // of parallel logins/connections overwhelms the Vite dev server (ECONNREFUSED)
  // and is not worth the flakiness for a small smoke/a11y gate. Determinism wins.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 60_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
})

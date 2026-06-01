import { defineConfig, devices } from '@playwright/test'

/**
 * Phase E.9 — Playwright config.
 *
 * `pnpm test:e2e` runs everything. `pnpm test:a11y:e2e` runs only the
 * @a11y-tagged tests. The base URL points at the Vite dev server; the
 * CI job spawns it before tests start (see .github/workflows/spa.yml
 * when it ships in E.9.2's CI tightening).
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173/LibreClinica/app',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
})

/// <reference types="vitest" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'
import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

/**
 * Phase E.6 — version + build constants surfaced in the SideRail
 * footer and the LoginView. Read at config-load time, env-first so
 * the release-image workflow can stamp the real GitHub release tag
 * + commit SHA + publish date by passing build-args to the Docker
 * build:
 *
 *   APP_VERSION ← $APP_VERSION env (from --build-arg) ▸ package.json
 *                 ▸ 'unknown'
 *   BUILD_HASH  ← $BUILD_HASH env ▸ `git rev-parse --short HEAD`
 *                 ▸ 'dev'
 *   BUILD_DATE  ← $BUILD_DATE env (ISO yyyy-MM-dd) ▸ today
 *
 * The env override is what makes the release tag dynamic — the
 * Dockerfile accepts the build-arg, sets the matching ENV, and the
 * Vite config picks it up here. Local `pnpm dev` and the smoke-build
 * `mvn package` without build-args fall through to package.json + git.
 */
function readPackageVersion(): string {
  try {
    const raw = readFileSync(
      fileURLToPath(new URL('./package.json', import.meta.url)),
      'utf-8',
    )
    return (JSON.parse(raw).version as string | undefined) ?? 'unknown'
  } catch {
    return 'unknown'
  }
}
function gitShortSha(): string {
  try {
    return execSync('git rev-parse --short HEAD', {
      cwd: fileURLToPath(new URL('.', import.meta.url)),
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .toString()
      .trim() || 'dev'
  } catch {
    return 'dev'
  }
}
function todayIso(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
function normaliseDate(raw: string): string {
  // GitHub's release.published_at + repository.updated_at come back as
  // full ISO timestamps (`yyyy-MM-ddTHH:mm:ssZ`). The SPA footer wants
  // just the calendar date — slice the first 10 chars when the input
  // matches the ISO timestamp shape; otherwise pass through.
  const m = raw.match(/^(\d{4}-\d{2}-\d{2})/)
  return m ? m[1]! : raw
}
function normaliseHash(raw: string): string {
  // GitHub passes the full 40-char `github.sha`; the footer uses the
  // short 7-char form everywhere else. Truncate to match.
  return /^[0-9a-f]{40}$/.test(raw) ? raw.slice(0, 7) : raw
}
const APP_VERSION = (process.env.APP_VERSION ?? '').trim() || readPackageVersion()
const BUILD_HASH = normaliseHash((process.env.BUILD_HASH ?? '').trim()) || gitShortSha()
const BUILD_DATE = normaliseDate((process.env.BUILD_DATE ?? '').trim()) || todayIso()

/**
 * Phase E.1 (2026-05-30): Vue 3 + Vite + Tailwind v4.
 *
 * The SPA bundle is consumed by the WAR build — `mvn package` runs
 * `pnpm install` + `pnpm build` via the Frontend Maven Plugin
 * declared in web/pom.xml. Vite output lands at
 * `web/src/main/webapp/app/`, where Tomcat serves it from
 * `/LibreClinica/app/index.html` at runtime.
 *
 * Dev mode (`pnpm dev`) runs the Vite dev server at
 * http://127.0.0.1:5173 with a proxy to the running Spring Boot
 * backend at http://127.0.0.1:8080/LibreClinica — that way the SPA
 * can talk to real `@RestController` endpoints during development
 * without CORS noise.
 */
export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(APP_VERSION),
    __BUILD_HASH__: JSON.stringify(BUILD_HASH),
    __BUILD_DATE__: JSON.stringify(BUILD_DATE),
  },
  plugins: [
    vue(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // 2026-07-06 — SPA served at the clean site root behind the nginx reverse
  // proxy (ecrf.augen.meduniwien.ac.at/login instead of /LibreClinica/app/login).
  // The built assets reference '/assets/…'; the WAR still physically serves
  // them under /LibreClinica/app/, so nginx maps '/assets/' → the WAR path
  // (deploy/nginx/ecrf.conf). NOTE: the app is now only reachable THROUGH the
  // proxy — a direct :8080/LibreClinica/app load can't resolve the root-based
  // asset URLs. See deploy/nginx/README.md.
  base: '/',
  build: {
    outDir: fileURLToPath(new URL('../main/webapp/app', import.meta.url)),
    emptyOutDir: true,
    sourcemap: true,
    target: 'es2022',
    cssCodeSplit: true,
    rollupOptions: {
      output: {
        manualChunks: {
          // Keep Vue / router / Pinia in one chunk; the rest splits per-route.
          vendor: ['vue', 'vue-router', 'pinia', 'vue-i18n'],
        },
      },
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // Hot-module-reload toggle. Default on (normal dev). Set
    // VITE_DISABLE_HMR=1 to turn it off — Vite then never pushes an HMR
    // patch or a full page reload when source files change, so an editing
    // session in another window can't reload a browser tab that an
    // automated agent (e.g. Claude-in-Chrome) is driving. Requires a dev
    // server restart to take effect (changing this file already restarts it).
    hmr: process.env.VITE_DISABLE_HMR === '1' ? false : true,
    proxy: {
      // Forward backend-bound calls to the Spring Boot WAR running in
      // Docker Compose. The SPA never talks to the backend through `/app/`;
      // it calls `/LibreClinica/MainMenu`, `/LibreClinica/pages/*`,
      // `/LibreClinica/actuator/health`, etc. (the WAR's context path is
      // `/LibreClinica`). The api/client.ts wrapper prepends the prefix
      // transparently. Keep the alternation in sync with the OpenAPI
      // inventory in docs/development/modernization/phase-e/api-surface.md.
      '^/LibreClinica/(MainMenu|pages|actuator|j_spring_security_check|j_spring_security_logout|Logout|Login|ListStudySubjects|ViewStudySubject)': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        // Spring's LoginUrlAuthenticationEntryPoint + SavedRequestAwareAuthenticationSuccessHandler
        // emit absolute Location headers (e.g.
        // `Location: http://127.0.0.1:8080/LibreClinica/MainMenu`). Without
        // rewrite the browser bounces from :5173 to :8080 cross-origin,
        // losing the JSESSIONID and breaking the SPA's authenticated state.
        // `autoRewrite: true` rewrites the host/port in Location headers to
        // match the inbound Host (i.e. 127.0.0.1:5173), keeping the whole
        // login/302 flow same-origin.
        autoRewrite: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setupTests.ts'],
    // tests/a11y/spa-routes.spec.ts + tests/manual/** are Playwright specs
    // that pull in @playwright/test (a non-vitest runner). When vitest's
    // default include picks them up the files fail to collect. Exclude
    // here; Playwright runs out-of-band.
    exclude: ['**/node_modules/**', '**/dist/**', 'tests/a11y/spa-routes.spec.ts', 'tests/manual/**'],
  },
})

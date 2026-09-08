import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { useAuthStore } from '@/stores/auth'
import { useErrorsStore } from '@/stores/errors'
import { useConnectionStore } from '@/stores/connection'
import { useClientLogsStore } from '@/stores/clientLogs'
// Phase E.X studyModules (2026-06-20): i18n instance lifted to its own
// module so the study-module store can call
// {@code i18n.global.mergeLocaleMessage} on activation without
// re-entering main.ts (which would create an import cycle).
import { i18n } from '@/i18n'

// 2026-07-06 — vendor the three MUW-branded typefaces at build time
// via @fontsource-variable/* instead of loading from Google Fonts. The
// eCRF runs in a clinical environment where every runtime fetch to a
// public CDN leaks the operator's IP + Referer, and where an
// air-gapped or CDN-blocked network would silently break the SPA's
// typography. The variable-font packages ship a single OTC file per
// family (~120 kB gzipped total for all three) covering every weight
// + optical-size axis referenced by style.css's @font-face aliases.
import '@fontsource-variable/inter'
import '@fontsource-variable/newsreader'
import '@fontsource-variable/jetbrains-mono'

import './style.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(i18n)

/**
 * Phase E hardening — A5 (2026-06-10): global Vue error boundary.
 *
 * Without this hook, any uncaught throw inside a `<script setup>`,
 * render function, watcher, or computed surfaces as `Uncaught (in
 * promise)` in the dev tools and vanishes silently in production —
 * the user sees a blank panel, no toast, no audit trail. Routing the
 * error into the `errors` store gives `GlobalErrorToast` a chance to
 * render a user-facing message + the A4 `reqId` (when present), and
 * keeps the console log for the sysadmin runbook.
 *
 * Phase E follow-up (2026-06-11) — pipe the same throw into
 * `clientLogs` with level=`uncaught` so the bug-report dialog can
 * attach it on the next operator submission. The two stores stay
 * separate concerns: `errors` drives the toast (last 20), `clientLogs`
 * feeds the email (last 100).
 */
app.config.errorHandler = (err, _vm, info) => {
  useErrorsStore(pinia).push(err, info)
  useClientLogsStore(pinia).push({
    level: 'uncaught',
    message: truncateForLog(formatError(err, info)),
  })
  // eslint-disable-next-line no-console
  console.error('[GlobalError]', err, info)
}

/**
 * Phase E follow-up (2026-06-11) — monkey-patch `console.error` +
 * `console.warn` to mirror the message into the `clientLogs` ring
 * buffer before delegating to the original.
 *
 * The interceptor runs in any environment that has a `console` (i.e.
 * always), and is a no-op if it's already been installed — guards
 * against double-patching in HMR + future tests that boot the app
 * twice in one process.
 */
type ConsoleFn = (...args: unknown[]) => void
interface PatchedConsole extends Console {
  __muwClientLogsPatched?: true
}
function truncateForLog(value: string): string {
  // Console output can be huge — stack traces + serialised JSON dumps
  // routinely run into the tens of kilobytes. Cap at 500 chars per
  // entry so the in-memory ring buffer stays trivially small even when
  // the operator hits a render loop.
  const MAX = 500
  if (value.length <= MAX) return value
  return value.slice(0, MAX) + ' …(truncated)'
}
function formatConsoleArgs(args: unknown[]): string {
  return args
    .map((a) => {
      if (a instanceof Error) return a.stack ?? `${a.name}: ${a.message}`
      if (typeof a === 'string') return a
      try {
        return JSON.stringify(a)
      } catch {
        return String(a)
      }
    })
    .join(' ')
}
function formatError(err: unknown, info?: string): string {
  const head = err instanceof Error
    ? (err.stack ?? `${err.name}: ${err.message}`)
    : typeof err === 'string'
      ? err
      : (() => {
          try {
            return JSON.stringify(err)
          } catch {
            return String(err)
          }
        })()
  return info ? `${head}  [info=${info}]` : head
}
if (typeof console !== 'undefined') {
  const patched = console as PatchedConsole
  if (!patched.__muwClientLogsPatched) {
    const originalError: ConsoleFn = console.error.bind(console)
    const originalWarn: ConsoleFn = console.warn.bind(console)
    console.error = ((...args: unknown[]) => {
      try {
        useClientLogsStore(pinia).push({
          level: 'error',
          message: truncateForLog(formatConsoleArgs(args)),
        })
      } catch {
        // Pinia not ready / store throw — never let logging break the host call.
      }
      originalError(...args)
    }) as Console['error']
    console.warn = ((...args: unknown[]) => {
      try {
        useClientLogsStore(pinia).push({
          level: 'warn',
          message: truncateForLog(formatConsoleArgs(args)),
        })
      } catch {
        // see above.
      }
      originalWarn(...args)
    }) as Console['warn']
    patched.__muwClientLogsPatched = true
  }
}

/**
 * Phase E hardening — B4 (2026-06-10): wire the browser-level
 * connectivity events to the `connection` store. The store is the
 * single source of truth that `ConnectionBanner` and (future)
 * per-form `:disabled` integration consult. `api/client.ts` also
 * flips the store when an `ApiNetworkError` is thrown — that covers
 * the captive-portal / DNS-fail case where `navigator.onLine` lies.
 *
 * We only re-enable on the browser's `online` event, not on a
 * successful retry, so a single lucky fetch on a still-flaky network
 * does not prematurely dismiss the banner.
 */
if (typeof window !== 'undefined') {
  const connection = useConnectionStore(pinia)
  window.addEventListener('online', () => connection.markOnline())
  window.addEventListener('offline', () => connection.markOffline())
}

/**
 * Phase E.6 (2026-06-03): bootstrap auth state BEFORE installing the
 * router. `app.use(router)` calls `router.install(app)` which kicks
 * off the initial navigation immediately — earlier than the auth
 * store has finished hydrating from /me. Until 2026-06-03 the
 * sequence was install-router → bootstrap → mount, which fired the
 * global guard with state='anonymous' and redirected every refresh
 * to /login regardless of session validity.
 *
 * The fix: install pinia + i18n first, run bootstrap, then install
 * the router so its first navigation sees the resolved state.
 */
/**
 * Pluggable study-module SPI — route registration.
 *
 * Every manifest's routes are prefixed with
 * {@code /studies/:studyOid/modules/<protocolType-lowercased>} so the
 * URL space is namespaced by-study and-by-module. The {@code
 * meta.studyModule} stamp lets the router guard reject navigations
 * whose target study doesn't match the active study's protocol_type
 * (e.g. deep-linking into /studies/.../modules/namd while bound to a
 * GA study would 403 in the UI before the component imports any
 * code).
 *
 * Runs before {@code app.use(router)} so the initial navigation can
 * resolve module routes — useful for refresh on a deep link into the
 * workspace.
 */
import { STUDY_MODULES } from '@/studyModules/registry'
for (const mod of STUDY_MODULES) {
  for (const route of mod.routes) {
    router.addRoute({
      ...route,
      path: `/studies/:studyOid/modules/${mod.protocolType.toLowerCase()}${route.path}`,
      meta: { ...(route.meta ?? {}), studyModule: mod.protocolType },
    })
  }
}

// 2026-06-24 user-feedback round — eager-load every registered
// study module's i18n bundle at boot. The studyModules store
// previously deferred this to {@code watch(activeModule, ..., {immediate: true})},
// but that activation watcher only fires once a component invokes
// {@code useStudyModuleStore()}, AND the {@code await loadI18n()}
// settles asynchronously — a workspace render that landed before
// the merge surfaced raw {@code studyModules.namd.tab.viewer}-style
// keys (the user's 2026-06-24 console trace). Loading at boot
// guarantees the bundle is present before any view templates run.
// Bundle size cost is bounded — five module locales today, each
// well under 30 kB JSON. Merge failures are non-fatal (the keys
// fall back to the literal at runtime, with the missing-key warning
// already in place).
for (const mod of STUDY_MODULES) {
  if (!mod.loadI18n) continue
  void (async () => {
    try {
      const payload = await mod.loadI18n!()
      i18n.global.mergeLocaleMessage('de', payload.de)
      i18n.global.mergeLocaleMessage('de-AT', payload.de)
      i18n.global.mergeLocaleMessage('en', payload.en)
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn('[studyModules] eager loadI18n failed for', mod.protocolType, e)
    }
  })()
}

useAuthStore(pinia).bootstrap().finally(() => {
  app.use(router)
  app.mount('#app')
})

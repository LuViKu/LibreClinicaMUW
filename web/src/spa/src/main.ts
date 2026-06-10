import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import App from './App.vue'
import router from './router'
import { useAuthStore } from '@/stores/auth'
import { useErrorsStore } from '@/stores/errors'
import deMessages from './locales/de.json'
import enMessages from './locales/en.json'

import './style.css'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'en',
  // Phase E hardening — A5 (2026-06-10): silence i18n warning spam in
  // production builds. Vue-i18n logs both "missing key" and "fallback"
  // warnings to the console by default; in dev those are signal (a
  // typo'd key shows up immediately), but in prod they're noise that
  // crowds out genuine errors. Mirror the Vue-i18n recommendation:
  // gate both on `!import.meta.env.PROD`.
  missingWarn: !import.meta.env.PROD,
  fallbackWarn: !import.meta.env.PROD,
  messages: {
    'de-AT': deMessages,
    en: enMessages,
  },
})

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
 */
app.config.errorHandler = (err, _vm, info) => {
  useErrorsStore(pinia).push(err, info)
  // eslint-disable-next-line no-console
  console.error('[GlobalError]', err, info)
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
useAuthStore(pinia).bootstrap().finally(() => {
  app.use(router)
  app.mount('#app')
})

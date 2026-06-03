import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import App from './App.vue'
import router from './router'
import { useAuthStore } from '@/stores/auth'
import deMessages from './locales/de.json'
import enMessages from './locales/en.json'

import './style.css'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'en',
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

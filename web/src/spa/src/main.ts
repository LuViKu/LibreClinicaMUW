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
app.use(router)
app.use(i18n)

/**
 * Phase E.8 (2026-05-30): bootstrap the auth store before the first
 * navigation so the global router guard sees the correct state. The
 * mock `bootstrap()` reads sessionStorage; once the
 * `GET /pages/api/v1/me` adapter lands it will be replaced with an
 * `apiGet`. The guard runs after this returns; if the user lands on
 * a protected route while anonymous, the guard redirects to /login.
 */
useAuthStore(pinia).bootstrap().finally(() => {
  app.mount('#app')
})

/**
 * ChangePasswordView — forced or by choice.
 *
 * Forced (first login, expired password), the banner says why and there is
 * no way out but a new password. Opened from the profile menu, nothing is
 * forced: the banner is left out and a cancel button leads back.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'

import ChangePasswordView from '@/views/ChangePasswordView.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/change-password', name: 'change-password', component: ChangePasswordView },
    ],
  })
  await router.push('/change-password')
  await router.isReady()
  return mount(ChangePasswordView, { global: { plugins: [router, i18n] } })
}

function signIn(mustChangePassword: boolean) {
  useAuthStore().user = {
    username: 'root', source: 'local', mustChangePassword,
    passwordChangeReason: mustChangePassword ? 'first-login' : null,
  } as unknown as ReturnType<typeof useAuthStore>['user']
}

describe('ChangePasswordView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('explains a forced change and offers no way out', async () => {
    signIn(true)
    const w = await mountView()
    expect(w.text()).toContain('Welcome — please set a new password')
    expect(w.find('[data-testid="change-password-cancel"]').exists()).toBe(false)
  })

  it('leaves out the banner and offers a cancel button when opened by choice', async () => {
    signIn(false)
    const w = await mountView()
    expect(w.text()).not.toContain('Welcome — please set a new password')
    expect(w.text()).not.toContain('Your password has expired')
    expect(w.get('[data-testid="change-password-cancel"]').text()).toBe('Cancel')
  })
})

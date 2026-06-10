/**
 * Phase E hardening — A5 (2026-06-10).
 *
 * Pins the load-bearing contract for `NotFound.vue`:
 *   - the German heading "Seite nicht gefunden" renders, plus the CTA
 *     ("Zur Startseite");
 *   - the CTA navigates to `/` (the global home route).
 *
 * Plus a router-level assertion: an unknown path resolves to the
 * `not-found` named route via the `:pathMatch(.*)*` catch-all.
 */
import { describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createI18n } from 'vue-i18n'

import NotFound from '@/views/NotFound.vue'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>home</div>' } },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFound },
    ],
  })
}

describe('NotFound view', () => {
  it('renders the German heading and the CTA', async () => {
    const router = makeRouter()
    router.push('/somewhere-bogus')
    await router.isReady()
    const w = mount(NotFound, {
      global: { plugins: [router, i18n] },
    })
    expect(w.text()).toContain('Seite nicht gefunden')
    const cta = w.find('[data-testid="not-found-cta"]')
    expect(cta.exists()).toBe(true)
    expect(cta.text()).toBe('Zur Startseite')
  })

  it('CTA navigates to /', async () => {
    const router = makeRouter()
    router.push('/somewhere-bogus')
    await router.isReady()
    const w = mount(NotFound, {
      global: { plugins: [router, i18n] },
    })
    // RouterLink renders as <a href=".."> in the test DOM. Asserting
    // on the resolved href is the stable surface — clicking would
    // require a full <RouterView> harness which adds noise without
    // adding signal beyond "to=/".
    const cta = w.find('[data-testid="not-found-cta"]')
    expect(cta.element.tagName).toBe('A')
    expect(cta.attributes('href')).toBe('/')
  })

  it('router catch-all resolves unknown paths to the not-found route', async () => {
    const router = makeRouter()
    router.push('/this/path/does/not/exist')
    await router.isReady()
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('not-found')
  })
})

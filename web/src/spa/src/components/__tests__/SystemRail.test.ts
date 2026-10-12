import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

import SystemRail from '@/components/SystemRail.vue'
import deMessages from '@/locales/de.json'

/**
 * The System rail lists the eight instance-level pages in a fixed order,
 * highlights the current one, and is a labelled landmark — the same contract
 * as the Studienaufbau rail, minus the study and role scoping it does not need.
 * The locations (Standorte) follow the studies: both are about what the
 * platform runs, and a study is active at a location.
 */
const i18n = createI18n({ legacy: false, locale: 'de', messages: { de: deMessages } })

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push(path)
  await router.isReady()
  return mount(SystemRail, { global: { plugins: [router, i18n] } })
}

describe('SystemRail', () => {
  it('lists the eight system pages, overview first, and marks the current one', async () => {
    const w = await mountAt('/admin/config')
    const ids = w.findAll('a[data-testid^="rail-"]').map((a) => a.attributes('data-testid'))
    expect(ids).toEqual([
      'rail-status',
      'rail-studies',
      'rail-locations',
      'rail-audit',
      'rail-login-history',
      'rail-password-policy',
      'rail-config',
      'rail-jobs',
    ])
    expect(w.get('[data-testid="rail-studies"]').attributes('href')).toBe('/admin/studies')
    expect(w.get('[data-testid="rail-config"]').attributes('aria-current')).toBe('page')
    expect(w.get('[data-testid="rail-status"]').attributes('aria-current')).toBeUndefined()
    expect(w.get('[data-testid="rail-audit"]').attributes('href')).toBe('/system/audit-log')
    expect(w.get('nav').attributes('aria-label')).toBe(deMessages.a11y.sectionNavigation)
  })

  it('links the locations page and marks it current there, but not on the studies page', async () => {
    const w = await mountAt('/admin/locations')
    const link = w.get('[data-testid="rail-locations"]')
    expect(link.attributes('href')).toBe('/admin/locations')
    expect(link.attributes('aria-current')).toBe('page')
    expect(link.text()).toBe(deMessages.adminLocations.title)
    expect(w.get('[data-testid="rail-studies"]').attributes('aria-current')).toBeUndefined()

    const onStudies = await mountAt('/admin/studies')
    expect(onStudies.get('[data-testid="rail-locations"]').attributes('aria-current')).toBeUndefined()
  })

  it('marks the audit trail current on its page, though it lives outside /admin', async () => {
    const w = await mountAt('/system/audit-log')
    expect(w.get('[data-testid="rail-audit"]').attributes('aria-current')).toBe('page')
    expect(w.get('[data-testid="rail-status"]').attributes('aria-current')).toBeUndefined()
  })

  it('reads the page titles the pages themselves use, so the rail and the H1 agree', async () => {
    const w = await mountAt('/admin/system-status')
    expect(w.get('[data-testid="rail-status"]').text()).toBe(deMessages.adminSystemStatus.title)
    expect(w.get('[data-testid="rail-login-history"]').text()).toBe(deMessages.adminLoginHistory.title)
    expect(w.get('[data-testid="rail-login-history"]').attributes('href')).toBe('/admin/login-history')
    expect(w.get('[data-testid="rail-jobs"]').text()).toBe(deMessages.adminJobs.title)
    expect(w.text()).toContain(deMessages.system.rail.heading)
  })
})

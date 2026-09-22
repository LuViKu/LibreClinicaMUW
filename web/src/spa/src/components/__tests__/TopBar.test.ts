import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

import TopBar from '@/components/TopBar.vue'
import enMessages from '@/locales/en.json'

/**
 * The top bar is brand + primary navigation + user, and nothing else. It used
 * to carry a breadcrumb as well, in the same type size directly after the
 * navigation, so the study name read as a sixth destination and the trail
 * wrapped into two-line crumbs at laptop widths. Pinned here: the navigation
 * is a labelled landmark and the only <nav>, the current destination is
 * marked by prefix (home only on the home page), the active study is a chip
 * beside the user that leads to the picker, and the version line lives in the
 * profile menu now that the side rail no longer hosts it.
 */
const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: enMessages } })

async function mountAt(path: string, extra: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/subjects', component: { template: '<div />' } },
      { path: '/subjects/:id', component: { template: '<div />' } },
      { path: '/notes', component: { template: '<div />' } },
      { path: '/pick-study', component: { template: '<div />' } },
      { path: '/admin/system-status', component: { template: '<div />' } },
      { path: '/system/audit-log', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(TopBar, {
    props: {
      userName: 'demo',
      userRoles: ['Investigator'],
      navItems: [
        { id: 'home', to: '/', label: 'Home' },
        { id: 'subjects', to: '/subjects', label: 'Subject Matrix' },
        { id: 'notes', to: '/notes', label: 'Notes' },
      ],
      ...extra,
    },
    global: { plugins: [router, i18n] },
  })
}

describe('TopBar', () => {
  it('renders the destinations as the one labelled navigation landmark', async () => {
    const w = await mountAt('/notes')
    const navs = w.findAll('nav')
    expect(navs).toHaveLength(1)
    expect(navs[0].attributes('aria-label')).toBe(enMessages.topBar.primaryNav)
    expect(w.get('[data-testid="topbar-nav-subjects"]').attributes('href')).toBe('/subjects')
  })

  it('marks the current destination, by prefix', async () => {
    const w = await mountAt('/subjects/M-001')
    expect(w.get('[data-testid="topbar-nav-subjects"]').attributes('aria-current')).toBe('page')
    expect(w.get('[data-testid="topbar-nav-home"]').attributes('aria-current')).toBeUndefined()
  })

  it('marks home only on the home page', async () => {
    const w = await mountAt('/')
    expect(w.get('[data-testid="topbar-nav-home"]').attributes('aria-current')).toBe('page')
    expect(w.get('[data-testid="topbar-nav-subjects"]').attributes('aria-current')).toBeUndefined()
  })

  it('shows the active study as a chip that leads to the picker', async () => {
    const w = await mountAt('/notes', { studyName: 'HealthAEye', studyTo: '/pick-study' })
    const chip = w.get('[data-testid="topbar-study"]')
    expect(chip.text()).toContain('HealthAEye')
    expect(chip.attributes('href')).toBe('/pick-study')
    // Without a picker it is plain text, not a dead link.
    const plain = await mountAt('/notes', { studyName: 'HealthAEye' })
    expect(plain.get('[data-testid="topbar-study"]').element.tagName).toBe('SPAN')
  })

  it('offers the System section to administrators only, beside the study chip', async () => {
    const admin = await mountAt('/notes', { userRoles: ['Administrator'] })
    const link = admin.get('[data-testid="topbar-system-link"]')
    expect(link.attributes('href')).toBe('/admin/system-status')
    expect(link.text()).toBe(enMessages.topBar.system)
    expect(link.attributes('aria-current')).toBeUndefined()
    // Not a primary-nav destination: the one <nav> landmark stays the primary navigation.
    expect(admin.findAll('nav')).toHaveLength(1)
    const investigator = await mountAt('/notes')
    expect(investigator.find('[data-testid="topbar-system-link"]').exists()).toBe(false)
  })

  it('marks the System entry current across both of its path prefixes', async () => {
    const onStatus = await mountAt('/admin/system-status', { userRoles: ['Administrator'] })
    expect(onStatus.get('[data-testid="topbar-system-link"]').attributes('aria-current')).toBe('page')
    const onAudit = await mountAt('/system/audit-log', { userRoles: ['Administrator'] })
    expect(onAudit.get('[data-testid="topbar-system-link"]').attributes('aria-current')).toBe('page')
  })

  it('keeps the version and build in the profile menu', async () => {
    const w = await mountAt('/notes', { version: '1.5.0-beta.6-muw', buildHash: 'abc1234', buildDate: '20-09-2026' })
    expect(w.find('[data-testid="topbar-version"]').exists()).toBe(false)
    await w.get('[data-testid="topbar-profile-trigger"]').trigger('click')
    const line = w.get('[data-testid="topbar-version"]')
    expect(line.text()).toContain('1.5.0-beta.6-muw')
    expect(line.text()).toContain('abc1234')
  })
})

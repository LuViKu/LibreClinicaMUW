import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

import TopBar from '@/components/TopBar.vue'
import enMessages from '@/locales/en.json'

/**
 * The top bar carries the role's main destinations now. Two things are
 * pinned: the links exist as a labelled navigation landmark distinct from
 * the breadcrumb (two unlabelled <nav>s trip axe's landmark-unique rule), and
 * the home link is current only on the home page — "/" is a prefix of every
 * path, and a home link that is always highlighted tells the operator nothing.
 */
const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: enMessages } })

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/subjects', component: { template: '<div />' } },
      { path: '/subjects/:id', component: { template: '<div />' } },
      { path: '/notes', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(TopBar, {
    props: {
      userName: 'demo',
      userRoles: ['Investigator'],
      breadcrumb: [{ label: 'Subjects', to: '/subjects' }, { label: 'M-001' }],
      navItems: [
        { id: 'home', to: '/', label: 'Home' },
        { id: 'subjects', to: '/subjects', label: 'Subject Matrix' },
        { id: 'notes', to: '/notes', label: 'Notes' },
      ],
    },
    global: { plugins: [router, i18n] },
  })
}

describe('TopBar primary navigation', () => {
  it('renders the destinations as a labelled navigation landmark, distinct from the breadcrumb', async () => {
    const w = await mountAt('/notes')
    const navs = w.findAll('nav')
    const labels = navs.map((n) => n.attributes('aria-label'))
    expect(labels).toContain(enMessages.topBar.primaryNav)
    expect(labels).toContain(enMessages.topBar.breadcrumbNav)
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
})

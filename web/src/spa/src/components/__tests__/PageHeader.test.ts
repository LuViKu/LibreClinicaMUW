import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

import PageHeader from '@/components/PageHeader.vue'
import enMessages from '@/locales/en.json'

/**
 * The page header is the one place a page says where it is. A trail lists
 * the ancestors as links and never the page itself — the H1 is the page —
 * and a page that keeps its own heading row can render the trail alone.
 */
const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: enMessages } })

async function mountWith(props: Record<string, unknown>, slots: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/')
  await router.isReady()
  return mount(PageHeader, { props, slots, global: { plugins: [router, i18n] } })
}

describe('PageHeader', () => {
  it('renders the trail as a labelled navigation of links, ancestors only', async () => {
    const w = await mountWith({
      title: 'V1 Inclusion',
      trail: [
        { label: 'Studienteilnehmer', to: '/subjects' },
        { label: 'M-007', to: '/subjects/M-007' },
      ],
    })
    const nav = w.get('[data-testid="page-trail"]')
    expect(nav.attributes('aria-label')).toBe(enMessages.pageHeader.trail)
    const links = nav.findAll('a')
    expect(links.map((a) => a.text())).toEqual(['Studienteilnehmer', 'M-007'])
    expect(links[1].attributes('href')).toBe('/subjects/M-007')
    expect(w.get('h1').text()).toBe('V1 Inclusion')
    expect(nav.text()).not.toContain('V1 Inclusion')
  })

  it('renders an eyebrow line for a flat page, and no navigation landmark', async () => {
    const w = await mountWith({ title: 'Regeln', eyebrow: 'Prüf- und Ablaufregeln' })
    expect(w.find('nav').exists()).toBe(false)
    expect(w.get('[data-testid="page-eyebrow"]').text()).toBe('Prüf- und Ablaufregeln')
    expect(w.get('h1').text()).toBe('Regeln')
  })

  it('renders the trail alone when the page keeps its own heading row', async () => {
    const w = await mountWith({ trail: [{ label: 'Studienteilnehmer', to: '/subjects' }] })
    expect(w.find('nav').exists()).toBe(true)
    expect(w.find('h1').exists()).toBe(false)
  })

  it('places badge and actions where a page expects them', async () => {
    const w = await mountWith(
      { title: 'M-007' },
      { badge: '<span data-testid="b">Nicht signiert</span>', actions: '<button data-testid="a">Export</button>' },
    )
    expect(w.get('h1').find('[data-testid="b"]').exists()).toBe(true)
    expect(w.find('[data-testid="a"]').exists()).toBe(true)
  })
})

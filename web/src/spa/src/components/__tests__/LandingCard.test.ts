/**
 * Phase E.6 (2026-06-03) → Multi-role per (user, study) — M2 (2026-06-08).
 *
 * LandingCard switched from a single {@code roleVariant} string to a
 * non-empty {@code roleVariants} list backed by {@link RoleDots}.
 * The tests pin the contract the de-duplicated home catalogue
 * depends on:
 *
 *  - the rendered dots come from {@code roleVariants} (one per entry),
 *  - the first variant drives the {@code data-testid} accent so the
 *    visual identity reflects the highest-priority role,
 *  - the badge stays hidden when null/zero (prevents the "0 pending"
 *    noise from eager-loaded empty stores),
 *  - the title + description render verbatim (the component is purely
 *    presentational; i18n happens at the caller).
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import LandingCard from '../LandingCard.vue'

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/notes', name: 'notes', component: { template: '<div />' } },
    ],
  })
}

function mountCard(props: Record<string, unknown>) {
  const router = makeRouter()
  return mount(LandingCard, {
    global: { plugins: [router] },
    props: {
      to: { name: 'notes' },
      title: 'Notes',
      description: 'Open queries.',
      ...props,
    },
  })
}

describe('LandingCard', () => {
  it('renders one dot for a single role variant', () => {
    const w = mountCard({ roleVariants: ['investigator'] })
    const dots = w.findAll('span[aria-hidden="true"].rounded-full')
    expect(dots.length).toBe(1)
    expect(dots[0].classes()).toContain('bg-muw-teal-500')
  })

  it('renders multiple dots for multiple role variants', () => {
    const w = mountCard({ roleVariants: ['investigator', 'data-manager'] })
    const dots = w.findAll('span[aria-hidden="true"].rounded-full')
    expect(dots.length).toBe(2)
    expect(dots[0].classes()).toContain('bg-muw-teal-500')
    expect(dots[1].classes()).toContain('bg-muw-coral-500')
  })

  it('keys the data-testid off the first (highest-priority) variant', () => {
    const w = mountCard({ roleVariants: ['administrator', 'monitor'] })
    expect(w.attributes('data-testid')).toBe('landing-card-administrator')
  })

  it('renders the title and description verbatim', () => {
    const w = mountCard({
      roleVariants: ['monitor'],
      title: 'Source Data Verification',
      description: 'Completed CRFs awaiting verification.',
    })
    expect(w.text()).toContain('Source Data Verification')
    expect(w.text()).toContain('Completed CRFs awaiting verification.')
  })

  it('hides the badge when value is null', () => {
    const w = mountCard({ roleVariants: ['monitor'], badge: null })
    // The badge span carries the bg-muw-blue text-white rounded-full
    // classes; assert no element matches that combo.
    const badge = w.find('span.bg-muw-blue.text-white.rounded-full')
    expect(badge.exists()).toBe(false)
  })

  it('hides the badge when value is 0 (eager-loaded empty store)', () => {
    const w = mountCard({ roleVariants: ['monitor'], badge: 0 })
    const badge = w.find('span.bg-muw-blue.text-white.rounded-full')
    expect(badge.exists()).toBe(false)
  })

  it('shows the badge when value is a positive number', () => {
    const w = mountCard({ roleVariants: ['monitor'], badge: 7 })
    const badge = w.find('span.bg-muw-blue.text-white.rounded-full')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('7')
  })

  it('renders the optional role label when provided', () => {
    const w = mountCard({ roleVariants: ['investigator'], roleLabel: 'Investigator' })
    expect(w.text()).toContain('Investigator')
  })

  it('omits the role label when not provided', () => {
    const w = mountCard({ roleVariants: ['investigator'] })
    // The role label span has font-medium text-muw-blue classes —
    // absence means the dots are the sole indicator.
    const labelSpans = w.findAll('span.font-medium.text-muw-blue')
    expect(labelSpans.length).toBe(0)
  })
})

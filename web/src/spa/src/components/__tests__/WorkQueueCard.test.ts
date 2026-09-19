import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

import WorkQueueCard from '@/components/WorkQueueCard.vue'

/**
 * A work queue's count is the point of the card, so — unlike a landing
 * badge — it is shown even when it is zero, and never shown as a number
 * before it is known.
 */
function mountCard(count: number | null) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }, { path: '/x', component: { template: '<div />' } }],
  })
  return mount(WorkQueueCard, {
    props: {
      to: '/x',
      roleVariants: ['investigator', 'data-manager'],
      title: 'Open queries',
      description: 'Answer them.',
      count,
      countAriaLabel: count === null ? undefined : `${count} open queries`,
      loadingLabel: 'Count is loading',
    },
    global: { plugins: [router] },
  })
}

describe('WorkQueueCard', () => {
  it('shows the count, zero included', () => {
    expect(mountCard(8).get('[data-testid="queue-count"]').text()).toBe('8')
    expect(mountCard(0).get('[data-testid="queue-count"]').text()).toBe('0')
  })

  it('shows a dash, not a zero, while the count is unknown', () => {
    const w = mountCard(null)
    expect(w.find('[data-testid="queue-count"]').exists()).toBe(false)
    const loading = w.get('[data-testid="queue-count-loading"]')
    expect(loading.text()).toBe('—')
    expect(loading.attributes('aria-label')).toBe('Count is loading')
  })

  it('speaks the count', () => {
    expect(mountCard(8).get('[data-testid="queue-count"]').attributes('aria-label')).toBe('8 open queries')
  })

  it('is one link, carrying the granting roles as dots', () => {
    const w = mountCard(3)
    expect(w.get('a').attributes('href')).toBe('/x')
    expect(w.findAll('span[aria-hidden="true"].rounded-full').length).toBe(2)
  })
})

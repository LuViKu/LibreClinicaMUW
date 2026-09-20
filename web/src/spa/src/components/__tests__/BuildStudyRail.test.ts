import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

import BuildStudyRail from '@/components/BuildStudyRail.vue'
import { useAuthStore } from '@/stores/auth'
import deMessages from '@/locales/de.json'
import type { AuthenticatedUser } from '@/types/auth'

/**
 * The Studienaufbau rail lists the section's pages, highlights the current
 * one, needs the active study for the two study-scoped pages, and hides
 * pages the role cannot open rather than greying them out.
 */
const i18n = createI18n({ legacy: false, locale: 'de', messages: { de: deMessages } })

async function mountAt(path: string, roles: AuthenticatedUser['role'][], oid: string | null = 'S_HAE') {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    username: 'dm',
    role: roles[0],
    activeStudy: oid ? { oid, name: 'HealthAEye', roles } : undefined,
  } as unknown as AuthenticatedUser
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push(path)
  await router.isReady()
  return mount(BuildStudyRail, { global: { plugins: [router, i18n] } })
}

describe('BuildStudyRail', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('lists the section pages in build order and marks the current one', async () => {
    const w = await mountAt('/crf-library', ['Data Manager'])
    const ids = w.findAll('a[data-testid^="rail-"]').map((a) => a.attributes('data-testid'))
    expect(ids).toEqual([
      'rail-tracker', 'rail-study', 'rail-parameters', 'rail-crf-library', 'rail-event-definitions',
      'rail-group-classes', 'rail-rules', 'rail-sites', 'rail-modalities', 'rail-manage-users',
    ])
    expect(w.get('[data-testid="rail-crf-library"]').attributes('aria-current')).toBe('page')
    expect(w.get('[data-testid="rail-tracker"]').attributes('aria-current')).toBeUndefined()
    expect(w.get('[data-testid="rail-study"]').attributes('href')).toBe('/studies/S_HAE/edit')
    expect(w.get('nav').attributes('aria-label')).toBe(deMessages.a11y.sectionNavigation)
  })

  it('marks the tracker only on its own page — it is a prefix of nothing else', async () => {
    const w = await mountAt('/build-study', ['Administrator'])
    expect(w.get('[data-testid="rail-tracker"]').attributes('aria-current')).toBe('page')
  })

  it('leaves out the study-scoped pages when no study is active', async () => {
    const w = await mountAt('/crf-library', ['Data Manager'], null)
    expect(w.find('[data-testid="rail-study"]').exists()).toBe(false)
    expect(w.find('[data-testid="rail-parameters"]').exists()).toBe(false)
  })

  it('hides pages the role cannot open', async () => {
    const w = await mountAt('/manage-users', ['Investigator'])
    expect(w.find('[data-testid="rail-crf-library"]').exists()).toBe(false)
    expect(w.find('[data-testid="rail-rules"]').exists()).toBe(false)
    expect(w.get('[data-testid="rail-manage-users"]').attributes('aria-current')).toBe('page')
  })
})

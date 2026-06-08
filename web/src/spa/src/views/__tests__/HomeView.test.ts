/**
 * Phase E.6 (2026-06-03) → Multi-role per (user, study) — M2 (2026-06-08).
 *
 * Verifies the role-aware landing. The Phase E.6 per-section model
 * was replaced by a de-duplicated catalogue in M2: every visible
 * card carries an {@code allowedRoles} list, and the rendered set is
 * the intersection of those lists with the user's active-study
 * binding. A small change to the catalogue, the role-projection
 * rules (RoleMapper, MeApiController), or the fallback-chain in
 * userRoles should produce a test diff visible here.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

// Stub the API client so the per-store .load() actions resolve to
// empty arrays. The landing doesn't assert on the badge counts —
// only on which cards render — so we don't need a richer stub.
vi.mock('@/api/client', () => ({
  apiGet: vi.fn().mockResolvedValue([]),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {},
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

import HomeView from '@/views/HomeView.vue'
import { useAuthStore } from '@/stores/auth'

// Use the real en.json so aria-labels are the actual rendered strings.
// The test is about which sections RENDER for which role, not about
// i18n correctness — but a real bundle keeps the assertions readable.
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

// Minimal router stub — HomeView's <LandingCard> uses RouterLink, which
// needs a router context to resolve <to>. We don't navigate anywhere;
// memory history is fine.
function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/subjects', name: 'subject-matrix', component: { template: '<div />' } },
      { path: '/subjects/new', name: 'subject-new', component: { template: '<div />' } },
      { path: '/sdv', name: 'sdv', component: { template: '<div />' } },
      { path: '/notes', name: 'notes', component: { template: '<div />' } },
      { path: '/audit-log', name: 'audit-log', component: { template: '<div />' } },
      { path: '/build-study', name: 'build-study', component: { template: '<div />' } },
      { path: '/manage-users', name: 'manage-users', component: { template: '<div />' } },
      { path: '/import-crf-data', name: 'import-crf-data', component: { template: '<div />' } },
      { path: '/rules', name: 'rules', component: { template: '<div />' } },
      { path: '/sites', name: 'sites', component: { template: '<div />' } },
      { path: '/event-definitions', name: 'event-definitions', component: { template: '<div />' } },
      { path: '/crf-library', name: 'crf-library', component: { template: '<div />' } },
      { path: '/group-classes', name: 'group-classes', component: { template: '<div />' } },
      { path: '/studies/:oid/edit', name: 'study-edit', component: { template: '<div />' } },
      { path: '/studies/new', name: 'study-create', component: { template: '<div />' } },
      { path: '/pick-study', name: 'pick-study', component: { template: '<div />' } },
      { path: '/export', name: 'data-export', component: { template: '<div />' } },
    ],
  })
}

type Role = 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC'

function mountWith(roles: Role[] | null) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  if (roles && roles.length > 0) {
    // Highest-priority role wins the top-level singular projection,
    // mirroring the backend MeDto shape.
    const priority: Record<Role, number> = {
      Administrator: 5, 'Data Manager': 4, Monitor: 3, CRC: 2, Investigator: 1,
    }
    const sorted = [...roles].sort((a, b) => priority[b] - priority[a])
    auth.user = {
      username: 'demo',
      displayName: 'Demo',
      email: null,
      role: sorted[0],
      siteLabel: null,
      source: 'local',
      mfaSatisfied: true,
      profileComplete: true,
      locale: null,
      timezone: null,
      mustChangePassword: false,
      passwordChangeReason: null,
      activeStudy: {
        id: 1,
        oid: 'S_DEFAULTS1',
        name: 'Default Study',
        isSite: false,
        role: sorted[0],
        roles: roles,
      },
    }
  } else {
    auth.user = null
  }
  const router = makeRouter()
  const wrapper = mount(HomeView, {
    global: {
      plugins: [pinia, router, i18n],
    },
  })
  return wrapper
}

function cardIds(wrapper: ReturnType<typeof mountWith>): string[] {
  return wrapper.findAll('[data-card-id]').map((el) => el.attributes('data-card-id') as string)
}

beforeEach(() => {
  // No-op; createPinia/setActivePinia run per mount.
})

describe('HomeView role-aware catalogue', () => {
  it('renders no cards while auth is loading (user = null)', async () => {
    const w = mountWith(null)
    await w.vm.$nextTick()
    expect(cardIds(w).length).toBe(0)
  })

  it('renders the Investigator catalogue for a pure Investigator (5 cards)', async () => {
    const w = mountWith(['Investigator'])
    await w.vm.$nextTick()
    const ids = cardIds(w)
    expect(ids).toContain('subject-matrix')
    expect(ids).toContain('subject-new')
    expect(ids).toContain('sign-queue')
    expect(ids).toContain('todays-crfs')
    expect(ids).toContain('notes')
    // Admin/Monitor/DM-only cards must not appear.
    expect(ids).not.toContain('sdv')
    expect(ids).not.toContain('build-study')
    expect(ids).not.toContain('manage-users')
    expect(ids).not.toContain('audit-log')
  })

  it('renders the Investigator catalogue for CRC (CRC inherits Investigator)', async () => {
    const w = mountWith(['CRC'])
    await w.vm.$nextTick()
    const ids = cardIds(w)
    expect(ids).toContain('subject-matrix')
    expect(ids).toContain('subject-new')
    expect(ids).toContain('sign-queue')
    expect(ids).toContain('todays-crfs')
    expect(ids).toContain('notes')
  })

  it('renders the Monitor catalogue for a pure Monitor', async () => {
    const w = mountWith(['Monitor'])
    await w.vm.$nextTick()
    const ids = cardIds(w)
    expect(ids).toContain('subject-matrix')
    expect(ids).toContain('sdv')
    expect(ids).toContain('notes')
    expect(ids).toContain('audit-log')
    expect(ids).not.toContain('subject-new')
    expect(ids).not.toContain('build-study')
    expect(ids).not.toContain('manage-users')
  })

  it('renders the Data Manager catalogue for a pure Data Manager', async () => {
    const w = mountWith(['Data Manager'])
    await w.vm.$nextTick()
    const ids = cardIds(w)
    expect(ids).toContain('build-study')
    expect(ids).toContain('notes')
    expect(ids).toContain('audit-log')
    expect(ids).toContain('import-crf-data')
    expect(ids).toContain('rules')
    expect(ids).toContain('data-export')
    expect(ids).not.toContain('subject-new')
    expect(ids).not.toContain('manage-users')
  })

  it('renders the Administrator catalogue (DM no longer inherited — 2026-06-03)', async () => {
    const w = mountWith(['Administrator'])
    await w.vm.$nextTick()
    const ids = cardIds(w)
    expect(ids).toContain('manage-users')
    expect(ids).toContain('study-create')
    expect(ids).toContain('notes')
    expect(ids).toContain('audit-log')
    expect(ids).toContain('sites')
    expect(ids).toContain('data-export')
    // study-edit only when activeStudyOid is set; our mount sets it.
    expect(ids).toContain('study-edit')
    expect(ids).not.toContain('build-study')
    expect(ids).not.toContain('import-crf-data')
  })

  it('dedups cards across overlapping roles — Investigator + Data Manager sees exactly one Notes card', async () => {
    const w = mountWith(['Investigator', 'Data Manager'])
    await w.vm.$nextTick()
    const ids = cardIds(w)
    const notesCards = ids.filter((id) => id === 'notes')
    expect(notesCards.length).toBe(1)
  })

  it('union of Investigator + Data Manager catalogues — visible cards equal the union of both single-role catalogues', async () => {
    const inv = mountWith(['Investigator'])
    const dm = mountWith(['Data Manager'])
    await inv.vm.$nextTick()
    await dm.vm.$nextTick()
    const both = mountWith(['Investigator', 'Data Manager'])
    await both.vm.$nextTick()

    const expected = new Set<string>([...cardIds(inv), ...cardIds(dm)])
    const got = new Set<string>(cardIds(both))
    expect(got).toEqual(expected)
  })

  it('stacks RoleDots on shared cards — the Notes card for Investigator + Data Manager carries two dots', async () => {
    const w = mountWith(['Investigator', 'Data Manager'])
    await w.vm.$nextTick()
    const notesCard = w.find('[data-card-id="notes"]')
    expect(notesCard.exists()).toBe(true)
    const dots = notesCard.findAll('span[aria-hidden="true"].rounded-full')
    // RoleDots renders one dot per (unique) variant — Investigator
    // (teal) + Data Manager (coral) → two dots.
    expect(dots.length).toBe(2)
  })

  it('collapses CRC + Investigator dots into a single Investigator-coloured dot', async () => {
    const w = mountWith(['Investigator', 'CRC'])
    await w.vm.$nextTick()
    const notesCard = w.find('[data-card-id="notes"]')
    const dots = notesCard.findAll('span[aria-hidden="true"].rounded-full')
    // CRC + Investigator both map to the investigator variant.
    expect(dots.length).toBe(1)
    expect(dots[0].classes()).toContain('bg-muw-teal-500')
  })

  it('hides the switch-study card by default and shows it when availableStudies has more than one entry', async () => {
    const w = mountWith(['Data Manager'])
    // Flush the onMounted Promise.allSettled (loadStudies → []) so it
    // doesn't race with the manual mutation below.
    await flushPromises()
    expect(cardIds(w)).not.toContain('switch-study')

    // Simulate two available studies arriving — Pinia store mutation directly.
    const auth = useAuthStore()
    auth.availableStudies = [
      { id: 1, oid: 'S_A', name: 'Study A', isSite: false, role: 'Data Manager', parentOid: null, parentName: null },
      { id: 2, oid: 'S_B', name: 'Study B', isSite: false, role: 'Data Manager', parentOid: null, parentName: null },
    ] as never
    await w.vm.$nextTick()
    expect(cardIds(w)).toContain('switch-study')
  })
})

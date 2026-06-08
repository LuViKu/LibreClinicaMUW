/**
 * Phase E.6 — role-aware HomeView rendering spec.
 *
 * Verifies that the landing renders the correct per-role section
 * conditional on auth.user.role and hides everything else. A small
 * change to the role-projection rules (RoleMapper, MeApiController's
 * sysadmin shortcut) should produce a test diff visible here.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

// Stub the API client so the per-store .load() actions resolve to
// empty arrays. The landing doesn't assert on the badge counts —
// only on which sections render — so we don't need a richer stub.
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
      { path: '/modalities', name: 'modalities', component: { template: '<div />' } },
    ],
  })
}

function mountWith(role: string | null) {
  const pinia = createPinia()
  setActivePinia(pinia)
  // Seed auth.user BEFORE mount so onMounted-time computeds see the
  // role and dispatch the right .load() calls.
  const auth = useAuthStore()
  if (role) {
    auth.user = {
      username: 'demo',
      displayName: 'Demo',
      email: null,
      role: role as 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC',
      siteLabel: null,
      source: 'local',
      mfaSatisfied: true,
      profileComplete: true,
      locale: null,
      timezone: null,
      activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
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

beforeEach(() => {
  // The createTestingPinia stub replaces all actions with no-op spies,
  // so the onMounted .load() calls don't actually fire network requests.
})

describe('HomeView role-aware sections', () => {
  it('hides every per-role section while auth is loading (role = null)', async () => {
    const w = mountWith(null)
    await w.vm.$nextTick()
    expect(w.find('[aria-label="Investigator workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Monitor workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Data Manager workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Administrator workflows"]').exists()).toBe(false)
  })

  it('shows only the Investigator section for Investigator', async () => {
    const w = mountWith('Investigator')
    await w.vm.$nextTick()
    expect(w.find('[aria-label="Investigator workflows"]').exists()).toBe(true)
    expect(w.find('[aria-label="Monitor workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Data Manager workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Administrator workflows"]').exists()).toBe(false)
  })

  it('renders the Investigator landing without scheduleVisit and with the My queries card (Y2 follow-up)', async () => {
    const w = mountWith('Investigator')
    await w.vm.$nextTick()
    const section = w.find('[aria-label="Investigator workflows"]')
    expect(section.exists()).toBe(true)
    const cards = section.findAll('a')
    // Subject Matrix · Add Subject · Sign-pending · Today's CRFs · My queries
    expect(cards.length).toBe(5)
    const titles = cards.map((c) => c.text())
    expect(titles.some((t) => t.includes('My queries'))).toBe(true)
    expect(titles.some((t) => t.includes('Schedule a visit'))).toBe(false)
  })

  it('routes the Investigator queries card to /notes with assignedTo=current username', async () => {
    const w = mountWith('Investigator')
    await w.vm.$nextTick()
    const section = w.find('[aria-label="Investigator workflows"]')
    const queriesCard = section.findAll('a').find((a) => a.text().includes('My queries'))
    expect(queriesCard).toBeDefined()
    // RouterLink renders the resolved href; the route stub registers
    // /notes with no params and the assignedTo lands in the query string.
    expect(queriesCard!.attributes('href')).toContain('/notes')
    expect(queriesCard!.attributes('href')).toContain('assignedTo=demo')
  })

  it('shows only the Monitor section for Monitor', async () => {
    const w = mountWith('Monitor')
    await w.vm.$nextTick()
    expect(w.find('[aria-label="Investigator workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Monitor workflows"]').exists()).toBe(true)
    expect(w.find('[aria-label="Data Manager workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Administrator workflows"]').exists()).toBe(false)
  })

  it('shows the Data Manager section for Data Manager (Admin section hidden)', async () => {
    const w = mountWith('Data Manager')
    await w.vm.$nextTick()
    expect(w.find('[aria-label="Investigator workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Monitor workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Data Manager workflows"]').exists()).toBe(true)
    expect(w.find('[aria-label="Administrator workflows"]').exists()).toBe(false)
  })

  it('shows only the Administrator section for Administrator (DM no longer inherited — 2026-06-03)', async () => {
    const w = mountWith('Administrator')
    await w.vm.$nextTick()
    expect(w.find('[aria-label="Investigator workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Monitor workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Data Manager workflows"]').exists()).toBe(false)
    expect(w.find('[aria-label="Administrator workflows"]').exists()).toBe(true)
  })

  it('shows the Investigator section for CRC (inherits Investigator landing in v1)', async () => {
    const w = mountWith('CRC')
    await w.vm.$nextTick()
    expect(w.find('[aria-label="Investigator workflows"]').exists()).toBe(true)
  })
})

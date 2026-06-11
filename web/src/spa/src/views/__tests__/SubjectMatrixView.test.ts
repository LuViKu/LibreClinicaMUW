/**
 * 2026-06-11 — SubjectMatrixView ?filter=<id> adoption.
 *
 * HomeView's "Today's open CRFs" and "Ready to sign" operator cards
 * deep-link to /subjects?filter=today / ?filter=ready-to-sign. The
 * view used to silently drop those values (Filter union didn't carry
 * them and nobody read the query). This spec pins the adoption: the
 * URL query lands in the persistent statusFilter ref so the chip UI
 * highlights and `subjects.filtered` re-computes accordingly.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createI18n } from 'vue-i18n'

// Stub the API client so the store's .load() resolves without a live
// backend. The matrix re-uses the same fixture shape the store tests
// pin — keep it lean here because the spec is about query-string
// adoption, not data rendering.
vi.mock('@/api/client', () => ({
  apiGet: vi.fn().mockResolvedValue([]),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {},
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

// eslint-disable-next-line import/first
import SubjectMatrixView from '@/views/SubjectMatrixView.vue'
// eslint-disable-next-line import/first
import { useSubjectsStore } from '@/stores/subjects'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
  missingWarn: false,
  fallbackWarn: false,
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/subjects', name: 'subject-matrix', component: { template: '<div />' } },
      { path: '/subjects/new', name: 'subject-new', component: { template: '<div />' } },
      {
        path: '/subjects/:subjectId',
        name: 'subject-detail',
        component: { template: '<div />' },
      },
    ],
  })
}

async function mountWithQuery(filter: string | undefined) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: 'Investigator',
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    mustChangePassword: false,
    passwordChangeReason: null,
    locale: null,
    timezone: null,
    activeStudy: {
      id: 1,
      oid: 'S_DEFAULTS1',
      name: 'iAMD',
      isSite: false,
    },
  } as unknown as ReturnType<typeof useAuthStore>['user']['value']

  const router = makeRouter()
  await router.push(
    filter === undefined ? '/subjects' : `/subjects?filter=${encodeURIComponent(filter)}`,
  )
  await router.isReady()

  const wrapper = mount(SubjectMatrixView, {
    global: { plugins: [router, i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('SubjectMatrixView — ?filter query-string adoption', () => {
  beforeEach(() => {
    // Reset the singleton store between tests.
    setActivePinia(createPinia())
  })

  it('adopts ?filter=today into subjects.statusFilter so HomeView\'s "Today\'s open CRFs" card lands on the filtered view', async () => {
    await mountWithQuery('today')
    const store = useSubjectsStore()
    expect(store.statusFilter).toBe('today')
  })

  it('adopts ?filter=ready-to-sign into subjects.statusFilter so HomeView\'s "Ready to sign" card lands on the filtered view', async () => {
    await mountWithQuery('ready-to-sign')
    const store = useSubjectsStore()
    expect(store.statusFilter).toBe('ready-to-sign')
  })

  it('leaves statusFilter at the default "all" when no ?filter is supplied', async () => {
    await mountWithQuery(undefined)
    const store = useSubjectsStore()
    expect(store.statusFilter).toBe('all')
  })

  it('ignores an unknown ?filter value (forward-compat with renamed chips)', async () => {
    await mountWithQuery('not-a-real-filter')
    const store = useSubjectsStore()
    expect(store.statusFilter).toBe('all')
  })
})

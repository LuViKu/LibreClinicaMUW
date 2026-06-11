/**
 * notes-deeplink (2026-06-11) — NotesDiscrepanciesView row context spec.
 *
 * Pins the SPA contract for the notes list row template after the
 * notes-deeplink slice:
 *
 *   - The item-OID cell renders the human-readable {@code itemLabel}
 *     (with the OID as a muted suffix), the event name, and the
 *     current item_data.value — so the operator can decide whether
 *     the value "looks good" without leaving the list.
 *   - The label is wrapped in a router-link to
 *     {@code /event-crfs/<eventCrfOid>?item=<itemOid>} so the operator
 *     can drill into the CRF and CrfEntryView's deep-link flash will
 *     land them on the right field.
 *   - The subject cell is wrapped in a router-link to the subject
 *     detail page (a small UX cleanup that ships with this slice).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {},
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

vi.mock('@/api/download', () => ({
  apiDownload: vi.fn().mockResolvedValue(undefined),
}))

import { apiGet } from '@/api/client'
import NotesDiscrepanciesView from '@/views/NotesDiscrepanciesView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotesStore } from '@/stores/notes'
import type { DiscrepancyNote } from '@/types/note'

import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/sdv', name: 'sdv', component: { template: '<div />' } },
      { path: '/notes', name: 'notes', component: { template: '<div />' } },
      { path: '/audit-log', name: 'audit-log', component: { template: '<div />' } },
      {
        path: '/subjects/:subjectId',
        name: 'subject-detail',
        component: { template: '<div />' },
      },
      {
        path: '/event-crfs/:eventCrfOid',
        name: 'crf-entry',
        component: { template: '<div />' },
      },
    ],
  })
}

const NOTE: DiscrepancyNote = {
  id: '1',
  type: 'query',
  status: 'new',
  subjectId: 'M-001',
  itemOid: 'I_HEIGHT_CM',
  description: 'Does this look good?',
  assignedTo: null,
  daysOpen: 3,
  lastActivityAt: '2026-06-10T00:00:00Z',
  thread: [],
  itemLabel: 'Height (cm)',
  itemValue: '162',
  eventCrfOid: '1',
  eventName: 'V1 Inclusion',
}

function setupAuth() {
  const auth = useAuthStore()
  auth.user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: 'Data Manager',
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
      role: 'Data Manager',
      roles: ['Data Manager'],
    },
  }
}

async function mountWith(notes: DiscrepancyNote[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  setupAuth()
  vi.mocked(apiGet).mockResolvedValue(notes)
  // Pre-populate the store before mount so the row template renders
  // synchronously off the seeded rows. The view's onMounted still
  // fires a no-op load() (notes.rows.length > 0 short-circuits in the
  // store load path? — actually the view guards with rows.length === 0,
  // see onMounted).
  const store = useNotesStore()
  store.rows = notes
  const router = makeRouter()
  const wrapper = mount(NotesDiscrepanciesView, {
    global: { plugins: [pinia, router, i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('NotesDiscrepanciesView — notes-deeplink row context', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the human-readable itemLabel + OID suffix', async () => {
    const w = await mountWith([NOTE])
    // The label appears in the deep-link anchor; the OID is rendered
    // separately as a muted suffix.
    const link = w.find('[data-testid="notes-item-deeplink"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('Height (cm)')
    // The bare OID is rendered alongside as small muted text.
    expect(w.text()).toContain('I_HEIGHT_CM')
  })

  it('renders the eventName + current itemValue under the label', async () => {
    const w = await mountWith([NOTE])
    const context = w.find('[data-testid="notes-item-context"]')
    expect(context.exists()).toBe(true)
    const text = context.text()
    expect(text).toContain('V1 Inclusion')
    expect(text).toContain('Current value: 162')
  })

  it('falls back to "empty" when itemValue is null', async () => {
    const noteWithoutValue: DiscrepancyNote = { ...NOTE, itemValue: null }
    const w = await mountWith([noteWithoutValue])
    const context = w.find('[data-testid="notes-item-context"]')
    expect(context.exists()).toBe(true)
    expect(context.text()).toContain('Current value: empty')
  })

  it('wraps the item label in a router-link to /event-crfs/<id>?item=<oid>', async () => {
    const w = await mountWith([NOTE])
    const link = w.find('[data-testid="notes-item-deeplink"]')
    expect(link.exists()).toBe(true)
    // RouterLink renders as <a href="..."> in jsdom.
    const href = link.attributes('href') ?? ''
    expect(href).toContain('/event-crfs/1')
    expect(href).toContain('item=I_HEIGHT_CM')
  })

  it('wraps the subject cell in a router-link to /subjects/<subjectId>', async () => {
    const w = await mountWith([NOTE])
    const link = w.find('[data-testid="notes-subject-link"]')
    expect(link.exists()).toBe(true)
    const href = link.attributes('href') ?? ''
    expect(href).toContain('/subjects/M-001')
  })
})

/**
 * Phase E completed-crf-and-event-lock (2026-06-11).
 *
 * Locks in the GxP-safety regression test for the inline event-edit
 * composer (the per-row "Bearbeiten" affordance in the events table
 * on SubjectDetailView). When an event whose status is 'complete'
 * is opened in the composer it must render every field disabled +
 * show a banner + replace Save with a Bearbeiten-to-unlock button.
 *
 * No backend reopen-equivalent exists for events — this is purely
 * defensive UX so an accidental click + keystroke can't silently
 * mutate an abgeschlossene Visite.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
    constructor(public status = 0, msg = '', public body: unknown = null) {
      super(msg)
      if (status === 403) this.isForbidden = true
      if (status === 401) this.isUnauthorized = true
    }
  }
  class ApiNetworkError extends Error {
    constructor(message: string, public cause: unknown = null) {
      super(message)
    }
  }
  return {
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
    ApiError,
    ApiNetworkError,
  }
})

// eslint-disable-next-line import/first
import { apiGet } from '@/api/client'
// eslint-disable-next-line import/first
import SubjectDetailView from '@/views/SubjectDetailView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

function makeI18n() {
  return createI18n({
    legacy: false,
    locale: 'en',
    fallbackLocale: 'en',
    messages: { en: enMessages },
  })
}

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/subjects', name: 'subject-matrix', component: { template: '<div />' } },
      {
        path: '/subjects/:subjectId',
        name: 'subject-detail',
        component: SubjectDetailView,
      },
      {
        path: '/events/:eventId',
        name: 'event-detail',
        component: { template: '<div />' },
      },
    ],
  })
}

const COMPLETED_EVENT_SUBJECT = {
  oid: 'SS_M001',
  id: 'M-001',
  secondaryId: null,
  gender: 'F',
  yearOfBirth: 1980,
  enrolledOn: '2026-01-01',
  signed: false,
  locked: false,
  events: [
    {
      eventId: '99',
      eventDefinitionOid: 'SE_V1',
      eventDefinitionName: 'Visit 1',
      ordinal: 1,
      dateStart: '2026-06-01',
      dateEnd: null,
      location: 'Vienna',
      status: 'complete', // ← the bug scenario: completed visit, editable per canEditEvent
      openQueries: 0,
      dataEntryStage: 'initial-data-entry-completed',
    },
  ],
  groupLabel: null,
  studyEye: null,
  screeningDate: null,
  status: 'available',
  groupAssignments: [],
  eyeTransitions: [],
}

async function mountAtSubject(role = 'Investigator') {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: role as never,
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
      oid: 'S_TEST',
      name: 'Test Study',
      isSite: false,
      role: role,
      roles: [role],
    } as never,
  } as never

  // Subject detail fetch returns the completed-event fixture.
  apiGetMock.mockImplementation(async (path: string) => {
    if (path.startsWith('/pages/api/v1/subjects/')) {
      return structuredClone(COMPLETED_EVENT_SUBJECT)
    }
    // Other ambient probes (events list, etc.) — return empty.
    return []
  })

  const router = makeRouter()
  router.push('/subjects/M-001')
  await router.isReady()

  const wrapper = mount(SubjectDetailView, {
    global: { plugins: [router, makeI18n()] },
  })
  await flushPromises()
  return wrapper
}

describe('SubjectDetailView — completed-event edit lock', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
  })

  it('opens the composer in read-only mode for a completed event and shows the banner', async () => {
    const w = await mountAtSubject()

    // Click the Edit button on the events row.
    const editButton = w.find('[data-testid="event-row-edit-button"]')
    expect(editButton.exists()).toBe(true)
    await editButton.trigger('click')
    await flushPromises()

    // Banner appears in the composer.
    const banner = w.find('[data-testid="event-completed-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('completed')

    // Fieldset (the inputs container) is disabled.
    const fieldset = w.find('tr.bg-slate-50 fieldset')
    expect(fieldset.exists()).toBe(true)
    expect((fieldset.element as HTMLFieldSetElement).disabled).toBe(true)

    // Save button must NOT be present while locked — the Bearbeiten
    // unlock button replaces it.
    const unlockBtn = w.find('[data-testid="event-unlock-button"]')
    expect(unlockBtn.exists()).toBe(true)
    expect(unlockBtn.text()).toBe('Edit')

    // No Save button in the composer.
    const composerRow = w.find('tr.bg-slate-50')
    const saveBtn = composerRow.findAll('button').find((b) => b.text() === 'Save')
    expect(saveBtn).toBeUndefined()
  })

  it('clicking Bearbeiten with confirm-yes flips the composer into edit mode', async () => {
    const w = await mountAtSubject()

    const editButton = w.find('[data-testid="event-row-edit-button"]')
    await editButton!.trigger('click')

    // Confirm yes.
    vi.stubGlobal('confirm', vi.fn(() => true))

    const unlockBtn = w.find('[data-testid="event-unlock-button"]')
    expect(unlockBtn.exists()).toBe(true)
    await unlockBtn.trigger('click')
    await flushPromises()

    // Fieldset enabled, banner gone, Save replaces unlock.
    const fieldset = w.find('tr.bg-slate-50 fieldset')
    expect((fieldset.element as HTMLFieldSetElement).disabled).toBe(false)
    expect(w.find('[data-testid="event-completed-banner"]').exists()).toBe(false)
    expect(w.find('[data-testid="event-unlock-button"]').exists()).toBe(false)

    const composerRow = w.find('tr.bg-slate-50')
    const saveBtn = composerRow.findAll('button').find((b) => b.text() === 'Save')
    expect(saveBtn).toBeTruthy()

    vi.unstubAllGlobals()
  })

  it('clicking Bearbeiten with confirm-no leaves the composer locked', async () => {
    const w = await mountAtSubject()

    const editButton = w.find('[data-testid="event-row-edit-button"]')
    await editButton!.trigger('click')

    // Confirm no.
    vi.stubGlobal('confirm', vi.fn(() => false))

    const unlockBtn = w.find('[data-testid="event-unlock-button"]')
    await unlockBtn.trigger('click')
    await flushPromises()

    // Still locked.
    const fieldset = w.find('tr.bg-slate-50 fieldset')
    expect((fieldset.element as HTMLFieldSetElement).disabled).toBe(true)
    expect(w.find('[data-testid="event-completed-banner"]').exists()).toBe(true)
    expect(w.find('[data-testid="event-unlock-button"]').exists()).toBe(true)

    vi.unstubAllGlobals()
  })
})

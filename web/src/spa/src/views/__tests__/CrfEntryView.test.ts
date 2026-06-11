/**
 * Phase E completed-crf-and-event-lock (2026-06-11).
 *
 * Locks in the GxP-safety regression test: a CRF whose backend
 * status is 'complete' must render every input read-only and hide
 * the Save / Mark complete buttons. The pre-existing Reopen button
 * stays reachable, and a successful reopen call flips the SPA back
 * into the editable mode.
 *
 * The Subject-signed lock (status === 'locked') keeps the existing
 * stricter banner copy + no Reopen affordance — those assertions
 * sit next to the completed-state ones so a regression in one
 * mode can't silently land at the cost of the other.
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
vi.mock('@/api/upload', () => ({
  apiUpload: vi.fn(),
}))

// eslint-disable-next-line import/first
import { apiGet, apiPost } from '@/api/client'
// eslint-disable-next-line import/first
import CrfEntryView from '@/views/CrfEntryView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import type { CrfEntry, CrfEntryStatus } from '@/types/crf'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'
// eslint-disable-next-line import/first
import deMessages from '@/locales/de.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>

function makeI18n() {
  return createI18n({
    legacy: false,
    locale: 'en',
    fallbackLocale: 'en',
    messages: { en: enMessages, de: deMessages },
  })
}

function makeRouter(eventCrfOid = 'EC_TEST'): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/subjects', name: 'subject-matrix', component: { template: '<div />' } },
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
      {
        path: '/dde/:eventCrfOid/reconcile',
        name: 'dde-reconcile',
        component: { template: '<div />' },
      },
    ],
  })
}

function makeEntry(status: CrfEntryStatus): CrfEntry {
  return {
    eventCrfOid: 'EC_TEST',
    subjectId: 'M-001',
    eventLabel: 'V1 Inclusion',
    schema: {
      oid: 'F_DEMO_V1',
      name: 'Demographics',
      version: 'v1.0',
      sections: [
        {
          oid: 'S_IDENT',
          title: 'Identification',
          items: [
            {
              oid: 'I_HEIGHT',
              label: 'Height (cm)',
              dataType: 'integer',
              required: true,
              min: 50,
              max: 250,
            },
            {
              oid: 'I_NAME',
              label: 'Name',
              dataType: 'string',
              required: true,
            },
          ],
        },
      ],
    },
    values: { I_HEIGHT: 170, I_NAME: 'Müller' },
    status,
    lastSavedAt: '2026-06-10T09:00:00.000Z',
    groups: [],
    maxFileBytes: 0,
    fileExtensions: '',
    dde: null,
  } as unknown as CrfEntry
}

async function mountView(opts: { status: CrfEntryStatus; role?: string }) {
  setActivePinia(createPinia())
  // Auth store drives canReopen — Investigator + 'complete' satisfies
  // canReopenCrf; Monitor doesn't.
  const auth = useAuthStore()
  auth.user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: (opts.role ?? 'Investigator') as never,
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: null,
    timezone: null,
    mustChangePassword: false,
    passwordChangeReason: null,
    activeStudy: null,
  } as never

  const router = makeRouter()
  router.push('/event-crfs/EC_TEST')
  await router.isReady()

  // Initial CRF load returns the fixture in the requested status.
  apiGetMock.mockImplementation(async (path: string) => {
    if (path.startsWith('/pages/api/v1/eventCrfs/EC_TEST')) {
      // The advanced store also probes /section-status, /lock-status,
      // /notes — return empty bodies so it short-circuits the optional
      // chrome without exploding.
      if (path.includes('/section-status')) return []
      if (path.includes('/lock-status')) return null
      if (path.includes('/notes')) return { eventCrfOid: 'EC_TEST', totalCount: 0, openCount: 0, byItemOid: {} }
      return makeEntry(opts.status)
    }
    return null
  })
  apiPostMock.mockResolvedValue({})

  const wrapper = mount(CrfEntryView, {
    global: { plugins: [router, makeI18n()] },
  })
  await flushPromises()
  return wrapper
}

describe('CrfEntryView — completed-CRF read-only lock', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiPostMock.mockReset()
  })

  it('renders inputs as read-only and shows the completed banner when status === complete', async () => {
    const w = await mountView({ status: 'complete' })

    const banner = w.find('[data-testid="crf-completed-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('Reopen CRF')

    // Every form input inside the fieldset is disabled — vue-test-utils
    // reports inherited disabled attributes via the actual element, but
    // for assertion clarity we check the wrapping fieldset directly.
    const fieldset = w.find('fieldset')
    expect(fieldset.exists()).toBe(true)
    expect(fieldset.element.disabled).toBe(true)

    // Save-draft + Mark-complete buttons must NOT render — only the
    // Reopen button stays in the action row for a privileged role.
    expect(w.text()).not.toContain('Save draft')
    expect(w.text()).not.toContain('Mark CRF complete')
    expect(w.text()).toContain('Reopen CRF')
  })

  it('hides the Reopen action when the operator role cannot reopen', async () => {
    const w = await mountView({ status: 'complete', role: 'Monitor' })

    const banner = w.find('[data-testid="crf-completed-banner"]')
    expect(banner.exists()).toBe(true)
    // Monitor sees the no-reopen variant message instead.
    expect(banner.text()).toContain('Contact the study lead')

    // Save / Mark complete still hidden.
    expect(w.text()).not.toContain('Save draft')
    expect(w.text()).not.toContain('Mark CRF complete')
    expect(w.text()).not.toContain('Reopen CRF')

    // Fieldset still disabled — defense in depth.
    expect(w.find('fieldset').element.disabled).toBe(true)
  })

  it('after a successful reopen, the fieldset flips back to editable and Save reappears', async () => {
    const w = await mountView({ status: 'complete' })
    expect(w.find('fieldset').element.disabled).toBe(true)

    // Stub the reopen POST (a.k.a. /markIncomplete on the wire) to
    // return the in-progress status the backend writes back after a
    // successful reopen. The store reads response.status into the
    // entry, which drives `isCompleted` → false.
    apiPostMock.mockImplementation(async (path: string) => {
      if (path.endsWith('/markIncomplete') || path.endsWith(':reopen')) {
        return { status: 'in-progress', lastSavedAt: '2026-06-11T10:00:00.000Z' }
      }
      return {}
    })

    // Reopen confirms via window.confirm — auto-accept.
    vi.stubGlobal('confirm', vi.fn(() => true))

    const reopenBtn = w
      .findAll('button')
      .find((b) => b.text().includes('Reopen CRF'))
    expect(reopenBtn).toBeTruthy()
    await reopenBtn!.trigger('click')
    await flushPromises()

    // Wire-level path is /markIncomplete (legacy OpenClinica naming
     // — the SPA labels it "Reopen CRF" in the UI).
    expect(
      apiPostMock.mock.calls.some(
        (call) => typeof call[0] === 'string' && call[0].includes('/markIncomplete'),
      ),
    ).toBe(true)
    // Fieldset is now enabled, completed-banner gone, Mark complete
    // returns to the action row.
    expect(w.find('fieldset').element.disabled).toBe(false)
    expect(w.find('[data-testid="crf-completed-banner"]').exists()).toBe(false)
    expect(w.text()).toContain('Mark CRF complete')

    vi.unstubAllGlobals()
  })

  it('subject-signed lock renders the stricter banner copy and no Reopen', async () => {
    const w = await mountView({ status: 'locked' })

    // The locked-by-subject-sign banner has different copy.
    const lockedBanner = w.find('[data-testid="crf-locked-banner"]')
    expect(lockedBanner.exists()).toBe(true)
    expect(lockedBanner.text()).toContain('subject is signed')

    // No completed-banner at the same time.
    expect(w.find('[data-testid="crf-completed-banner"]').exists()).toBe(false)
    // No editable actions, no Reopen — locked is the strictest state.
    expect(w.text()).not.toContain('Reopen CRF')
    expect(w.text()).not.toContain('Save draft')
    expect(w.find('fieldset').element.disabled).toBe(true)
  })
})

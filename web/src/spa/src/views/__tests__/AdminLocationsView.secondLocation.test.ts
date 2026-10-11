/**
 * System → Standorte → create: the warning before the second location.
 *
 * While there is exactly one location the server still serves uploaders that
 * have no token. The second location closes that door, so the create dialog
 * says so first. Pinned: no warning while the new location would be the only
 * available one; for the second it asks the server which uploaders never used
 * a token, says how many and which, points to the page where tokens are
 * issued, and, when there are any, holds "Anlegen" back until the person
 * ticks that they know; with none it just says so; and a failed lookup is a
 * neutral warning that blocks nothing.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'
import { axe } from 'vitest-axe'
import '@/test/a11y/setup'

const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn() }))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

import { apiGet, apiPost, apiPut, apiDelete, ApiError } from '@/api/client'
import { i18n as appI18n } from '@/i18n'
import { useNotificationsStore } from '@/stores/notifications'
import AdminLocationsView from '@/views/AdminLocationsView.vue'
import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'
import type { Location } from '@/types/location'
import type { AnonymousUploaders } from '@/types/ingestSource'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
const LOCATIONS_URL = '/pages/api/v1/admin/locations'
const SOURCES_URL = '/pages/api/v1/admin/ingest-sources'
const ANON_URL = `${SOURCES_URL}/anonymous-uploaders`

function loc(over: Partial<Location> & Pick<Location, 'id' | 'code' | 'name'>): Location {
  return {
    status: 'available', street: null, city: null, zip: null, country: null,
    siteCount: 0, studyCount: 0, ingestItemCount: 0, userCount: 0,
    ...over,
  }
}

const MUW = loc({ id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' })
const LINZ = loc({ id: 3, code: 'LINZ', name: 'Klinik Linz', status: 'removed' })

const CLARUS = { displayName: 'CLARUS-PC', kind: 'export-watcher', lastSeenAt: '2026-10-11T09:30:00Z' }
const BRIDGE = { displayName: null, kind: 'optomed-bridge', lastSeenAt: '2026-10-10T08:00:00Z' }
const TWO: AnonymousUploaders = { count: 2, instances: [CLARUS, BRIDGE] }
const NONE: AnonymousUploaders = { count: 0, instances: [] }

const clone = <T,>(v: T): T => structuredClone(v)

interface Scenario {
  locations?: Location[]
  anonymous?: AnonymousUploaders | Error | Promise<AnonymousUploaders>
}

function answerGets(s: Scenario = {}) {
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === LOCATIONS_URL) return clone(s.locations ?? [MUW])
    if (path === SOURCES_URL) return []
    if (path === ANON_URL) {
      const a = s.anonymous ?? NONE
      if (a instanceof Error) throw a
      return clone(await a)
    }
    if (path === '/pages/api/v1/admin/studies') return []
    throw new Error(`unexpected GET ${path}`)
  })
}

const mounted: VueWrapper[] = []

async function mountView(withI18n: typeof i18n = i18n): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/locations', component: AdminLocationsView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/locations')
  await router.isReady()
  const w = mount(AdminLocationsView, { global: { plugins: [router, withI18n] }, attachTo: document.body })
  mounted.push(w)
  await flushPromises()
  return w
}

const byId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null
const text = (id: string) => byId(id)?.textContent?.replace(/\s+/g, ' ').trim() ?? null
const field = (id: string) => document.body.querySelector(`#${id}`) as HTMLInputElement | null

async function type(id: string, value: string) {
  const el = field(id)!
  el.value = value
  el.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

async function click(testid: string) {
  byId(testid)!.click()
  await flushPromises()
}

async function tick(testid: string) {
  const box = byId(testid) as HTMLInputElement
  box.checked = true
  box.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
}

/** Opens the create dialog and fills code and name, so that only the warning can hold "Anlegen" back. */
async function openCreateAndFill() {
  await click('location-create')
  await type('location-code', 'GRAZ')
  await type('location-name', 'Klinik Graz')
}

const submit = () => byId('location-form-submit') as HTMLButtonElement
const anonReads = () => vi.mocked(apiGet).mock.calls.filter((c) => c[0] === ANON_URL).length

describe('creating a location: the warning before the second', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    appI18n.global.locale.value = 'en'
    document.body.innerHTML = ''
    for (const fn of [apiGet, apiPost, apiPut, apiDelete]) vi.mocked(fn).mockReset()
    confirmMock.mockReset()
    confirmMock.mockResolvedValue(true)
    answerGets()
  })
  afterEach(() => {
    for (const w of mounted.splice(0)) w.unmount()
    appI18n.global.locale.value = 'de-AT'
  })

  describe('when the new location would not be the second', () => {
    it('says nothing while no location exists, and does not ask', async () => {
      answerGets({ locations: [] })
      await mountView()
      await openCreateAndFill()
      expect(byId('location-second-warning')).toBeNull()
      expect(field('location-ack-anonymous')).toBeNull()
      expect(anonReads()).toBe(0)
      expect(submit().disabled).toBe(false)
    })

    it('says nothing while the only locations are disabled ones: the new one would be the only available', async () => {
      answerGets({ locations: [LINZ] })
      await mountView()
      await openCreateAndFill()
      expect(byId('location-second-warning')).toBeNull()
      expect(anonReads()).toBe(0)
    })

    it('says nothing when a location is edited', async () => {
      await mountView()
      await click('location-edit-1')
      expect(byId('location-second-warning')).toBeNull()
      expect(anonReads()).toBe(0)
    })
  })

  describe('when uploaders have no token yet', () => {
    beforeEach(() => { answerGets({ anonymous: TWO }) })

    it('asks the server when the dialog opens, and not before', async () => {
      await mountView()
      expect(anonReads()).toBe(0)
      await click('location-create')
      expect(anonReads()).toBe(1)
    })

    it('says what the second location changes, how many uploaders have no token, and which', async () => {
      await mountView()
      await click('location-create')
      const box = byId('location-second-warning')!
      expect(box.getAttribute('data-state')).toBe('ready')
      expect(text('location-second-warning')).toContain('From the second location on, every uploader needs a token')
      expect(text('location-second-warning')).toContain('the anonymous front door closes')
      expect(text('location-second-count')).toBe('2 uploaders have no token yet but reported in the last 7 days:')
      const items = Array.from(document.body.querySelectorAll('[data-testid="location-second-item"]')).map((li) =>
        li.textContent?.replace(/\s+/g, ' ').trim())
      expect(items).toHaveLength(2)
      expect(items[0]).toMatch(/^CLARUS-PC · Export Watcher · last seen /)
      // An uploader that reports no name is still named: by what it is.
      expect(items[1]).toMatch(/^unnamed · Optomed Bridge · last seen /)
    })

    it('says "1 uploader has" for one', async () => {
      answerGets({ anonymous: { count: 1, instances: [CLARUS] } })
      await mountView()
      await click('location-create')
      expect(text('location-second-count')).toBe('1 uploader has no token yet but reported in the last 7 days:')
    })

    it('shows a program the page does not know as the server named it, and how many more there are', async () => {
      answerGets({ anonymous: { count: 53, instances: [{ displayName: 'X-PC', kind: 'new-program', lastSeenAt: '2026-10-11T09:30:00Z' }] } })
      await mountView()
      await click('location-create')
      expect(text('location-second-item')).toContain('X-PC · new-program')
      expect(text('location-second-more')).toBe('… and 52 more')
    })

    it('links to the page where tokens are issued, in a tab of its own so the dialog stays', async () => {
      await mountView()
      await click('location-create')
      const link = byId('location-second-link') as HTMLAnchorElement
      expect(link.getAttribute('href')).toBe('/uploaders')
      expect(link.getAttribute('target')).toBe('_blank')
      expect(link.getAttribute('rel')).toContain('noopener')
      expect(link.textContent).toContain('Uploaders and tokens')
    })

    it('holds "Create" back until the person ticks that they know, however complete the form is', async () => {
      await mountView()
      await openCreateAndFill()
      const box = field('location-ack-anonymous')!
      expect(box).not.toBeNull()
      expect(box.type).toBe('checkbox')
      expect(box.checked).toBe(false)
      expect(box.required).toBe(true)
      expect(document.body.querySelector('label[for="location-ack-anonymous"]')?.classList.contains('req')).toBe(true)
      expect(document.body.querySelector('label[for="location-ack-anonymous"]')?.textContent?.trim())
        .toBe('I know that these uploaders can no longer upload without a token afterwards')
      expect(submit().disabled).toBe(true)

      await tick('location-ack-anonymous')
      expect(submit().disabled).toBe(false)
    })

    it('does not post without the tick, and posts with it', async () => {
      vi.mocked(apiPost).mockResolvedValue(loc({ id: 2, code: 'GRAZ', name: 'Klinik Graz' }))
      await mountView()
      await openCreateAndFill()
      document.getElementById('location-form')!.dispatchEvent(new Event('submit', { cancelable: true }))
      await flushPromises()
      expect(apiPost).not.toHaveBeenCalled()

      await tick('location-ack-anonymous')
      await click('location-form-submit')
      expect(apiPost).toHaveBeenCalledWith(LOCATIONS_URL, { code: 'GRAZ', name: 'Klinik Graz' })
    })

    it('starts every opening unticked and asks again', async () => {
      await mountView()
      await openCreateAndFill()
      await tick('location-ack-anonymous')
      await click('location-form-cancel')
      expect(anonReads()).toBe(1)

      await click('location-create')
      expect(anonReads()).toBe(2)
      expect((field('location-ack-anonymous') as HTMLInputElement).checked).toBe(false)
    })

    it('waits for the answer: "Create" is held back while the lookup runs, and the warning says it is checking', async () => {
      let answer!: (a: AnonymousUploaders) => void
      answerGets({ anonymous: new Promise<AnonymousUploaders>((r) => { answer = r }) })
      await mountView()
      await openCreateAndFill()
      expect(byId('location-second-warning')!.getAttribute('data-state')).toBe('loading')
      expect(text('location-second-checking')).toBe('Checking which uploaders have no token yet …')
      expect(submit().disabled).toBe(true)
      expect(field('location-ack-anonymous')).toBeNull()

      answer(clone(TWO))
      await flushPromises()
      expect(byId('location-second-checking')).toBeNull()
      expect(field('location-ack-anonymous')).not.toBeNull()
      expect(submit().disabled).toBe(true)
    })

    it('drops an answer that arrives for an opening that is already closed', async () => {
      let answerFirst!: (a: AnonymousUploaders) => void
      vi.mocked(apiGet).mockImplementation(async (path: string) => {
        if (path === LOCATIONS_URL) return [clone(MUW)]
        if (path === SOURCES_URL) return []
        if (path === ANON_URL) {
          return anonReads() === 1
            ? new Promise<AnonymousUploaders>((r) => { answerFirst = r })
            : clone(NONE)
        }
        throw new Error(`unexpected GET ${path}`)
      })
      await mountView()
      await click('location-create')
      await click('location-form-cancel')
      await click('location-create')
      await flushPromises()
      expect(text('location-second-none')).toContain('already uses a token')

      answerFirst(clone(TWO))
      await flushPromises()
      // The late answer of the first opening must not put its uploaders into the second.
      expect(byId('location-second-count')).toBeNull()
      expect(field('location-ack-anonymous')).toBeNull()
    })
  })

  describe('when every uploader already has a token', () => {
    it('still says what the second location changes, says that nothing is missing, and asks for no tick', async () => {
      answerGets({ anonymous: NONE })
      await mountView()
      await openCreateAndFill()
      expect(text('location-second-warning')).toContain('From the second location on, every uploader needs a token')
      expect(text('location-second-none')).toBe('Every uploader that reported in the last 7 days already uses a token.')
      expect(byId('location-second-count')).toBeNull()
      expect(byId('location-second-list')).toBeNull()
      expect(field('location-ack-anonymous')).toBeNull()
      expect(submit().disabled).toBe(false)
    })

    it('lets the location be created', async () => {
      answerGets({ anonymous: NONE })
      vi.mocked(apiPost).mockResolvedValue(loc({ id: 2, code: 'GRAZ', name: 'Klinik Graz' }))
      await mountView()
      await openCreateAndFill()
      await click('location-form-submit')
      expect(apiPost).toHaveBeenCalledWith(LOCATIONS_URL, { code: 'GRAZ', name: 'Klinik Graz' })
      expect(useNotificationsStore().toasts.map((t) => t.message)).toContain('Location “Klinik Graz” created.')
    })
  })

  describe('when the lookup fails', () => {
    beforeEach(() => { answerGets({ anonymous: new ApiError(500, 'Server Error', { message: 'Database unavailable' }) }) })

    it('says so in a neutral warning, that is, not as an alarm', async () => {
      await mountView()
      await click('location-create')
      const box = byId('location-second-warning')!
      expect(box.getAttribute('data-state')).toBe('failed')
      expect(box.className).toContain('bg-slate-50')
      expect(box.className).not.toContain('amber')
      expect(text('location-second-unknown')).toBe(
        'It could not be checked whether uploaders without a token are still running. Check before you create the location: from the second location on they are turned away.',
      )
      expect(text('location-second-warning')).toContain('From the second location on, every uploader needs a token')
      expect(byId('location-second-link')).not.toBeNull()
    })

    it('blocks nothing: no tick, and "Create" works', async () => {
      vi.mocked(apiPost).mockResolvedValue(loc({ id: 2, code: 'GRAZ', name: 'Klinik Graz' }))
      await mountView()
      await openCreateAndFill()
      expect(field('location-ack-anonymous')).toBeNull()
      expect(submit().disabled).toBe(false)
      await click('location-form-submit')
      expect(apiPost).toHaveBeenCalledTimes(1)
    })

    it('treats an unreachable server the same way', async () => {
      const { ApiNetworkError } = await import('@/api/client')
      answerGets({ anonymous: new ApiNetworkError('offline', null) })
      await mountView()
      await openCreateAndFill()
      expect(byId('location-second-warning')!.getAttribute('data-state')).toBe('failed')
      expect(submit().disabled).toBe(false)
    })
  })

  describe('in German, the working language', () => {
    it('names the tick exactly, and leaves no key untranslated', async () => {
      const german = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de: deMessages } })
      appI18n.global.locale.value = 'de-AT'
      answerGets({ anonymous: TWO })
      await mountView(german)
      await click('location-create')
      expect(document.body.querySelector('label[for="location-ack-anonymous"]')?.textContent?.trim())
        .toBe('Ich weiß, dass diese Uploader danach ohne Token nicht mehr hochladen können')
      expect(text('location-second-warning')).toContain('Ab dem zweiten Standort braucht jeder Uploader einen Token')
      expect(text('location-second-count')).toBe('2 Uploader haben noch keinen Token, haben sich aber in den letzten 7 Tagen gemeldet:')
      expect(text('location-second-item')).toContain('zuletzt gesehen')
      expect(text('location-second-link')).toContain('Uploader und Tokens')
      expect(document.body.textContent).not.toMatch(/\b(adminLocations|uploaders|common)\.[a-zA-Z.]+/)
    })

    it('says "1 Uploader hat" for one, and says nothing is missing when it is none', async () => {
      const german = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de: deMessages } })
      appI18n.global.locale.value = 'de-AT'
      answerGets({ anonymous: { count: 1, instances: [CLARUS] } })
      await mountView(german)
      await click('location-create')
      expect(text('location-second-count')).toBe('1 Uploader hat noch keinen Token, hat sich aber in den letzten 7 Tagen gemeldet:')
    })
  })

  describe('accessibility', () => {
    const RULES = {
      runOnly: { type: 'tag' as const, values: ['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'] },
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    }

    it('has no structural violations with the warning, the list and the tick', async () => {
      answerGets({ anonymous: TWO })
      await mountView()
      await click('location-create')
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })

    it('has none with the neutral warning', async () => {
      answerGets({ anonymous: new ApiError(500, 'Server Error', null) })
      await mountView()
      await click('location-create')
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })
  })
})

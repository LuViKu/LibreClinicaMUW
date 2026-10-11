/**
 * System → Standorte, the "Quellen" section: where device files come from,
 * and the location each source is assigned to (system administrators only).
 *
 * Pins: the page has two tabs, Standorte first, and the Quellen tab carries
 * the number of sources that still have no location; the sources table
 * names kind, name and key, location, waiting files, last seen and status;
 * a source without a location that has files waiting is emphasised ("N files
 * waiting"), because only administrators can see those files until it is
 * assigned; choosing a location in a row assigns it, confirms with a notice,
 * and refreshes the locations' file counts that the assignment moved;
 * "not assigned" takes the location away; and the server's 409 (the location
 * is no longer available) and 404 are shown, with the select put back.
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
import type { IngestSource } from '@/types/ingestSource'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
const LOCATIONS_URL = '/pages/api/v1/admin/locations'
const SOURCES_URL = '/pages/api/v1/admin/ingest-sources'

function loc(over: Partial<Location> & Pick<Location, 'id' | 'code' | 'name'>): Location {
  return {
    status: 'available', street: null, city: null, zip: null, country: null,
    siteCount: 0, studyCount: 0, ingestItemCount: 0, userCount: 0,
    ...over,
  }
}

/** In the server's order: by name. */
const GRAZ = loc({ id: 2, code: 'GRAZ', name: 'Klinik Graz', ingestItemCount: 12 })
const LINZ = loc({ id: 3, code: 'LINZ', name: 'Klinik Linz', status: 'removed' })
const MUW = loc({ id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik', ingestItemCount: 340 })
const LOCATIONS = [GRAZ, LINZ, MUW]

function src(over: Partial<IngestSource> & Pick<IngestSource, 'id' | 'kind' | 'displayName'>): IngestSource {
  return {
    sourceKey: null,
    locationId: 1,
    locationName: 'MUW Augenklinik',
    pendingItemCount: 0,
    lastSeenAt: null,
    status: 'active',
    ...over,
  }
}

const CLARUS = src({ id: 1, kind: 'uploader', displayName: 'Clarus-PC', pendingItemCount: 3,
  lastSeenAt: '2026-10-12T09:30:00Z' })
const LUMO = src({ id: 2, kind: 'dicom_ae', displayName: 'Optomed Lumo', sourceKey: 'LUMO01',
  locationId: null, locationName: null, pendingItemCount: 12 })
const REMIDIO = src({ id: 3, kind: 'remidio', displayName: 'Remidio-Konto', sourceKey: 'muw-augen',
  locationId: null, locationName: null, pendingItemCount: 0 })
const OLD_PC = src({ id: 4, kind: 'uploader', displayName: 'Altes Gerät', locationId: 3,
  locationName: 'Klinik Linz', status: 'revoked' })
const SOURCES = [CLARUS, LUMO, REMIDIO, OLD_PC]

const clone = <T,>(v: T): T => structuredClone(v)

function answerGets(over: { locations?: Location[]; sources?: IngestSource[]; sourcesFail?: ApiError } = {}) {
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === LOCATIONS_URL) return clone(over.locations ?? LOCATIONS)
    if (path === SOURCES_URL) {
      if (over.sourcesFail) throw over.sourcesFail
      return clone(over.sources ?? SOURCES)
    }
    if (path === '/pages/api/v1/admin/studies') return []
    // The create dialog's question before a second location; not what these tests are about.
    if (path === `${SOURCES_URL}/anonymous-uploaders`) return { count: 0, instances: [] }
    throw new Error(`unexpected GET ${path}`)
  })
}

/** Every wrapper mounted by a test, unmounted afterwards even when the test fails half-way. */
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
const select = (id: string) => document.body.querySelector(`#${id}`) as HTMLSelectElement | null

async function click(testid: string) {
  byId(testid)!.click()
  await flushPromises()
}

/** Picks an option of a source's location select, as a person does. */
async function choose(sourceId: number, value: string) {
  const el = select(`source-location-${sourceId}`)!
  el.value = value
  el.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
}

async function openSources() {
  await click('locations-tab-sources')
}

function toasts(): string[] {
  return useNotificationsStore().toasts.map((x) => x.message)
}

describe('System → Standorte → Quellen', () => {
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

  describe('the tabs', () => {
    it('starts on Standorte, with Quellen beside it, and reads the sources with the page', async () => {
      await mountView()
      const tabs = Array.from(document.body.querySelectorAll('[role="tab"]'))
      expect(tabs.map((t) => t.getAttribute('data-testid'))).toEqual(['locations-tab-locations', 'locations-tab-sources'])
      expect(tabs.map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false'])
      expect(byId('locations-tab-locations')!.textContent).toContain('Locations')
      expect(byId('locations-tab-sources')!.textContent).toContain('Sources')
      expect(document.body.querySelector('[role="tablist"]')?.getAttribute('aria-label')).toBe('Sections of the location administration')
      expect(vi.mocked(apiGet)).toHaveBeenCalledWith(LOCATIONS_URL)
      expect(vi.mocked(apiGet)).toHaveBeenCalledWith(SOURCES_URL)
      // The locations are on screen; the sources are not.
      expect(byId('admin-location-1')).not.toBeNull()
      expect(byId('location-create')).not.toBeNull()
      expect(byId('source-row-1')).toBeNull()
    })

    it('links each tab to its panel and each panel back to its tab', async () => {
      await mountView()
      const panel = byId('locations-panel-locations')!
      expect(panel.getAttribute('role')).toBe('tabpanel')
      expect(byId('locations-tab-locations')!.getAttribute('aria-controls')).toBe(panel.id)
      expect(panel.getAttribute('aria-labelledby')).toBe(byId('locations-tab-locations')!.id)

      await openSources()
      const sources = byId('locations-panel-sources')!
      expect(sources.getAttribute('role')).toBe('tabpanel')
      expect(byId('locations-tab-sources')!.getAttribute('aria-controls')).toBe(sources.id)
      expect(sources.getAttribute('aria-labelledby')).toBe(byId('locations-tab-sources')!.id)
      expect(byId('locations-panel-locations')).toBeNull()
    })

    it('switches by click, and the create button belongs to Standorte alone', async () => {
      await mountView()
      await openSources()
      expect(byId('locations-tab-sources')!.getAttribute('aria-selected')).toBe('true')
      expect(byId('locations-tab-locations')!.getAttribute('aria-selected')).toBe('false')
      expect(byId('source-row-1')).not.toBeNull()
      expect(byId('admin-location-1')).toBeNull()
      expect(byId('location-create')).toBeNull()

      await click('locations-tab-locations')
      expect(byId('admin-location-1')).not.toBeNull()
      expect(byId('location-create')).not.toBeNull()
      expect(byId('source-row-1')).toBeNull()
    })

    it('moves with the arrow keys, Home and End, and only the current tab is in the tab order', async () => {
      await mountView()
      const list = document.body.querySelector('[role="tablist"]') as HTMLElement
      const press = async (key: string) => {
        list.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
        await flushPromises()
      }
      expect(byId('locations-tab-locations')!.getAttribute('tabindex')).toBe('0')
      expect(byId('locations-tab-sources')!.getAttribute('tabindex')).toBe('-1')

      await press('ArrowRight')
      expect(byId('locations-tab-sources')!.getAttribute('aria-selected')).toBe('true')
      expect(byId('locations-tab-sources')!.getAttribute('tabindex')).toBe('0')
      expect(byId('locations-tab-locations')!.getAttribute('tabindex')).toBe('-1')
      expect(document.activeElement).toBe(byId('locations-tab-sources'))

      await press('ArrowRight')
      expect(byId('locations-tab-locations')!.getAttribute('aria-selected')).toBe('true')
      await press('ArrowLeft')
      expect(byId('locations-tab-sources')!.getAttribute('aria-selected')).toBe('true')
      await press('Home')
      expect(byId('locations-tab-locations')!.getAttribute('aria-selected')).toBe('true')
      await press('End')
      expect(byId('locations-tab-sources')!.getAttribute('aria-selected')).toBe('true')
      await press('a')
      expect(byId('locations-tab-sources')!.getAttribute('aria-selected')).toBe('true')
    })

    it('shows on the Quellen tab how many sources still have no location', async () => {
      await mountView()
      expect(text('locations-tab-sources-badge')).toContain('2')
      expect(byId('locations-tab-sources')!.textContent).toContain('2 sources without a location')
    })

    it('shows no number when every source has a location', async () => {
      answerGets({ sources: [CLARUS, OLD_PC] })
      await mountView()
      expect(byId('locations-tab-sources-badge')).toBeNull()
    })

    it('shows no number when the sources cannot be read, and says why on the tab', async () => {
      answerGets({ sourcesFail: new ApiError(500, 'Server Error', { message: 'Database unavailable' }) })
      await mountView()
      expect(byId('locations-tab-sources-badge')).toBeNull()
      await openSources()
      expect(text('sources-error')).toContain('Database unavailable')
    })
  })

  describe('the sources table', () => {
    it('lists every source in the server order, with kind, name and key, waiting files and status', async () => {
      await mountView()
      await openSources()
      const ids = Array.from(document.body.querySelectorAll('[data-testid^="source-row-"]')).map(
        (tr) => tr.getAttribute('data-testid')!.slice('source-row-'.length),
      )
      expect(ids).toEqual(['1', '2', '3', '4'])

      expect(text('source-kind-1')).toBe('Uploader (acquisition PC)')
      expect(text('source-kind-2')).toBe('DICOM device (AE title)')
      expect(text('source-kind-3')).toBe('Remidio account')
      expect(text('source-name-1')).toBe('Clarus-PC')
      expect(text('source-name-2')).toContain('Optomed Lumo')
      expect(text('source-name-2')).toContain('LUMO01')
      expect(text('source-name-3')).toContain('muw-augen')
      expect(text('source-status-1')).toContain('Active')
      expect(text('source-status-4')).toContain('Revoked')

      const headers = Array.from(document.body.querySelectorAll('th')).map((th) => th.textContent?.trim())
      expect(headers).toEqual(expect.arrayContaining(['Kind', 'Name', 'Location', 'Waiting files', 'Last seen', 'Status']))
    })

    it('names a kind it does not know as the server named it', async () => {
      answerGets({ sources: [src({ id: 9, kind: 'fax' as never, displayName: 'Fax' })] })
      await mountView()
      await openSources()
      expect(text('source-kind-9')).toBe('fax')
    })

    it('offers each source "not assigned" and the available locations, and shows the current one', async () => {
      await mountView()
      await openSources()
      const options = (id: number) => Array.from(select(`source-location-${id}`)!.options).map((o) => o.textContent?.trim())

      expect(options(1)).toEqual(['not assigned', 'Klinik Graz', 'MUW Augenklinik'])
      expect(select('source-location-1')!.value).toBe('1')
      expect(select('source-location-2')!.value).toBe('')
      // A source at a disabled location still shows where it is, but nobody can pick that location for another source.
      expect(options(4)).toEqual(['not assigned', 'Klinik Graz', 'Klinik Linz (disabled)', 'MUW Augenklinik'])
      expect(select('source-location-4')!.value).toBe('3')
      expect(options(1)).not.toContain('Klinik Linz (disabled)')
      expect(select('source-location-2')!.getAttribute('aria-label')).toBe('Location of Optomed Lumo')
    })

    it('emphasises a source without a location that has files waiting, and no other', async () => {
      await mountView()
      await openSources()
      expect(text('source-waiting-2')).toContain('12 files waiting')
      expect(byId('source-row-2')!.getAttribute('data-needs-location')).toBe('true')
      // Unassigned, nothing waiting: no emphasis.
      expect(text('source-waiting-3')).toBe('0')
      expect(byId('source-row-3')!.getAttribute('data-needs-location')).toBeNull()
      // Assigned: the files that wait are the inbox's everyday work.
      expect(text('source-waiting-1')).toBe('3')
      expect(byId('source-row-1')!.getAttribute('data-needs-location')).toBeNull()
    })

    it('says "1 file waiting" for one', async () => {
      answerGets({ sources: [{ ...LUMO, pendingItemCount: 1 }] })
      await mountView()
      await openSources()
      expect(text('source-waiting-2')).toContain('1 file waiting')
      expect(text('source-waiting-2')).not.toContain('1 files waiting')
    })

    it('sums up what is still owed, or says that nothing is', async () => {
      await mountView()
      await openSources()
      expect(text('sources-summary')).toBe(
        'Sources without a location: 2. Only a system administrator sees their waiting files: 12.',
      )
    })

    it('says that every source has a location when none is missing one', async () => {
      answerGets({ sources: [CLARUS, OLD_PC] })
      await mountView()
      await openSources()
      expect(text('sources-summary')).toBe('Every source has a location.')
    })

    it('shows when a source was last seen, or that it never was', async () => {
      await mountView()
      await openSources()
      expect(byId('source-seen-1')!.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-12T09:30:00Z')
      expect(text('source-seen-2')).toBe('never')
    })

    it('says so when there is no source yet', async () => {
      answerGets({ sources: [] })
      await mountView()
      await openSources()
      expect(text('sources-empty')).toContain('No source is known yet')
    })

    it('shows the server’s refusal to anyone who is not a system administrator', async () => {
      answerGets({ sourcesFail: new ApiError(403, 'Forbidden', { message: 'Only a system administrator may list the sources' }) })
      await mountView()
      await openSources()
      expect(text('sources-error')).toContain('Only a system administrator may list the sources')
      expect(byId('source-row-1')).toBeNull()
      expect(byId('sources-empty')).toBeNull()
    })
  })

  describe('assigning a location', () => {
    const ASSIGNED_LUMO = { ...LUMO, locationId: 2, locationName: 'Klinik Graz' }

    it('PUTs the location, shows the source as assigned, and tells how many waiting files move', async () => {
      vi.mocked(apiPut).mockResolvedValue(ASSIGNED_LUMO)
      await mountView()
      await openSources()
      await choose(2, '2')

      expect(vi.mocked(apiPut)).toHaveBeenCalledTimes(1)
      expect(vi.mocked(apiPut)).toHaveBeenCalledWith(`${SOURCES_URL}/2/location`, { locationId: 2 })
      expect(select('source-location-2')!.value).toBe('2')
      // No longer owed: the emphasis is gone, the number stays as the inbox's own work.
      expect(byId('source-row-2')!.getAttribute('data-needs-location')).toBeNull()
      expect(text('source-waiting-2')).toBe('12')
      expect(text('sources-summary')).toBe(
        'Sources without a location: 1. Only a system administrator sees their waiting files: 0.',
      )
      expect(text('locations-tab-sources-badge')).toContain('1')
      expect(toasts()).toEqual([
        'Source “Optomed Lumo” now belongs to location “Klinik Graz”. Waiting files that appear in its inbox: 12.',
      ])
    })

    it('uses the short notice for a source with nothing waiting', async () => {
      vi.mocked(apiPut).mockResolvedValue({ ...REMIDIO, locationId: 1, locationName: 'MUW Augenklinik' })
      await mountView()
      await openSources()
      await choose(3, '1')
      expect(vi.mocked(apiPut)).toHaveBeenCalledWith(`${SOURCES_URL}/3/location`, { locationId: 1 })
      expect(toasts()).toEqual(['Source “Remidio-Konto” now belongs to location “MUW Augenklinik”.'])
    })

    it('takes the location away with "not assigned", sending null', async () => {
      vi.mocked(apiPut).mockResolvedValue({ ...CLARUS, locationId: null, locationName: null })
      await mountView()
      await openSources()
      await choose(1, '')

      expect(vi.mocked(apiPut)).toHaveBeenCalledWith(`${SOURCES_URL}/1/location`, { locationId: null })
      expect(select('source-location-1')!.value).toBe('')
      expect(toasts()).toEqual(['Source “Clarus-PC” is no longer assigned to a location.'])
      // Its three waiting files are now ones only an administrator sees.
      expect(byId('source-row-1')!.getAttribute('data-needs-location')).toBe('true')
      expect(text('locations-tab-sources-badge')).toContain('3')
    })

    it('does not send a change that changes nothing', async () => {
      await mountView()
      await openSources()
      await choose(1, '1')
      expect(vi.mocked(apiPut)).not.toHaveBeenCalled()
      expect(toasts()).toEqual([])
    })

    it('reads the locations again, so that the file count the assignment moved is the server’s', async () => {
      vi.mocked(apiPut).mockResolvedValue(ASSIGNED_LUMO)
      await mountView()
      await openSources()
      const readsBefore = vi.mocked(apiGet).mock.calls.filter((c) => c[0] === LOCATIONS_URL).length
      expect(readsBefore).toBe(1)

      answerGets({ locations: [{ ...GRAZ, ingestItemCount: 24 }, LINZ, MUW] })
      await choose(2, '2')
      expect(vi.mocked(apiGet).mock.calls.filter((c) => c[0] === LOCATIONS_URL).length).toBe(2)

      await click('locations-tab-locations')
      expect(text('count-files-2')).toBe('24')
      // A silent read: the list was never replaced by a loading row.
      expect(document.body.textContent).not.toContain('Loading…')
    })

    it('shows the chosen location while the request is on its way, and ignores a second change meanwhile', async () => {
      let answer!: (s: IngestSource) => void
      vi.mocked(apiPut).mockImplementation((() => new Promise((resolve) => { answer = resolve })) as never)
      await mountView()
      await openSources()

      const el = select('source-location-2')!
      el.value = '2'
      el.dispatchEvent(new Event('change', { bubbles: true }))
      await nextTick()
      expect(select('source-location-2')!.value).toBe('2')
      expect(byId('source-row-2')!.getAttribute('aria-busy')).toBe('true')

      await choose(2, '1')
      expect(vi.mocked(apiPut)).toHaveBeenCalledTimes(1)

      answer(ASSIGNED_LUMO)
      await flushPromises()
      expect(select('source-location-2')!.value).toBe('2')
      expect(byId('source-row-2')!.getAttribute('aria-busy')).toBeNull()
    })

    it('shows the server’s 409 above the table, puts the select back and confirms nothing', async () => {
      vi.mocked(apiPut).mockRejectedValue(new ApiError(409, 'Conflict', { message: 'Location "Klinik Graz" is disabled' }))
      await mountView()
      await openSources()
      const readsBefore = vi.mocked(apiGet).mock.calls.filter((c) => c[0] === LOCATIONS_URL).length
      await choose(2, '2')

      expect(text('sources-action-error')).toContain('Source “Optomed Lumo” could not be assigned.')
      expect(text('sources-action-error')).toContain('Location "Klinik Graz" is disabled')
      expect(select('source-location-2')!.value).toBe('')
      expect(byId('source-row-2')!.getAttribute('data-needs-location')).toBe('true')
      expect(toasts()).toEqual([])
      expect(vi.mocked(apiGet).mock.calls.filter((c) => c[0] === LOCATIONS_URL).length).toBe(readsBefore)

      await click('sources-action-error-dismiss')
      expect(byId('sources-action-error')).toBeNull()
    })

    it('shows the server’s 404 the same way', async () => {
      vi.mocked(apiPut).mockRejectedValue(new ApiError(404, 'Not Found', { message: 'No ingest source with id 2' }))
      await mountView()
      await openSources()
      await choose(2, '1')
      expect(text('sources-action-error')).toContain('No ingest source with id 2')
      expect(select('source-location-2')!.value).toBe('')
    })

    it('names the status when the refusal carries no text', async () => {
      vi.mocked(apiPut).mockRejectedValue(new ApiError(500, 'Server Error', null))
      await mountView()
      await openSources()
      await choose(2, '1')
      expect(text('sources-action-error')).toContain('The change could not be saved (HTTP 500).')
    })

    it('clears an old refusal when the next change starts', async () => {
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: 'Location is disabled' }))
      vi.mocked(apiPut).mockResolvedValueOnce({ ...REMIDIO, locationId: 1, locationName: 'MUW Augenklinik' })
      await mountView()
      await openSources()
      await choose(2, '2')
      expect(byId('sources-action-error')).not.toBeNull()
      await choose(3, '1')
      expect(byId('sources-action-error')).toBeNull()
    })
  })

  describe('in German, the working language', () => {
    it('speaks German on both tabs, with no key left untranslated', async () => {
      const german = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de: deMessages } })
      appI18n.global.locale.value = 'de-AT'
      await mountView(german)
      expect(text('locations-tab-locations')).toContain('Standorte')
      expect(text('locations-tab-sources')).toContain('Quellen')
      expect(text('locations-tab-sources')).toContain('2 Quellen ohne Standort')

      await openSources()
      const page = document.body.textContent!
      expect(page).toContain('Wartende Dateien')
      expect(page).toContain('Zuletzt gesehen')
      expect(text('source-waiting-2')).toContain('12 Dateien warten')
      expect(text('source-kind-1')).toBe('Uploader (Erfassungs-PC)')
      expect(text('source-kind-2')).toBe('DICOM-Gerät (AE-Titel)')
      expect(text('source-kind-3')).toBe('Remidio-Konto')
      expect(text('sources-summary')).toBe(
        'Quellen ohne Standort: 2. Ihre wartenden Dateien sieht nur ein Systemadministrator: 12.',
      )
      expect(Array.from(select('source-location-2')!.options).map((o) => o.textContent?.trim())).toEqual(
        ['nicht zugeordnet', 'Klinik Graz', 'MUW Augenklinik'],
      )
      expect(text('source-seen-2')).toBe('noch nie')
      expect(text('source-waiting-1')).toBe('3')
      // vue-i18n prints the key itself for a message it cannot find.
      expect(document.body.textContent).not.toMatch(/\b(adminLocations|uploaders|system|common)\.[a-zA-Z]+/)
    })
  })

  describe('accessibility', () => {
    const RULES = {
      runOnly: { type: 'tag' as const, values: ['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'] },
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    }

    it('has no structural violations on the Standorte tab with its tab list', async () => {
      await mountView()
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })

    it('has none on the Quellen tab', async () => {
      await mountView()
      await openSources()
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })
  })
})

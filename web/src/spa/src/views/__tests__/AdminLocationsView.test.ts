/**
 * AdminLocationsView — System → Standorte, the system administrator's list
 * of the global locations.
 *
 * Pins: the list shows every location, removed ones included, with the
 * server's counts; creating needs a code and a name before it posts, shows
 * the server's per-field refusals under the fields and confirms with a
 * notice; editing leaves the code alone and sends a cleared field as an
 * empty string; disabling and restoring ask first, and the 409 refusal of
 * a disable is shown, not swallowed; the detail panel lists the studies
 * and sites of a location and the people assigned to it, and assigning
 * and unassigning a person confirm too.
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
import type { Location, LocationDetail } from '@/types/location'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
const BASE = '/pages/api/v1/admin/locations'

function loc(over: Partial<Location> & Pick<Location, 'id' | 'code' | 'name'>): Location {
  return {
    status: 'available', street: null, city: null, zip: null, country: null,
    siteCount: 0, studyCount: 0, ingestItemCount: 0, userCount: 0,
    ...over,
  }
}

/** In the server's order: by name. */
const GRAZ = loc({ id: 2, code: 'GRAZ', name: 'Klinik Graz', city: 'Graz', country: 'AT',
  siteCount: 1, ingestItemCount: 12 })
const LINZ = loc({ id: 3, code: 'LINZ', name: 'Klinik Linz', city: 'Linz', status: 'removed',
  ingestItemCount: 5, userCount: 1 })
const MUW = loc({ id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik', street: 'Währinger Gürtel 18-20',
  zip: '1090', city: 'Wien', country: 'AT', siteCount: 2, studyCount: 1, ingestItemCount: 340, userCount: 2 })
const LOCATIONS = [GRAZ, LINZ, MUW]

const ANNA = { username: 'anna', displayName: 'Anna Beispiel', canManageSources: false }
const BERND = { username: 'bernd', displayName: 'Bernd Muster', canManageSources: false }

const MUW_DETAIL: LocationDetail = {
  ...MUW,
  studies: [
    { oid: 'S_NAMD', name: 'nAMD Registry', isSite: false, parentOid: null, status: 'available' },
    { oid: 'S_ALPHA_1', name: 'Alpha Wien', isSite: true, parentOid: 'S_ALPHA', status: 'available' },
    { oid: 'S_ALPHA_2', name: 'Alpha Süd', isSite: true, parentOid: 'S_ALPHA', status: 'pending' },
  ],
  users: [ANNA, BERND],
}
const DETAILS: Record<number, LocationDetail> = {
  1: MUW_DETAIL,
  2: { ...GRAZ, studies: [], users: [] },
  3: { ...LINZ, studies: [], users: [{ username: 'carl', displayName: 'Carl Alt', canManageSources: false }] },
}

/** The sysadmin's study list, only for the names of a site's parent. */
const STUDIES = [
  { oid: 'S_ALPHA', name: 'Alpha Study', uniqueIdentifier: 'alpha', principalInvestigator: 'Dr. A',
    createdDate: '2026-03-04', status: 'AVAILABLE', parentOid: null, sites: [] },
]

const clone = <T,>(v: T): T => structuredClone(v)

function answerGets(over: { locations?: Location[]; details?: Record<number, LocationDetail>; studiesFail?: boolean } = {}) {
  const locations = over.locations ?? LOCATIONS
  const details = over.details ?? DETAILS
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === BASE) return clone(locations)
    if (path === '/pages/api/v1/admin/studies') {
      if (over.studiesFail) throw new ApiError(500, 'Server Error', null)
      return clone(STUDIES)
    }
    const m = path.match(/^\/pages\/api\/v1\/admin\/locations\/(\d+)$/)
    if (m) {
      const d = details[Number(m[1])]
      if (d) return clone(d)
      throw new ApiError(404, 'Not Found', { message: `No location with id ${m[1]}` })
    }
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

/* ---- DOM helpers: the dialog is teleported to <body>, so everything is read from there ---- */

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

function rowIds(): string[] {
  return Array.from(document.body.querySelectorAll('[data-testid^="admin-location-"]')).map(
    (tr) => tr.getAttribute('data-testid')!.slice('admin-location-'.length),
  )
}

function toasts(): string[] {
  return useNotificationsStore().toasts.map((x) => x.message)
}

describe('AdminLocationsView', () => {
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

  describe('the list', () => {
    it('lists every location with the server’s counts, removed ones included', async () => {
      await mountView()
      expect(vi.mocked(apiGet)).toHaveBeenCalledWith(BASE)
      expect(rowIds()).toEqual(['2', '3', '1'])

      const muw = text('admin-location-1')!
      expect(muw).toContain('MUW-AUGEN')
      expect(muw).toContain('MUW Augenklinik')
      expect(muw).toContain('Wien, AT')
      expect(text('count-sites-1')).toBe('2')
      expect(text('count-studies-1')).toBe('1')
      expect(text('count-files-1')).toBe('340')
      expect(text('count-users-1')).toBe('2')
      expect(text('count-sites-2')).toBe('1')
      expect(text('count-files-3')).toBe('5')

      expect(text('status-1')).toContain('Active')
      expect(text('status-3')).toContain('Disabled')
      expect(text('admin-locations-subtitle')).toBe('Locations: 3 (2 active)')
    })

    it('names its columns and sits in the System section', async () => {
      await mountView()
      const headers = Array.from(document.body.querySelectorAll('th')).map((th) => th.textContent?.trim())
      expect(headers).toEqual(expect.arrayContaining(
        ['Code', 'Name', 'Place', 'Study sites', 'Studies', 'Files', 'People', 'Status'],
      ))
      expect(document.body.querySelector('h1')?.textContent?.trim()).toBe('Locations')
      expect(byId('rail-locations')?.getAttribute('aria-current')).toBe('page')
    })

    it('is a flat page of its section: a header with no trail and no eyebrow (DR-030)', async () => {
      await mountView()
      expect(byId('page-header')).not.toBeNull()
      expect(byId('page-trail')).toBeNull()
      expect(byId('page-eyebrow')).toBeNull()
    })

    it('offers edit to every location, disable to an active one and restore to a disabled one', async () => {
      await mountView()
      for (const id of ['1', '2', '3']) expect(byId(`location-edit-${id}`), id).not.toBeNull()
      expect(byId('location-disable-1')).not.toBeNull()
      expect(byId('location-restore-1')).toBeNull()
      expect(byId('location-restore-3')).not.toBeNull()
      expect(byId('location-disable-3')).toBeNull()
    })

    it('shows that it is loading until the list arrives', async () => {
      let answer!: (rows: Location[]) => void
      vi.mocked(apiGet).mockImplementation((() => new Promise((resolve) => { answer = resolve })) as never)
      await mountView()
      expect(document.body.textContent).toContain('Loading…')
      expect(rowIds()).toEqual([])
      // No count of "0 locations" before the list is known.
      expect(byId('admin-locations-subtitle')).toBeNull()

      answer(clone(LOCATIONS))
      await flushPromises()
      expect(document.body.textContent).not.toContain('Loading…')
      expect(rowIds()).toEqual(['2', '3', '1'])
      expect(text('admin-locations-subtitle')).toBe('Locations: 3 (2 active)')
    })

    it('says so when there is no location', async () => {
      answerGets({ locations: [] })
      await mountView()
      expect(rowIds()).toEqual([])
      expect(document.body.textContent).toContain('There is no location yet.')
    })

    it('shows the server’s refusal to anyone who is not a system administrator', async () => {
      vi.mocked(apiGet).mockRejectedValue(
        new ApiError(403, 'Forbidden', { message: 'Only a system administrator may manage locations' }),
      )
      await mountView()
      expect(text('locations-error')).toContain('Only a system administrator may manage locations')
      expect(rowIds()).toEqual([])
      expect(byId('location-create')).toBeNull()
    })
  })

  describe('creating a location', () => {
    const dialogOpen = () => document.body.querySelector('[role="dialog"]') !== null
    const labelOf = (id: string) => document.body.querySelector(`label[for="${id}"]`)!

    it('marks the code and the name as required and posts only once both are filled', async () => {
      await mountView()
      await click('location-create')
      expect(dialogOpen()).toBe(true)
      expect(text('location-dialog-title')).toBe('Create location')
      expect(document.body.textContent).toContain('2 to 30 characters')
      expect(labelOf('location-code').classList.contains('req')).toBe(true)
      expect(labelOf('location-name').classList.contains('req')).toBe(true)
      expect(labelOf('location-city').classList.contains('req')).toBe(false)

      const submit = byId('location-form-submit') as HTMLButtonElement
      expect(submit.disabled).toBe(true)
      await type('location-code', 'LINZ-2')
      expect(submit.disabled).toBe(true)
      await type('location-name', '   ')
      expect(submit.disabled).toBe(true)
      await type('location-name', 'Klinik Linz 2')
      expect(submit.disabled).toBe(false)

      // Whatever reaches the form's submit (Enter in a field) is held to the same rule.
      await type('location-name', '')
      document.body.querySelector('form#location-form')!.dispatchEvent(new Event('submit', { cancelable: true }))
      await flushPromises()
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('posts the trimmed input without the blank optional fields, confirms, and opens the new location', async () => {
      const created = loc({ id: 4, code: 'LINZ-2', name: 'Klinik Linz 2', city: 'Linz', country: 'AT' })
      vi.mocked(apiPost).mockResolvedValue(created)
      answerGets({ details: { ...DETAILS, 4: { ...created, studies: [], users: [] } } })
      await mountView()
      await click('location-create')
      await type('location-code', ' LINZ-2 ')
      await type('location-name', 'Klinik Linz 2 ')
      await type('location-city', 'Linz')
      await type('location-country', 'AT')
      await click('location-form-submit')

      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenCalledWith(BASE, { code: 'LINZ-2', name: 'Klinik Linz 2', city: 'Linz', country: 'AT' })
      expect(dialogOpen()).toBe(false)
      expect(toasts()).toContain('Location “Klinik Linz 2” created.')
      expect(rowIds()).toEqual(['2', '3', '4', '1'])
      expect(text('count-files-4')).toBe('0')
      // The next thing to do with a new location is to assign people: its panel opens.
      expect(vi.mocked(apiGet)).toHaveBeenCalledWith(`${BASE}/4`)
      expect(text('location-detail-title')).toContain('Klinik Linz 2')
    })

    it('shows the server’s refusals under their fields, keeps the dialog, and clears a message once the field is edited', async () => {
      vi.mocked(apiPost).mockRejectedValue(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [
          { field: 'code', message: 'A location with this code exists already' },
          { field: 'name', message: 'Name must not be longer than 100 characters' },
        ],
      }))
      await mountView()
      await click('location-create')
      await type('location-code', 'MUW-AUGEN')
      await type('location-name', 'MUW Augenklinik')
      await click('location-form-submit')

      expect(text('field-error-code')).toBe('A location with this code exists already')
      expect(text('field-error-name')).toBe('Name must not be longer than 100 characters')
      expect(field('location-code')!.getAttribute('aria-invalid')).toBe('true')
      // The generic "Validation failed" is not repeated above the fields.
      expect(byId('location-form-error')).toBeNull()
      expect(dialogOpen()).toBe(true)
      expect(toasts()).toEqual([])
      expect(rowIds()).toEqual(['2', '3', '1'])

      await type('location-code', 'MUW-AUGEN-2')
      expect(byId('field-error-code')).toBeNull()
      expect(byId('field-error-name')).not.toBeNull()
    })

    it('shows a refusal that is about no field as a message of the form', async () => {
      vi.mocked(apiPost).mockRejectedValue(
        new ApiError(403, 'Forbidden', { message: 'Only a system administrator may manage locations' }),
      )
      await mountView()
      await click('location-create')
      await type('location-code', 'LINZ-2')
      await type('location-name', 'Klinik Linz 2')
      await click('location-form-submit')
      expect(text('location-form-error')).toContain('Only a system administrator may manage locations')
      expect(dialogOpen()).toBe(true)
      expect(toasts()).toEqual([])
    })

    it('puts the cursor in the code field, and hands the focus back to the button when it is cancelled', async () => {
      await mountView()
      await click('location-create')
      expect(document.activeElement).toBe(field('location-code'))
      await click('location-form-cancel')
      expect(document.activeElement).toBe(byId('location-create'))
    })

    it('closes on Cancel and on Escape without a request, and opens blank the next time', async () => {
      await mountView()
      await click('location-create')
      await type('location-code', 'LINZ-2')
      await click('location-form-cancel')
      expect(dialogOpen()).toBe(false)

      await click('location-create')
      expect(field('location-code')!.value).toBe('')
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      await flushPromises()
      expect(dialogOpen()).toBe(false)
      expect(apiPost).not.toHaveBeenCalled()
    })
  })

  describe('editing a location', () => {
    const dialogOpen = () => document.body.querySelector('[role="dialog"]') !== null

    it('opens with the stored values, leaves the code alone, and sends a cleared field as an empty string', async () => {
      const saved: LocationDetail = { ...MUW_DETAIL, name: 'MUW Augenklinik Wien', street: null }
      vi.mocked(apiPut).mockResolvedValue(saved)
      await mountView()
      await click('location-edit-1')
      expect(text('location-dialog-title')).toBe('Edit location')
      expect(document.activeElement).toBe(field('location-name'))
      expect(document.body.textContent).toContain('The code is fixed once the location exists.')
      expect(field('location-code')!.value).toBe('MUW-AUGEN')
      expect(field('location-code')!.readOnly || field('location-code')!.disabled).toBe(true)
      expect(field('location-name')!.value).toBe('MUW Augenklinik')
      expect(field('location-street')!.value).toBe('Währinger Gürtel 18-20')
      expect(field('location-zip')!.value).toBe('1090')

      await type('location-name', 'MUW Augenklinik Wien')
      await type('location-street', '')
      await click('location-form-submit')

      expect(apiPut).toHaveBeenCalledTimes(1)
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1`, {
        name: 'MUW Augenklinik Wien', street: '', zip: '1090', city: 'Wien', country: 'AT',
      })
      expect(dialogOpen()).toBe(false)
      expect(toasts()).toContain('Location “MUW Augenklinik Wien” saved.')
      expect(text('admin-location-1')).toContain('MUW Augenklinik Wien')
    })

    it('blocks saving without a name', async () => {
      await mountView()
      await click('location-edit-1')
      const submit = byId('location-form-submit') as HTMLButtonElement
      expect(submit.disabled).toBe(false)
      await type('location-name', '  ')
      expect(submit.disabled).toBe(true)
    })

    it('shows the server’s refusal under the field and leaves the row as it was', async () => {
      vi.mocked(apiPut).mockRejectedValue(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [{ field: 'name', message: 'A location with this name exists already' }],
      }))
      await mountView()
      await click('location-edit-1')
      await type('location-name', 'Klinik Graz')
      await click('location-form-submit')
      expect(text('field-error-name')).toBe('A location with this name exists already')
      expect(dialogOpen()).toBe(true)
      expect(toasts()).toEqual([])
      expect(text('admin-location-1')).toContain('MUW Augenklinik')
    })
  })

  describe('disabling and restoring', () => {
    it('asks first, then disables the location and says so', async () => {
      vi.mocked(apiPost).mockResolvedValue({ ...GRAZ, status: 'removed' })
      await mountView()
      await click('location-disable-2')

      expect(confirmMock).toHaveBeenCalledTimes(1)
      const ask = confirmMock.mock.calls[0][0] as { message: string; danger?: boolean }
      expect(ask.message).toContain('Klinik Graz')
      expect(ask.danger).toBe(true)
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenCalledWith(`${BASE}/2/disable`, {})
      expect(text('status-2')).toContain('Disabled')
      expect(byId('location-restore-2')).not.toBeNull()
      expect(byId('location-disable-2')).toBeNull()
      expect(toasts()).toContain('Location “Klinik Graz” disabled.')
      expect(text('admin-locations-subtitle')).toBe('Locations: 3 (1 active)')
    })

    it('does nothing when the confirmation is declined', async () => {
      confirmMock.mockResolvedValue(false)
      await mountView()
      await click('location-disable-2')
      expect(confirmMock).toHaveBeenCalledTimes(1)
      expect(apiPost).not.toHaveBeenCalled()
      expect(text('status-2')).toContain('Active')
      expect(toasts()).toEqual([])
    })

    it('shows the server’s 409 refusal, leaves the location active, and lets the banner be dismissed', async () => {
      const refusal = 'The location cannot be disabled: 3 studies or sites still use it.'
      vi.mocked(apiPost).mockRejectedValue(new ApiError(409, 'Conflict', { message: refusal }))
      await mountView()
      await click('location-disable-1')

      const banner = text('locations-action-error')!
      expect(banner).toContain(refusal)
      expect(banner).toContain('MUW Augenklinik')
      expect(byId('locations-action-error')!.getAttribute('role')).toBe('alert')
      expect(text('status-1')).toContain('Active')
      expect(toasts()).toEqual([])

      await click('locations-action-error-dismiss')
      expect(byId('locations-action-error')).toBeNull()
    })

    it('asks first, then restores a disabled location and says so', async () => {
      vi.mocked(apiPost).mockResolvedValue({ ...LINZ, status: 'available' })
      await mountView()
      await click('location-restore-3')

      expect(confirmMock).toHaveBeenCalledTimes(1)
      const ask = confirmMock.mock.calls[0][0] as { message: string; danger?: boolean }
      expect(ask.message).toContain('Klinik Linz')
      expect(ask.danger).toBeFalsy()
      expect(apiPost).toHaveBeenCalledWith(`${BASE}/3/restore`, {})
      expect(text('status-3')).toContain('Active')
      expect(byId('location-disable-3')).not.toBeNull()
      expect(toasts()).toContain('Location “Klinik Linz” restored.')
    })

    it('shows a refusal of a restore as well', async () => {
      vi.mocked(apiPost).mockRejectedValue(new ApiError(404, 'Not Found', { message: 'No location with id 3' }))
      await mountView()
      await click('location-restore-3')
      expect(text('locations-action-error')).toContain('could not be restored')
      expect(text('locations-action-error')).toContain('No location with id 3')
      expect(text('status-3')).toContain('Disabled')
      expect(toasts()).toEqual([])
    })
  })

  describe('the detail panel', () => {
    it('opens on the name, lists the studies and sites and the assigned people, and takes the focus', async () => {
      await mountView()
      expect(byId('location-detail')).toBeNull()
      await click('location-select-1')

      expect(vi.mocked(apiGet)).toHaveBeenCalledWith(`${BASE}/1`)
      expect(byId('location-select-1')!.getAttribute('aria-expanded')).toBe('true')
      expect(byId('location-select-2')!.getAttribute('aria-expanded')).toBe('false')
      expect(text('location-detail-title')).toContain('MUW Augenklinik')
      expect(text('location-detail-title')).toContain('MUW-AUGEN')
      expect(document.activeElement).toBe(byId('location-detail-title'))

      const panel = text('location-detail')!
      expect(panel).toContain('Währinger Gürtel 18-20, 1090 Wien, AT')

      const study = text('detail-study-S_NAMD')!
      expect(study).toContain('nAMD Registry')
      expect(study).toContain('Study')
      expect(study).not.toContain('Study site')
      // A site shows the study it belongs to, by name.
      const site = text('detail-study-S_ALPHA_1')!
      expect(site).toContain('Alpha Wien')
      expect(site).toContain('Study site')
      expect(site).toContain('of Alpha Study')
      expect(site).not.toContain('Available')
      expect(text('detail-study-S_ALPHA_2')).toContain('Pending')

      expect(text('detail-user-anna')).toContain('Anna Beispiel')
      expect(text('detail-user-anna')).toContain('anna')
      expect(text('detail-user-bernd')).toContain('Bernd Muster')
    })

    it('marks a person who holds the right to manage the location’s sources', async () => {
      answerGets({ details: { ...DETAILS, 1: { ...MUW_DETAIL, users: [{ ...ANNA, canManageSources: true }, BERND] } } })
      await mountView()
      await click('location-select-1')
      expect(text('detail-user-anna')).toContain('Location admin')
      expect(text('detail-user-bernd')).not.toContain('Location admin')
    })

    it('names the parent study by its OID when the study list cannot be read', async () => {
      answerGets({ studiesFail: true })
      await mountView()
      await click('location-select-1')
      expect(text('detail-study-S_ALPHA_1')).toContain('of S_ALPHA')
      // The failure is not this page's: no banner for it.
      expect(byId('locations-error')).toBeNull()
    })

    it('says what is missing for a location without an address, studies or people', async () => {
      answerGets({ details: { ...DETAILS, 2: { ...GRAZ, city: null, country: null, studies: [], users: [] } } })
      await mountView()
      await click('location-select-2')
      const panel = text('location-detail')!
      expect(panel).toContain('No address on file.')
      expect(panel).toContain('No study and no study site at this location yet.')
      expect(panel).toContain('Nobody is assigned to this location explicitly.')
    })

    it('closes with its button or a second click on the name, and hands the focus back to the name', async () => {
      await mountView()
      await click('location-select-1')
      await click('location-detail-close')
      expect(byId('location-detail')).toBeNull()
      expect(document.activeElement).toBe(byId('location-select-1'))
      expect(byId('location-select-1')!.getAttribute('aria-expanded')).toBe('false')

      await click('location-select-1')
      expect(byId('location-detail')).not.toBeNull()
      await click('location-select-1')
      expect(byId('location-detail')).toBeNull()
    })

    it('switches to another location', async () => {
      await mountView()
      await click('location-select-1')
      await click('location-select-2')
      expect(text('location-detail-title')).toContain('Klinik Graz')
      expect(byId('location-select-1')!.getAttribute('aria-expanded')).toBe('false')
      expect(byId('location-select-2')!.getAttribute('aria-expanded')).toBe('true')
    })

    it('shows the server’s message when the detail cannot be read', async () => {
      answerGets({ details: { 1: MUW_DETAIL } })
      await mountView()
      await click('location-select-2')
      expect(text('location-detail-load-error')).toContain('No location with id 2')
    })

    it('keeps the people of a disabled location, but gives it no new ones', async () => {
      await mountView()
      await click('location-select-3')
      expect(text('location-detail')).toContain('The location is disabled. Restore it to assign people.')
      expect(field('location-assign-username')).toBeNull()
      expect(text('detail-user-carl')).toContain('Carl Alt')
      expect(byId('location-unassign-carl')).not.toBeNull()
    })

    it('follows an edit and a disable of its own location', async () => {
      vi.mocked(apiPut).mockResolvedValue({ ...DETAILS[2], name: 'Klinik Graz Mitte' })
      vi.mocked(apiPost).mockResolvedValue({ ...GRAZ, name: 'Klinik Graz Mitte', status: 'removed' })
      await mountView()
      await click('location-select-2')

      await click('location-edit-2')
      await type('location-name', 'Klinik Graz Mitte')
      await click('location-form-submit')
      expect(text('location-detail-title')).toContain('Klinik Graz Mitte')

      await click('location-disable-2')
      expect(text('location-detail-title')).toContain('Disabled')
      expect(text('location-detail')).toContain('The location is disabled.')
    })
  })

  describe('assigning and unassigning people', () => {
    it('assigns a person by username, confirms, and keeps the count in the list in step', async () => {
      const carl = { username: 'carl.x', displayName: 'Carl Neu', canManageSources: false }
      vi.mocked(apiPut).mockResolvedValue({ ...MUW_DETAIL, userCount: 3, users: [ANNA, BERND, carl] })
      await mountView()
      await click('location-select-1')

      const add = byId('location-assign-submit') as HTMLButtonElement
      expect(add.disabled).toBe(true)
      await type('location-assign-username', '   ')
      expect(add.disabled).toBe(true)
      await type('location-assign-username', ' carl.x ')
      expect(add.disabled).toBe(false)
      await click('location-assign-submit')

      expect(apiPut).toHaveBeenCalledTimes(1)
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1/users/carl.x`, {})
      expect(text('detail-user-carl.x')).toContain('Carl Neu')
      expect(text('count-users-1')).toBe('3')
      expect(field('location-assign-username')!.value).toBe('')
      expect(toasts()).toContain('Carl Neu is assigned to location “MUW Augenklinik”.')
    })

    it('sends a username that holds URL characters as one encoded path segment', async () => {
      vi.mocked(apiPut).mockResolvedValue({ ...MUW_DETAIL })
      await mountView()
      await click('location-select-1')
      await type('location-assign-username', 'b.k@muw/x')
      await click('location-assign-submit')
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1/users/b.k%40muw%2Fx`, {})
    })

    it('is a form, so Enter in the field assigns', async () => {
      vi.mocked(apiPut).mockResolvedValue({ ...MUW_DETAIL })
      await mountView()
      await click('location-select-1')
      const form = byId('location-assign-form')!
      expect(form.tagName).toBe('FORM')
      expect((byId('location-assign-submit') as HTMLButtonElement).type).toBe('submit')
      await type('location-assign-username', 'anna')
      form.dispatchEvent(new Event('submit', { cancelable: true }))
      await flushPromises()
      expect(apiPut).toHaveBeenCalledTimes(1)
    })

    it('shows the server’s refusal next to the field, keeps what was typed, and confirms nothing', async () => {
      vi.mocked(apiPut).mockRejectedValue(new ApiError(404, 'Not Found', { message: 'No active user "nobody"' }))
      await mountView()
      await click('location-select-1')
      await type('location-assign-username', 'nobody')
      await click('location-assign-submit')

      expect(text('location-detail-error')).toContain('No active user "nobody"')
      expect(field('location-assign-username')!.value).toBe('nobody')
      expect(field('location-assign-username')!.getAttribute('aria-invalid')).toBe('true')
      expect(toasts()).toEqual([])
      expect(text('count-users-1')).toBe('2')
      expect(byId('detail-user-nobody')).toBeNull()
    })

    it('removes a person from the location, confirms, and keeps the count in step', async () => {
      vi.mocked(apiDelete).mockResolvedValue({ ...MUW_DETAIL, userCount: 1, users: [BERND] })
      await mountView()
      await click('location-select-1')
      expect(byId('location-unassign-anna')!.getAttribute('aria-label')).toBe('Remove Anna Beispiel from this location')
      await click('location-unassign-anna')

      expect(apiDelete).toHaveBeenCalledTimes(1)
      expect(apiDelete).toHaveBeenCalledWith(`${BASE}/1/users/anna`)
      expect(byId('detail-user-anna')).toBeNull()
      expect(byId('detail-user-bernd')).not.toBeNull()
      expect(text('count-users-1')).toBe('1')
      expect(toasts()).toContain('Anna Beispiel is no longer assigned to location “MUW Augenklinik”.')
      // The pressed button is gone; the cursor goes on to the add field.
      expect(document.activeElement).toBe(field('location-assign-username'))
    })

    it('shows the server’s refusal of a removal and keeps the person listed', async () => {
      vi.mocked(apiDelete).mockRejectedValue(new ApiError(500, 'Server Error', { message: 'Database unavailable' }))
      await mountView()
      await click('location-select-1')
      await click('location-unassign-anna')
      expect(text('location-detail-error')).toContain('Database unavailable')
      // The refusal is about the removal, not about what is typed in the add field.
      expect(field('location-assign-username')!.getAttribute('aria-invalid')).toBeNull()
      expect(byId('detail-user-anna')).not.toBeNull()
      expect(toasts()).toEqual([])
    })
  })

  describe('in German, the working language', () => {
    it('speaks German on the list, in the panel and in the dialog, with no key left untranslated', async () => {
      const german = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de: deMessages } })
      appI18n.global.locale.value = 'de-AT'
      await mountView(german)
      await click('location-select-1')
      const page = document.body.textContent!
      expect(page).toContain('Standorte')
      expect(page).toContain('Standort anlegen')
      expect(page).toContain('Studien-Standort')
      expect(page).toContain('Zugewiesene Personen')
      expect(text('status-1')).toContain('Aktiv')
      expect(text('status-3')).toContain('Deaktiviert')

      await click('location-edit-1')
      expect(text('location-dialog-title')).toBe('Standort bearbeiten')
      expect(document.body.textContent).toContain('Das Kürzel ist nach dem Anlegen fest.')
      // vue-i18n prints the key itself for a message it cannot find.
      expect(document.body.textContent).not.toMatch(/\b(adminLocations|system|common|adminStudies)\.[a-zA-Z]+/)
    })
  })

  describe('accessibility', () => {
    const RULES = {
      runOnly: { type: 'tag' as const, values: ['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'] },
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    }

    it('has no structural violations with the list and a detail panel open', async () => {
      await mountView()
      await click('location-select-1')
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })

    it('has none with the dialog open', async () => {
      await mountView()
      await click('location-edit-1')
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })
  })
})

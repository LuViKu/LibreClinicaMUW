import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiPatch: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {
    status: number
    body: unknown
    constructor(status: number, message: string, body: unknown = null) {
      super(message)
      this.status = status
      this.body = body
    }
    get isUnauthorized() { return this.status === 401 }
    get isForbidden() { return this.status === 403 }
  },
  ApiNetworkError: class ApiNetworkError extends Error {},
}))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => () => Promise.resolve(true) }))

import { apiGet, apiPost, apiPut, ApiError } from '@/api/client'
import { useNotificationsStore } from '@/stores/notifications'
import SitesView from '@/views/SitesView.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'
import type { AuthenticatedUser, LocationMode } from '@/types/auth'
import type { LocationOption } from '@/types/location'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const SITES_PATH = '/pages/api/v1/studies/S_1/sites'
const LOCATIONS_PATH = '/pages/api/v1/locations'

function option(id: number, name: string): LocationOption {
  return { id, code: `L${id}`, name, street: null, city: null, zip: null, country: null }
}
const MUW = option(1, 'MUW Augenklinik')
const GRAZ = option(2, 'Klinik Graz')

const SITE_ONE = {
  oid: 'SITE_1', name: 'Site One', status: 'available', briefSummary: '', principalInvestigator: 'Dr X',
  locationId: 1, locationCode: 'L1', locationName: 'MUW Augenklinik',
}

interface MountOptions {
  mode?: LocationMode
  locations?: LocationOption[]
}

const mounted: VueWrapper[] = []
afterEach(() => { while (mounted.length) mounted.pop()!.unmount() })

/**
 * The backend lets a director or coordinator create and edit sites
 * (userMayEditStudy) but disable and restore only a system administrator
 * (roleMayLifecycleStudy), so only the latter sees those two buttons.
 */
async function mountAs(role: AuthenticatedUser['role'], opts: MountOptions = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    username: 'u', displayName: 'U', email: null, role, siteLabel: null, source: 'local',
    mfaSatisfied: true, profileComplete: true, locale: null, timezone: null, manageableLocations: [],
    activeStudy: { id: 1, oid: 'S_1', name: 'Study', isSite: false, role, roles: [role] },
    ...(opts.mode ? { locationMode: opts.mode } : {}),
  } as unknown as AuthenticatedUser
  const locations = opts.locations ?? [MUW]
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === SITES_PATH) return [structuredClone(SITE_ONE)]
    if (path === LOCATIONS_PATH) return structuredClone(locations)
    throw new Error(`unexpected GET ${path}`)
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  const w = mount(SitesView, { global: { plugins: [pinia, router, i18n] }, attachTo: document.body })
  mounted.push(w)
  await flushPromises()
  return w
}

function button(w: VueWrapper, text: string) {
  return w.findAll('button').find((b) => b.text() === text)!
}

async function fillRequired(w: VueWrapper) {
  await w.find('#site-name').setValue('New Site')
  await w.find('#site-uid').setValue('new-site')
  await w.find('#site-pi').setValue('Dr Y')
}

/** The move dialog is teleported to the body, outside the wrapper. */
async function chooseInDialog(value: string) {
  const select = document.getElementById('site-move-location') as HTMLSelectElement
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flushPromises()
}

async function submitDialog() {
  ;(document.getElementById('site-location-form') as HTMLFormElement).dispatchEvent(new Event('submit'))
  await flushPromises()
}

describe('SitesView write controls by role', () => {
  beforeEach(() => vi.clearAllMocks())

  it.each(['CRC', 'Data Manager', 'Administrator'] as const)('%s sees the create button', async (role) => {
    const w = await mountAs(role)
    expect(w.text()).toContain(enMessages.sites.createAction)
  })

  it('shows disable only to the administrator', async () => {
    for (const role of ['CRC', 'Data Manager'] as const) {
      const w = await mountAs(role)
      expect(w.text(), role).toContain('Site One')
      expect(w.text(), role).not.toContain(enMessages.sites.disable)
    }
    const admin = await mountAs('Administrator')
    expect(admin.text()).toContain(enMessages.sites.disable)
  })
})

describe('SitesView create', () => {
  beforeEach(() => vi.clearAllMocks())

  it('confirms a created site with a success notice', async () => {
    const w = await mountAs('Administrator')
    vi.mocked(apiPost).mockResolvedValueOnce({
      oid: 'S_NEW', name: 'New Site', status: 'pending', briefSummary: '', principalInvestigator: 'Dr Y',
    })
    await button(w, enMessages.sites.createAction).trigger('click')
    await fillRequired(w)
    await button(w, enMessages.sites.submitCreate).trigger('click')
    await flushPromises()
    expect(apiPost).toHaveBeenCalledTimes(1)
    const toasts = useNotificationsStore().toasts.map((x) => x.message)
    expect(toasts).toContain(enMessages.sites.created.replace('{name}', 'New Site'))
  })

  it('no longer asks for the facility name and city; the contact e-mail stays', async () => {
    const w = await mountAs('Administrator')
    await button(w, enMessages.sites.createAction).trigger('click')
    expect(w.find('#site-fac-name').exists()).toBe(false)
    expect(w.find('#site-fac-city').exists()).toBe(false)
    expect(w.find('#site-fac-email').exists()).toBe(true)
  })

  it('preselects the one location there is, still shows it, and sends its id', async () => {
    const w = await mountAs('Data Manager', { mode: 'single', locations: [MUW] })
    expect(apiGet).toHaveBeenCalledWith(LOCATIONS_PATH)
    await button(w, enMessages.sites.createAction).trigger('click')
    const select = w.get('#site-location').element as HTMLSelectElement
    expect(select.value).toBe('1')
    expect(Array.from(select.options).map((o) => o.text)).toEqual(['MUW Augenklinik'])
    expect(w.text()).toContain(enMessages.sites.field.location)

    await fillRequired(w)
    vi.mocked(apiPost).mockResolvedValueOnce({ ...SITE_ONE, oid: 'S_NEW', name: 'New Site' })
    await button(w, enMessages.sites.submitCreate).trigger('click')
    await flushPromises()
    const [path, body] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(SITES_PATH)
    expect(body.locationId).toBe(1)
    expect(body).not.toHaveProperty('facilityName')
    expect(body).not.toHaveProperty('facilityCity')
  })

  it('requires a location when there are several to choose from', async () => {
    const w = await mountAs('Data Manager', { mode: 'multi', locations: [GRAZ, MUW] })
    await button(w, enMessages.sites.createAction).trigger('click')
    await fillRequired(w)
    const select = w.get('#site-location')
    expect((select.element as HTMLSelectElement).value).toBe('')
    expect(button(w, enMessages.sites.submitCreate).attributes('disabled')).toBeDefined()

    await select.setValue('2')
    expect(button(w, enMessages.sites.submitCreate).attributes('disabled')).toBeUndefined()
    vi.mocked(apiPost).mockResolvedValueOnce({ ...SITE_ONE, oid: 'S_NEW', name: 'New Site' })
    await button(w, enMessages.sites.submitCreate).trigger('click')
    await flushPromises()
    const [, body] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(body.locationId).toBe(2)
  })

  it('shows the server’s locationId error on the select', async () => {
    const w = await mountAs('Data Manager', { mode: 'multi', locations: [GRAZ, MUW] })
    await button(w, enMessages.sites.createAction).trigger('click')
    await fillRequired(w)
    await w.get('#site-location').setValue('1')
    vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(400, 'Validation failed', {
      message: 'Validation failed',
      errors: [{ field: 'locationId', message: 'Location is not available' }],
    }))
    await button(w, enMessages.sites.submitCreate).trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="site-location-error"]').text()).toContain('Location is not available')
    expect(w.get('#site-location').attributes('aria-invalid')).toBe('true')
  })
})

describe('SitesView location of each site', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists each site’s location', async () => {
    const w = await mountAs('Monitor')
    expect(w.get('[data-testid="site-location-SITE_1"]').text()).toBe(`${enMessages.sites.location}: MUW Augenklinik`)
  })

  it('does not load the location options for someone who cannot manage sites', async () => {
    await mountAs('Monitor', { mode: 'multi' })
    expect(apiGet).not.toHaveBeenCalledWith(LOCATIONS_PATH)
  })
})

describe('SitesView move a site to another location', () => {
  beforeEach(() => vi.clearAllMocks())

  it('is offered in multi mode to those who manage sites', async () => {
    const w = await mountAs('Data Manager', { mode: 'multi', locations: [GRAZ, MUW] })
    expect(w.get('[data-testid="site-move-SITE_1"]').text()).toBe(enMessages.sites.move.action)
  })

  it('is not offered in single mode, nor when the mode is not reported', async () => {
    for (const mode of ['single', undefined] as const) {
      const w = await mountAs('Administrator', { mode })
      expect(w.find('[data-testid="site-move-SITE_1"]').exists(), String(mode)).toBe(false)
      expect(w.text()).not.toContain(enMessages.sites.move.action)
    }
  })

  it('is not offered to someone who cannot manage sites', async () => {
    const w = await mountAs('Monitor', { mode: 'multi', locations: [GRAZ, MUW] })
    expect(w.find('[data-testid="site-move-SITE_1"]').exists()).toBe(false)
  })

  it('moves the site with PUT { locationId } and confirms it', async () => {
    const w = await mountAs('Data Manager', { mode: 'multi', locations: [GRAZ, MUW] })
    await w.get('[data-testid="site-move-SITE_1"]').trigger('click')
    await flushPromises()
    const select = document.getElementById('site-move-location') as HTMLSelectElement
    expect(select.value).toBe('1')
    const submit = document.querySelector('[data-testid="site-move-submit"]') as HTMLButtonElement
    expect(submit.disabled).toBe(true)

    await chooseInDialog('2')
    expect(submit.disabled).toBe(false)

    vi.mocked(apiPut).mockResolvedValueOnce({ ...SITE_ONE, locationId: 2, locationCode: 'L2', locationName: 'Klinik Graz' })
    await submitDialog()

    expect(apiPut).toHaveBeenCalledWith(`${SITES_PATH}/SITE_1`, { locationId: 2 })
    expect(w.get('[data-testid="site-location-SITE_1"]').text()).toContain('Klinik Graz')
    expect(document.getElementById('site-move-location')).toBeNull()
    const toasts = useNotificationsStore().toasts.map((x) => x.message)
    expect(toasts).toContain(
      enMessages.sites.move.done.replace('{name}', 'Site One').replace('{location}', 'Klinik Graz'),
    )
  })

  it('shows the server’s locationId refusal in the dialog', async () => {
    const w = await mountAs('Data Manager', { mode: 'multi', locations: [GRAZ, MUW] })
    await w.get('[data-testid="site-move-SITE_1"]').trigger('click')
    await flushPromises()
    await chooseInDialog('2')
    vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(400, 'Validation failed', {
      message: 'Validation failed',
      errors: [{ field: 'locationId', message: 'Location is outside your scope' }],
    }))
    await submitDialog()
    expect(document.querySelector('[data-testid="site-move-field-error"]')?.textContent).toContain(
      'Location is outside your scope',
    )
    expect(document.querySelector('[data-testid="site-move-form-error"]')).toBeNull()
  })
})

/**
 * System → Standorte in single- and in multi-location mode (DR-040 amendment).
 *
 * How many locations may be active is chosen at deploy time
 * (`LIBRECLINICA_DEPLOYMENT_LOCATIONS`) and reported by `/me` as
 * `locationMode`. Pinned: in single mode with a location active, the page
 * offers neither "create" nor "restore" and says instead that a second
 * location is a deploy step; with no location active it still offers both,
 * so the first one can be made; multi mode offers everything and says
 * nothing; an answer without the field reads as single; and the server's 409
 * "single-location deployment" reaches the person as a German sentence, not
 * as the server's code word.
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
import { useAuthStore } from '@/stores/auth'
import AdminLocationsView from '@/views/AdminLocationsView.vue'
import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'
import type { AuthenticatedUser, LocationMode } from '@/types/auth'
import type { Location } from '@/types/location'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
const deI18n = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de: deMessages } })
const BASE = '/pages/api/v1/admin/locations'
const REFUSAL = { message: 'single-location deployment' }

function loc(over: Partial<Location> & Pick<Location, 'id' | 'code' | 'name'>): Location {
  return {
    status: 'available', street: null, city: null, zip: null, country: null,
    siteCount: 0, studyCount: 0, ingestItemCount: 0, userCount: 0,
    ...over,
  }
}

const MUW = loc({ id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' })
const LINZ = loc({ id: 3, code: 'LINZ', name: 'Klinik Linz', status: 'removed' })

const clone = <T,>(v: T): T => structuredClone(v)

function answerGets(locations: Location[] | Promise<Location[]> = [LINZ, MUW]) {
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === BASE) return clone(await locations)
    if (path === '/pages/api/v1/admin/ingest-sources') return []
    if (path === '/pages/api/v1/admin/studies') return []
    const m = path.match(/^\/pages\/api\/v1\/admin\/locations\/(\d+)$/)
    if (m) return { ...clone(MUW), id: Number(m[1]), studies: [], users: [] }
    throw new Error(`unexpected GET ${path}`)
  })
}

/** A system administrator; `mode` absent is a `/me` from before the field existed. */
function signIn(mode?: LocationMode) {
  const auth = useAuthStore()
  auth.user = {
    username: 'root', displayName: 'Root', role: 'Administrator', userType: 'SYSADMIN',
    profileComplete: true, mustChangePassword: false, manageableLocations: [], activeStudy: null,
    ...(mode ? { locationMode: mode } : {}),
  } as unknown as AuthenticatedUser
  auth.state = 'authenticated'
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

describe('AdminLocationsView: the location mode', () => {
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

  describe('in single mode with a location active', () => {
    beforeEach(() => signIn('single'))

    it('offers no "create" and says that a second location is a deploy step', async () => {
      await mountView()
      expect(byId('location-create')).toBeNull()
      const line = text('locations-single-mode')!
      expect(line).toContain('set up for a single location')
      expect(line).toContain('LIBRECLINICA_DEPLOYMENT_LOCATIONS=multi')
      expect(line).toContain('deploy README')
    })

    it('offers no "restore" for a disabled location, but still edit, and disable for the active one', async () => {
      await mountView()
      expect(byId('location-restore-3')).toBeNull()
      expect(byId('location-edit-3')).not.toBeNull()
      expect(byId('location-edit-1')).not.toBeNull()
      expect(byId('location-disable-1')).not.toBeNull()
      // No separator is left dangling where the restore was.
      expect(text('admin-location-3')).not.toMatch(/·\s*$/)
    })

    it('offers no "create" while the list is still on its way, rather than a button that vanishes', async () => {
      let arrive!: (l: Location[]) => void
      answerGets(new Promise<Location[]>((resolve) => { arrive = resolve }))
      await mountView()
      expect(byId('location-create')).toBeNull()
      arrive([LINZ, MUW])
      await flushPromises()
      expect(byId('location-create')).toBeNull()
    })

    it('keeps the explanation to the Standorte tab', async () => {
      await mountView()
      await click('locations-tab-sources')
      expect(byId('locations-single-mode')).toBeNull()
    })

    it('says it in German, the working language', async () => {
      appI18n.global.locale.value = 'de-AT'
      await mountView(deI18n)
      const line = text('locations-single-mode')!
      expect(line).toContain('für einen einzigen Standort eingerichtet')
      expect(line).toContain('LIBRECLINICA_DEPLOYMENT_LOCATIONS=multi')
      expect(line).toContain('Deploy-README')
      expect(line).not.toMatch(/adminLocations\./)
    })

    it('has no structural violations with the explanation on screen', async () => {
      await mountView()
      // The page is mounted without the app's landmarks, and jsdom has no layout for contrast.
      const rules = {
        runOnly: { type: 'tag' as const, values: ['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'] },
        rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
      }
      expect(await axe(document.body, rules)).toHaveNoViolations()
    })
  })

  describe('in single mode with no location active', () => {
    beforeEach(() => signIn('single'))

    it('still offers "create", so that the one location can be made, and keeps the explanation', async () => {
      answerGets([])
      await mountView()
      expect(byId('location-create')).not.toBeNull()
      expect(byId('locations-single-mode')).not.toBeNull()
    })

    it('still offers "restore" when every location is disabled', async () => {
      answerGets([LINZ])
      await mountView()
      expect(byId('location-restore-3')).not.toBeNull()
      expect(byId('location-create')).not.toBeNull()
    })
  })

  describe('in multi mode', () => {
    beforeEach(() => signIn('multi'))

    it('offers "create" and "restore", and has nothing to explain', async () => {
      await mountView()
      expect(byId('location-create')).not.toBeNull()
      expect(byId('location-restore-3')).not.toBeNull()
      expect(byId('locations-single-mode')).toBeNull()
    })
  })

  describe('with a /me from before the field existed', () => {
    beforeEach(() => signIn())

    it('reads the session as single mode', async () => {
      await mountView()
      expect(byId('location-create')).toBeNull()
      expect(byId('location-restore-3')).toBeNull()
      expect(byId('locations-single-mode')).not.toBeNull()
    })
  })

  describe('the server’s 409 "single-location deployment"', () => {
    it('is shown in the create dialog as a sentence, not as the server’s code word', async () => {
      // The page thought creating was allowed (no location active); the server knows better.
      signIn('single')
      answerGets([])
      vi.mocked(apiPost).mockRejectedValue(new ApiError(409, 'Conflict', REFUSAL))
      await mountView()
      await click('location-create')
      await type('location-code', 'GRAZ')
      await type('location-name', 'Klinik Graz')
      await click('location-form-submit')

      const message = text('location-form-error')!
      expect(message).toContain('set up for a single location')
      expect(message).not.toContain('single-location deployment')
      expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()
    })

    it('is shown above the list when a restore is refused', async () => {
      // /me says multi, the server has been restarted in single mode since.
      signIn('multi')
      vi.mocked(apiPost).mockRejectedValue(new ApiError(409, 'Conflict', REFUSAL))
      await mountView()
      await click('location-restore-3')

      const message = text('locations-action-error')!
      expect(message).toContain('Klinik Linz')
      expect(message).toContain('set up for a single location')
      expect(message).not.toContain('single-location deployment')
      expect(text('status-3')).toContain('Disabled')
    })

    it('reads in German, the working language', async () => {
      signIn('multi')
      appI18n.global.locale.value = 'de-AT'
      vi.mocked(apiPost).mockRejectedValue(new ApiError(409, 'Conflict', REFUSAL))
      await mountView(deI18n)
      await click('location-restore-3')

      const message = text('locations-action-error')!
      expect(message).toContain('konnte nicht wiederhergestellt werden')
      expect(message).toContain('für einen einzigen Standort eingerichtet')
    })

    it('leaves any other 409 in the server’s own words', async () => {
      signIn('multi')
      vi.mocked(apiPost).mockRejectedValue(new ApiError(409, 'Conflict', { message: 'Location is in use' }))
      await mountView()
      await click('location-restore-3')
      expect(text('locations-action-error')).toContain('Location is in use')
    })
  })
})

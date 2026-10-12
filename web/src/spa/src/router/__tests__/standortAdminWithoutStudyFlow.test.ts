/**
 * The way in for a Standort-Admin who holds no study role, end to end.
 *
 * The real router, the real app shell (top bar included), the real login and
 * picker views and the real Uploaders page, with only the network stubbed.
 * A site's IT contact signs in, has no study to pick, and must land on the
 * Uploaders page with a top bar that does not pretend they have a study or a
 * role's worth of links; a plain user with no study role is stuck on the
 * picker exactly as before.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { apiGet } from '@/api/client'
import { i18n } from '@/i18n'
import App from '@/App.vue'
import router from '../index'
import { useAuthStore } from '@/stores/auth'

// The first navigation to each lazy-loaded view imports its module; on a loaded machine
// that can take longer than the default, and a timeout here would say nothing about the flow.
vi.setConfig({ testTimeout: 20_000 })
const WAIT = { timeout: 10_000 }

const MUW = { id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' }

/** What GET /me sends for an account with no study role: the sentinel role, no active study. */
function me(manageableLocations: unknown[]) {
  return {
    username: 'it.contact',
    displayName: 'IT Contact',
    email: null,
    role: 'Investigator',
    roles: ['Investigator'],
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: null,
    timezone: null,
    mustChangePassword: false,
    passwordChangeReason: null,
    activeStudy: null,
    userType: 'USER',
    manageableLocations,
  }
}

const UPLOADER = {
  id: 11, locationId: 1, locationCode: 'MUW-AUGEN', locationName: 'MUW Augenklinik', displayName: 'Clarus-PC',
  tokenHint: 'AbCdEfGh', status: 'active', createdBy: 'root', createdAt: '2026-10-12T08:00:00Z',
  lastUsedAt: null, revokedAt: null, instanceUid: null, lastHeartbeatAt: null,
}

let currentMe: ReturnType<typeof me>
let studies: unknown[] | Error

const mounted: VueWrapper[] = []

function mountApp(): VueWrapper {
  const w = mount(App, { global: { plugins: [router, i18n] }, attachTo: document.body })
  mounted.push(w)
  return w
}

const byId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

async function typeInto(id: string, value: string) {
  const el = document.getElementById(id) as HTMLInputElement
  el.value = value
  el.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

/** Signs in through the real login form; the network answers as the backend does. */
async function signIn() {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 204, json: async () => null }) as unknown as Response))
  await typeInto('login-username', 'it.contact')
  await typeInto('login-password', 'secret-Passw0rd')
  ;(document.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }))
  await flushPromises()
}

async function settle() {
  for (let i = 0; i < 5; i++) await flushPromises()
}

describe('a Standort-Admin who holds no study role', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'en'
    document.body.innerHTML = ''
    currentMe = me([MUW])
    studies = []
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiGet).mockImplementation(async (path: string) => {
      if (path === '/pages/api/v1/me') return structuredClone(currentMe)
      if (path === '/pages/api/v1/studies') {
        if (studies instanceof Error) throw studies
        return structuredClone(studies)
      }
      if (path === '/pages/api/v1/uploaders') return [UPLOADER]
      throw new Error(`unexpected GET ${path}`)
    })
    // The router is a singleton: start every test from a public page, signed out.
    useAuthStore().state = 'anonymous'
    await router.replace('/contact')
  })

  afterEach(() => {
    for (const w of mounted.splice(0)) w.unmount()
    vi.unstubAllGlobals()
    i18n.global.locale.value = 'de-AT'
  })

  it('lands on the Uploaders page right after login, instead of an empty study picker', async () => {
    await router.replace('/login')
    mountApp()
    await settle()
    await signIn()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('uploaders'), WAIT)
    await settle()

    expect(document.querySelector('h1')?.textContent?.trim()).toBe('Uploaders and tokens')
    expect(document.body.textContent).toContain('Clarus-PC')
    // The picker has been left behind for good, not stacked on the way back.
    expect(document.body.textContent).not.toContain(i18n.global.t('studyPicker.empty'))
  })

  it('has a top bar that offers the Uploaders page alone and shows no study', async () => {
    await router.replace('/login')
    mountApp()
    await settle()
    await signIn()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('uploaders'), WAIT)
    await settle()

    const links = Array.from(document.querySelectorAll('[data-testid^="topbar-nav-"]'))
    expect(links.map((a) => a.getAttribute('data-testid'))).toEqual(['topbar-nav-uploaders'])
    expect(byId('topbar-nav-uploaders')!.getAttribute('aria-current')).toBe('page')
    expect(byId('topbar-nav-uploaders')!.textContent?.trim()).toBe('Uploaders and tokens')
    // No study chip, no System entry, no rail: nothing that belongs to a study or to an administrator.
    expect(byId('topbar-study')).toBeNull()
    expect(byId('topbar-system-link')).toBeNull()
    expect(document.querySelector('[data-testid^="rail-"]')).toBeNull()
    // The user menu is there.
    expect(byId('topbar-profile-trigger')!.textContent).toContain('it.contact')
  })

  it('asks the server for nothing only an administrator may read', async () => {
    await router.replace('/login')
    mountApp()
    await settle()
    await signIn()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('uploaders'), WAIT)
    await settle()
    const paths = vi.mocked(apiGet).mock.calls.map((c) => c[0] as string)
    expect(paths).toEqual(expect.arrayContaining(['/pages/api/v1/me', '/pages/api/v1/studies', '/pages/api/v1/uploaders']))
    expect(paths.filter((p) => p.includes('/admin/'))).toEqual([])
  })

  it('also gets there from the home page and from the logo, which lead to the study picker for everyone', async () => {
    useAuthStore().user = me([MUW]) as never
    useAuthStore().state = 'authenticated'
    mountApp()
    await router.push('/')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('uploaders'), WAIT)
    await settle()
    expect(document.querySelector('h1')?.textContent?.trim()).toBe('Uploaders and tokens')

    // The logo is the home link: it goes through the picker and ends where it began.
    const pickerReads = () => vi.mocked(apiGet).mock.calls.filter((c) => c[0] === '/pages/api/v1/studies').length
    const before = pickerReads()
    ;(document.querySelector('header a[href="/"]') as HTMLAnchorElement).click()
    await settle()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('uploaders'), WAIT)
    expect(pickerReads()).toBe(before + 1)
    expect(document.querySelector('h1')?.textContent?.trim()).toBe('Uploaders and tokens')
  })

  it('opens the page directly from a bookmark, with no picker in between', async () => {
    useAuthStore().user = me([MUW]) as never
    useAuthStore().state = 'authenticated'
    mountApp()
    await router.push('/uploaders')
    await settle()
    expect(router.currentRoute.value.name).toBe('uploaders')
    expect(vi.mocked(apiGet).mock.calls.map((c) => c[0])).not.toContain('/pages/api/v1/studies')
    expect(document.body.textContent).toContain('Clarus-PC')
  })

  it('stays on the picker when the account holds no study role and has no such right, as before', async () => {
    currentMe = me([])
    await router.replace('/login')
    mountApp()
    await settle()
    await signIn()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('pick-study'), WAIT)
    await settle()
    expect(router.currentRoute.value.name).toBe('pick-study')
    expect(document.body.textContent).toContain(i18n.global.t('studyPicker.empty'))
    // And the Uploaders page stays shut: the picker is all there is.
    await router.push('/uploaders')
    await settle()
    expect(router.currentRoute.value.name).toBe('pick-study')
  })

  it('does not strand someone who has a study to pick: the picker lists it', async () => {
    studies = [{
      oid: 'S_OWN', name: 'Own Study', uniqueIdentifier: 'own', parentOid: null, parentName: null,
      role: 'Data Manager', isSite: false, isActive: false,
    }]
    await router.replace('/login')
    mountApp()
    await settle()
    await signIn()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('pick-study'), WAIT)
    await settle()
    expect(document.body.textContent).toContain('Own Study')
    expect(router.currentRoute.value.name).toBe('pick-study')
  })
})

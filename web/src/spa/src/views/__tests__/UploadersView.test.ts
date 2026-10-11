/**
 * UploadersView — the uploaders of a location and their tokens, for the
 * system administrator and for a location's Standort-Admin.
 *
 * Pins: the list shows the uploaders of the chosen location (a select only
 * when the session manages more than one) with the token hint, the status,
 * who created it and when it was last used and last reported; creating an
 * uploader shows its token once, in a dialog that closes only after the
 * person confirms having saved it, and lets it go for good; renaming,
 * replacing a token and revoking ask or confirm as they should, and the
 * server's 400, 404 and 409 are shown, not swallowed; a Standort-Admin who
 * is no system administrator gets the same page without the System rail and
 * without a call that only an administrator may make.
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
import { useNotificationsStore } from '@/stores/notifications'
import { useUploadersStore } from '@/stores/uploaders'
import UploadersView from '@/views/UploadersView.vue'
import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'
import type { AuthenticatedUser, ManageableLocation } from '@/types/auth'
import type { ManagedUploader } from '@/types/uploader'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
const LIST = '/pages/api/v1/uploaders'

const MUW: ManageableLocation = { id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' }
const GRAZ: ManageableLocation = { id: 2, code: 'GRAZ', name: 'Klinik Graz' }

const TOKEN = 'mwu_' + 'Qx7'.repeat(14) + 'Zk'
const TOKEN_2 = 'mwu_' + 'Mn4'.repeat(14) + 'Vb'

function up(over: Partial<ManagedUploader> & Pick<ManagedUploader, 'id' | 'displayName'>): ManagedUploader {
  return {
    locationId: 1,
    locationCode: 'MUW-AUGEN',
    locationName: 'MUW Augenklinik',
    tokenHint: 'AbCdEfGh',
    status: 'active',
    createdBy: 'root',
    createdAt: '2026-10-12T08:00:00Z',
    lastUsedAt: null,
    revokedAt: null,
    instanceUid: null,
    lastHeartbeatAt: null,
    ...over,
  }
}

/** In the server's order: by location name, then display name. */
const GRAZ_OCT = up({ id: 21, displayName: 'OCT Graz', locationId: 2, locationCode: 'GRAZ',
  locationName: 'Klinik Graz', tokenHint: 'GrAzOcT1', createdBy: 'maria' })
const CLARUS = up({ id: 11, displayName: 'Clarus-PC', lastUsedAt: '2026-10-12T09:30:00Z',
  instanceUid: 'inst-1', lastHeartbeatAt: '2026-10-12T09:31:00Z' })
const SPECTRALIS = up({ id: 12, displayName: 'Spectralis-PC', tokenHint: 'SpEcTrA2', status: 'revoked',
  revokedAt: '2026-10-12T07:00:00Z', createdBy: null })
const ALL = [GRAZ_OCT, CLARUS, SPECTRALIS]

const clone = <T,>(v: T): T => structuredClone(v)

function answerGets(over: { uploaders?: ManagedUploader[]; fail?: ApiError } = {}) {
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === LIST) {
      if (over.fail) throw over.fail
      return clone(over.uploaders ?? ALL)
    }
    throw new Error(`unexpected GET ${path}`)
  })
}

/** Sets the session the view reads: who is signed in and which locations they manage. */
function signIn(kind: 'sysadmin' | 'location-admin', manageable: ManageableLocation[]) {
  const auth = useAuthStore()
  auth.user = {
    username: kind === 'sysadmin' ? 'root' : 'maria',
    displayName: kind === 'sysadmin' ? 'Root' : 'Maria Muster',
    role: kind === 'sysadmin' ? 'Administrator' : 'Data Manager',
    userType: kind === 'sysadmin' ? 'SYSADMIN' : 'USER',
    profileComplete: true,
    mustChangePassword: false,
    manageableLocations: manageable,
    activeStudy: { id: 1, oid: 'S_X', name: 'Study', isSite: false, role: 'Data Manager', roles: ['Data Manager'] },
  } as unknown as AuthenticatedUser
  auth.state = 'authenticated'
}

/** Every wrapper mounted by a test, unmounted afterwards even when the test fails half-way. */
const mounted: VueWrapper[] = []

async function mountView(withI18n: typeof i18n = i18n): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/uploaders', component: UploadersView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/uploaders')
  await router.isReady()
  const w = mount(UploadersView, { global: { plugins: [router, withI18n] }, attachTo: document.body })
  mounted.push(w)
  await flushPromises()
  return w
}

/* ---- DOM helpers: the dialogs are teleported to <body>, so everything is read from there ---- */

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

async function check(testid: string) {
  const box = byId(testid) as HTMLInputElement
  box.checked = true
  box.dispatchEvent(new Event('change', { bubbles: true }))
  box.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

function rowIds(): string[] {
  return Array.from(document.body.querySelectorAll('[data-testid^="uploader-row-"]')).map(
    (tr) => tr.getAttribute('data-testid')!.slice('uploader-row-'.length),
  )
}

function toasts(): string[] {
  return useNotificationsStore().toasts.map((x) => x.message)
}

/** The clipboard jsdom does not have. */
function stubClipboard(writeText: (s: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
}

describe('UploadersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    appI18n.global.locale.value = 'en'
    document.body.innerHTML = ''
    for (const fn of [apiGet, apiPost, apiPut, apiDelete]) vi.mocked(fn).mockReset()
    confirmMock.mockReset()
    confirmMock.mockResolvedValue(true)
    answerGets()
    signIn('sysadmin', [MUW, GRAZ])
  })
  afterEach(() => {
    for (const w of mounted.splice(0)) w.unmount()
    appI18n.global.locale.value = 'de-AT'
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  describe('the list', () => {
    it('lists the uploaders of the first location, with what a person needs to tell them apart', async () => {
      await mountView()
      expect(vi.mocked(apiGet)).toHaveBeenCalledTimes(1)
      expect(vi.mocked(apiGet)).toHaveBeenCalledWith(LIST)
      // The first managed location is MUW; Graz's uploader is not on this page yet.
      expect(rowIds()).toEqual(['11', '12'])

      expect(text('uploader-name-11')).toBe('Clarus-PC')
      expect(text('uploader-token-11')).toBe('mwu_AbCdEfGh…')
      expect(text('uploader-status-11')).toContain('Active')
      expect(text('uploader-created-11')).toContain('by root')
      expect(byId('uploader-used-11')!.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-12T09:30:00Z')
      expect(byId('uploader-report-11')!.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-12T09:31:00Z')
    })

    it('says "never" for a token that was not used and a program that did not report', async () => {
      answerGets({ uploaders: [up({ id: 13, displayName: 'Neu' })] })
      await mountView()
      expect(text('uploader-used-13')).toBe('never')
      expect(text('uploader-report-13')).toBe('never')
      expect(byId('uploader-used-13')!.querySelector('time')).toBeNull()
    })

    it('shows a revoked uploader as revoked, without a revoke action, but with the way back', async () => {
      await mountView()
      expect(text('uploader-status-12')).toContain('Revoked')
      expect(byId('uploader-revoke-12')).toBeNull()
      expect(byId('uploader-replace-12')).not.toBeNull()
      expect(byId('uploader-rename-12')).not.toBeNull()
      // Nobody created it as far as the server knows: no "by".
      expect(text('uploader-created-12')).not.toContain('by ')
      expect(byId('uploader-revoke-11')).not.toBeNull()
    })

    it('names its columns, counts the uploaders and sits in the System section for an administrator', async () => {
      await mountView()
      const headers = Array.from(document.body.querySelectorAll('th')).map((th) => th.textContent?.trim())
      expect(headers).toEqual(expect.arrayContaining(
        ['Name', 'Token (start)', 'Status', 'Created', 'Last used', 'Last report'],
      ))
      expect(document.body.querySelector('h1')?.textContent?.trim()).toBe('Uploaders and tokens')
      expect(text('uploaders-subtitle')).toBe('Uploaders: 2 (1 active)')
      expect(byId('rail-uploaders')?.getAttribute('aria-current')).toBe('page')
    })

    it('is a flat page of its section: a header with no trail and no eyebrow (DR-030)', async () => {
      await mountView()
      expect(byId('page-header')).not.toBeNull()
      expect(byId('page-trail')).toBeNull()
      expect(byId('page-eyebrow')).toBeNull()
    })

    it('shows a loading row first, then the rows', async () => {
      let answer!: (v: ManagedUploader[]) => void
      vi.mocked(apiGet).mockImplementation(() => new Promise<ManagedUploader[]>((r) => { answer = r }))
      const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }] })
      await router.push('/')
      const w = mount(UploadersView, { global: { plugins: [router, i18n] }, attachTo: document.body })
      mounted.push(w)
      await nextTick()
      expect(document.body.textContent).toContain('Loading…')
      expect(rowIds()).toEqual([])
      answer(clone(ALL))
      await flushPromises()
      expect(document.body.textContent).not.toContain('Loading…')
      expect(rowIds()).toEqual(['11', '12'])
    })

    it('explains an empty location and still offers to create the first uploader', async () => {
      answerGets({ uploaders: [GRAZ_OCT] })
      await mountView()
      expect(rowIds()).toEqual([])
      expect(text('uploaders-empty')).toContain('This location has no uploader yet')
      expect(byId('uploaders-create')).not.toBeNull()
    })

    it('shows the server’s message when the list is refused, and offers no create button', async () => {
      answerGets({ fail: new ApiError(403, 'Forbidden', { message: 'You may not manage the uploaders of any location' }) })
      await mountView()
      expect(text('uploaders-error')).toContain('You may not manage the uploaders of any location')
      expect(byId('uploaders-create')).toBeNull()
      expect(byId('uploaders-empty')).toBeNull()
    })

    it('reloads on request, with the rows kept on screen meanwhile', async () => {
      await mountView()
      vi.mocked(apiGet).mockClear()
      answerGets({ uploaders: [{ ...CLARUS, lastHeartbeatAt: '2026-10-12T10:15:00Z' }, SPECTRALIS] })
      await click('uploaders-refresh')
      expect(vi.mocked(apiGet)).toHaveBeenCalledTimes(1)
      expect(byId('uploader-report-11')!.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-12T10:15:00Z')
    })

    it('tells a session that manages no location so, instead of showing an empty table', async () => {
      signIn('location-admin', [])
      await mountView()
      expect(text('uploaders-no-location')).toContain('No location')
      expect(byId('uploaders-create')).toBeNull()
    })
  })

  describe('the location select', () => {
    it('is there when the session manages several locations, starts on the first and switches without a request', async () => {
      await mountView()
      const select = field('uploaders-location') as unknown as HTMLSelectElement
      expect(select).not.toBeNull()
      expect(Array.from(select.options).map((o) => o.textContent?.trim())).toEqual([
        'MUW Augenklinik (MUW-AUGEN)',
        'Klinik Graz (GRAZ)',
      ])
      expect(select.value).toBe('1')

      select.value = '2'
      select.dispatchEvent(new Event('change', { bubbles: true }))
      await flushPromises()
      expect(rowIds()).toEqual(['21'])
      expect(text('uploader-name-21')).toBe('OCT Graz')
      expect(vi.mocked(apiGet)).toHaveBeenCalledTimes(1)
      expect(text('uploaders-subtitle')).toBe('Uploaders: 1 (1 active)')
    })

    it('has a visible label', async () => {
      await mountView()
      const label = document.body.querySelector('label[for="uploaders-location"]')
      expect(label?.textContent?.trim()).toBe('Location')
    })

    it('is left out for a session that manages one location, which is named instead', async () => {
      signIn('location-admin', [GRAZ])
      await mountView()
      expect(field('uploaders-location')).toBeNull()
      expect(text('uploaders-location-single')).toBe('Location: Klinik Graz')
      expect(rowIds()).toEqual(['21'])
    })

    it('creates at the location that is shown', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 22, displayName: 'Fundus', locationId: 2, locationCode: 'GRAZ', locationName: 'Klinik Graz' }), token: TOKEN })
      await mountView()
      const select = field('uploaders-location') as unknown as HTMLSelectElement
      select.value = '2'
      select.dispatchEvent(new Event('change', { bubbles: true }))
      await flushPromises()

      await click('uploaders-create')
      expect(text('uploader-dialog-location')).toBe('Location: Klinik Graz')
      await type('uploader-name', 'Fundus')
      await click('uploader-form-submit')
      expect(vi.mocked(apiPost)).toHaveBeenCalledWith('/pages/api/v1/locations/2/uploaders', { displayName: 'Fundus' })
      // In the list's order: by name within the location.
      expect(rowIds()).toEqual(['22', '21'])
    })
  })

  describe('creating an uploader and its token', () => {
    it('asks for a name before it posts: required mark, blocked submit, at most 64 characters', async () => {
      await mountView()
      await click('uploaders-create')
      expect(text('uploader-dialog-title')).toBe('Create uploader')
      expect(document.body.querySelector('label[for="uploader-name"]')?.classList.contains('req')).toBe(true)
      const submit = byId('uploader-form-submit') as HTMLButtonElement
      expect(submit.disabled).toBe(true)
      await type('uploader-name', '   ')
      expect(submit.disabled).toBe(true)
      await type('uploader-name', 'x'.repeat(65))
      expect(submit.disabled).toBe(true)
      expect(text('field-error-displayName')).toBe('At most 64 characters.')
      await type('uploader-name', 'x'.repeat(64))
      expect(submit.disabled).toBe(false)
      expect(byId('field-error-displayName')).toBeNull()
      expect(vi.mocked(apiPost)).not.toHaveBeenCalled()
    })

    it('posts the trimmed name, shows the token once, and confirms with a notice', async () => {
      const issued = up({ id: 13, displayName: 'Optomed-PC', tokenHint: 'Qx7Qx7Qx' })
      vi.mocked(apiPost).mockResolvedValue({ uploader: issued, token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', '  Optomed-PC  ')
      await click('uploader-form-submit')

      expect(vi.mocked(apiPost)).toHaveBeenCalledTimes(1)
      expect(vi.mocked(apiPost)).toHaveBeenCalledWith('/pages/api/v1/locations/1/uploaders', { displayName: 'Optomed-PC' })
      expect(toasts()).toContain('Uploader “Optomed-PC” created.')
      // The name dialog is gone; the token dialog is up.
      expect(byId('uploader-form-submit')).toBeNull()
      expect(text('uploader-token-title')).toBe('Token for “Optomed-PC”')
      expect(field('uploader-token-value')!.value).toBe(TOKEN)
      expect(field('uploader-token-value')!.readOnly).toBe(true)
      expect(field('uploader-token-value')!.classList.contains('font-mono')).toBe(true)
      // The row is in the list behind the dialog, in the list's order.
      expect(rowIds()).toEqual(['11', '13', '12'])
    })

    it('says that the token is not shown again, and where to enter it', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')

      expect(text('uploader-token-warning')).toContain('shown only now')
      expect(text('uploader-token-warning')).toContain('cannot be retrieved later')
      expect(text('uploader-token-where')).toContain('settings dialog of the uploader on the acquisition PC')
      expect(text('uploader-token-where')).toContain('not on the command line')
      expect(document.body.querySelector('label[for="uploader-token-value"]')?.textContent?.trim()).toBe('Token (shown once)')
    })

    it('can be closed only after the person confirms having saved the token', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')

      const close = byId('uploader-token-close') as HTMLButtonElement
      expect(close.disabled).toBe(true)
      expect(text('uploader-token-close-hint')).toContain('once you have saved the token')
      expect(close.getAttribute('aria-describedby')).toBe('uploader-token-close-hint')
      // Neither Escape nor the scrim, nor a header cross, can get round the confirmation.
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      await flushPromises()
      expect(field('uploader-token-value')).not.toBeNull()
      expect(document.body.querySelector('[role="dialog"] button[aria-label="Close"]')).toBeNull()

      await check('uploader-token-saved')
      expect(close.disabled).toBe(false)
      expect(byId('uploader-token-close-hint')).toBeNull()
      await click('uploader-token-close')
      expect(field('uploader-token-value')).toBeNull()
    })

    it('lets the token go for good when the dialog closes: not in the page, not in the store', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')
      expect(field('uploader-token-value')!.value).toBe(TOKEN)

      await check('uploader-token-saved')
      await click('uploader-token-close')
      expect(field('uploader-token-value')).toBeNull()
      expect(document.body.innerHTML).not.toContain(TOKEN)
      expect(document.body.textContent).not.toContain(TOKEN)
      expect(JSON.stringify(useUploadersStore().$state)).not.toContain(TOKEN)

      // A second token dialog starts unconfirmed.
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 14, displayName: 'Zweiter' }), token: TOKEN_2 })
      await click('uploaders-create')
      await type('uploader-name', 'Zweiter')
      await click('uploader-form-submit')
      expect((byId('uploader-token-saved') as HTMLInputElement).checked).toBe(false)
      expect((byId('uploader-token-close') as HTMLButtonElement).disabled).toBe(true)
      expect(field('uploader-token-value')!.value).toBe(TOKEN_2)
    })

    it('hands the focus back to the button that began the work', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')
      await check('uploader-token-saved')
      await click('uploader-token-close')
      await flushPromises()
      expect(document.activeElement).toBe(byId('uploaders-create'))
    })

    it('copies the token and says so with a notice', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      stubClipboard(writeText)
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')

      await click('uploader-token-copy')
      expect(writeText).toHaveBeenCalledTimes(1)
      expect(writeText).toHaveBeenCalledWith(TOKEN)
      expect(toasts()).toContain('Token copied to the clipboard.')
    })

    it('selects the token and says so when the clipboard is not available', async () => {
      stubClipboard(vi.fn().mockRejectedValue(new Error('denied')))
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')

      await click('uploader-token-copy')
      expect(toasts()).toContain('Copying did not work. The token is selected; copy it with Ctrl+C (Mac: Cmd+C).')
      expect(toasts()).not.toContain('Token copied to the clipboard.')
      expect(document.activeElement).toBe(field('uploader-token-value'))
    })

    it('shows the server’s refusal of the name under the field and keeps the dialog open', async () => {
      vi.mocked(apiPost).mockRejectedValue(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [{ field: 'displayName', message: 'An uploader with this name already exists at this location' }],
      }))
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Clarus-PC')
      await click('uploader-form-submit')

      expect(text('field-error-displayName')).toBe('An uploader with this name already exists at this location')
      expect(field('uploader-name')!.getAttribute('aria-invalid')).toBe('true')
      expect(byId('uploader-form-error')).toBeNull()
      expect(field('uploader-name')!.value).toBe('Clarus-PC')
      expect(toasts()).toEqual([])
      expect(field('uploader-token-value')).toBeNull()
      // The message belongs to what was typed: editing the field takes it away.
      await type('uploader-name', 'Clarus-PC 2')
      expect(byId('field-error-displayName')).toBeNull()
    })

    it.each([
      [409, 'Location is disabled'],
      [404, 'No location with id 1'],
    ])('shows the server’s %i as a message of the form, with no token', async (status, message) => {
      vi.mocked(apiPost).mockRejectedValue(new ApiError(status, 'Refused', { message }))
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')
      expect(text('uploader-form-error')).toBe(message)
      expect(byId('field-error-displayName')).toBeNull()
      expect(field('uploader-token-value')).toBeNull()
      expect(toasts()).toEqual([])
      expect(rowIds()).toEqual(['11', '12'])
    })

    it('leaves nothing behind when the name dialog is cancelled', async () => {
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Abgebrochen')
      await click('uploader-form-cancel')
      expect(byId('uploader-form-submit')).toBeNull()
      expect(vi.mocked(apiPost)).not.toHaveBeenCalled()
      await click('uploaders-create')
      expect(field('uploader-name')!.value).toBe('')
    })
  })

  describe('renaming an uploader', () => {
    it('opens with the current name, PUTs the new one, patches the row and confirms with a notice', async () => {
      vi.mocked(apiPut).mockResolvedValue({ ...CLARUS, displayName: 'Clarus-PC Raum 3' })
      await mountView()
      await click('uploader-rename-11')
      expect(text('uploader-dialog-title')).toBe('Rename uploader')
      expect(field('uploader-name')!.value).toBe('Clarus-PC')
      await type('uploader-name', ' Clarus-PC Raum 3 ')
      await click('uploader-form-submit')

      expect(vi.mocked(apiPut)).toHaveBeenCalledTimes(1)
      expect(vi.mocked(apiPut)).toHaveBeenCalledWith(`${LIST}/11`, { displayName: 'Clarus-PC Raum 3' })
      expect(text('uploader-name-11')).toBe('Clarus-PC Raum 3')
      expect(toasts()).toContain('Uploader “Clarus-PC Raum 3” renamed.')
      // A rename issues no token: there is nothing to show.
      expect(field('uploader-token-value')).toBeNull()
      expect(byId('uploader-form-submit')).toBeNull()
    })

    it('shows the server’s refusal under the field', async () => {
      vi.mocked(apiPut).mockRejectedValue(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [{ field: 'displayName', message: 'An uploader with this name already exists at this location' }],
      }))
      await mountView()
      await click('uploader-rename-11')
      await type('uploader-name', 'Spectralis-PC')
      await click('uploader-form-submit')
      expect(text('field-error-displayName')).toContain('already exists')
      expect(text('uploader-name-11')).toBe('Clarus-PC')
      expect(toasts()).toEqual([])
    })

    it('has a name for each button that tells the uploaders apart', async () => {
      await mountView()
      expect(byId('uploader-rename-11')!.getAttribute('aria-label')).toBe('Rename uploader Clarus-PC')
      expect(byId('uploader-replace-11')!.getAttribute('aria-label')).toBe('Replace the token of uploader Clarus-PC')
      expect(byId('uploader-revoke-11')!.getAttribute('aria-label')).toBe('Revoke uploader Clarus-PC')
    })
  })

  describe('replacing a token', () => {
    it('asks first, in danger style, and says the old token stops working at once', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: { ...CLARUS, tokenHint: 'NeWhInT1' }, token: TOKEN_2 })
      await mountView()
      await click('uploader-replace-11')
      expect(confirmMock).toHaveBeenCalledTimes(1)
      const asked = confirmMock.mock.calls[0][0]
      expect(asked.danger).toBe(true)
      expect(asked.title).toBe('Replace token')
      expect(asked.confirmLabel).toBe('Replace token')
      expect(asked.message).toContain('Replace the token of “Clarus-PC”?')
      expect(asked.message).toContain('stops working at once')
      expect(asked.message).toContain('token rejected')
    })

    it('POSTs to replace, shows the new token once and confirms with a notice', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: { ...CLARUS, tokenHint: 'NeWhInT1', lastUsedAt: null, lastHeartbeatAt: null }, token: TOKEN_2 })
      await mountView()
      await click('uploader-replace-11')

      expect(vi.mocked(apiPost)).toHaveBeenCalledTimes(1)
      expect(vi.mocked(apiPost)).toHaveBeenCalledWith(`${LIST}/11/replace`, {})
      expect(text('uploader-token-title')).toBe('New token for “Clarus-PC”')
      expect(field('uploader-token-value')!.value).toBe(TOKEN_2)
      expect(toasts()).toContain('Token of “Clarus-PC” replaced. The previous token is no longer valid.')
      // The row has the new hint and starts over on use and report.
      expect(text('uploader-token-11')).toBe('mwu_NeWhInT1…')
      expect(text('uploader-used-11')).toBe('never')

      await check('uploader-token-saved')
      await click('uploader-token-close')
      expect(field('uploader-token-value')).toBeNull()
      expect(document.body.innerHTML).not.toContain(TOKEN_2)
    })

    it('does nothing when the person says no', async () => {
      confirmMock.mockResolvedValue(false)
      await mountView()
      await click('uploader-replace-11')
      expect(vi.mocked(apiPost)).not.toHaveBeenCalled()
      expect(field('uploader-token-value')).toBeNull()
      expect(toasts()).toEqual([])
    })

    it('hands the cursor back to the button that asked when the person says no', async () => {
      confirmMock.mockResolvedValue(false)
      await mountView()
      await click('uploader-replace-11')
      expect(document.activeElement).toBe(byId('uploader-replace-11'))
    })

    it('tells the person that a new token lifts the revocation of a revoked uploader', async () => {
      vi.mocked(apiPost).mockResolvedValue({
        uploader: { ...SPECTRALIS, status: 'active', revokedAt: null, tokenHint: 'FreSh123' },
        token: TOKEN_2,
      })
      await mountView()
      await click('uploader-replace-12')
      expect(confirmMock.mock.calls[0][0].message).toContain('A new token lifts the revocation')
      expect(text('uploader-status-12')).toContain('Active')
      expect(byId('uploader-revoke-12')).not.toBeNull()
    })

    it.each([
      [404, 'No uploader with id 11'],
      [409, 'The location was removed'],
    ])('shows the server’s %i above the list, not a token', async (status, message) => {
      vi.mocked(apiPost).mockRejectedValue(new ApiError(status, 'Refused', { message }))
      await mountView()
      await click('uploader-replace-11')
      expect(text('uploaders-action-error')).toContain('The token of “Clarus-PC” could not be replaced.')
      expect(text('uploaders-action-error')).toContain(message)
      expect(field('uploader-token-value')).toBeNull()
      expect(toasts()).toEqual([])
      expect(text('uploader-token-11')).toBe('mwu_AbCdEfGh…')
      await click('uploaders-action-error-dismiss')
      expect(byId('uploaders-action-error')).toBeNull()
    })

    it('reloads the list after a 404, because the uploader may be gone from this person’s reach', async () => {
      vi.mocked(apiPost).mockRejectedValue(new ApiError(404, 'Not Found', { message: 'No uploader with id 11' }))
      await mountView()
      vi.mocked(apiGet).mockClear()
      answerGets({ uploaders: [SPECTRALIS] })
      await click('uploader-replace-11')
      expect(vi.mocked(apiGet)).toHaveBeenCalledTimes(1)
      expect(rowIds()).toEqual(['12'])
    })
  })

  describe('revoking an uploader', () => {
    it('asks first, in danger style, and says what the uploader does without a token', async () => {
      vi.mocked(apiPost).mockResolvedValue({ ...CLARUS, status: 'revoked', revokedAt: '2026-10-12T10:00:00Z' })
      await mountView()
      await click('uploader-revoke-11')
      const asked = confirmMock.mock.calls[0][0]
      expect(asked.danger).toBe(true)
      expect(asked.title).toBe('Revoke uploader')
      expect(asked.confirmLabel).toBe('Revoke')
      expect(asked.message).toContain('Revoke uploader “Clarus-PC”?')
      expect(asked.message).toContain('refused at once')
      expect(asked.message).toContain('leaves its files in the watch folder')
    })

    it('POSTs to revoke, shows the uploader as revoked and confirms with a notice', async () => {
      vi.mocked(apiPost).mockResolvedValue({ ...CLARUS, status: 'revoked', revokedAt: '2026-10-12T10:00:00Z' })
      await mountView()
      await click('uploader-revoke-11')
      expect(vi.mocked(apiPost)).toHaveBeenCalledTimes(1)
      expect(vi.mocked(apiPost)).toHaveBeenCalledWith(`${LIST}/11/revoke`, {})
      expect(text('uploader-status-11')).toContain('Revoked')
      expect(byId('uploader-revoke-11')).toBeNull()
      expect(toasts()).toContain('Uploader “Clarus-PC” revoked.')
      expect(text('uploaders-subtitle')).toBe('Uploaders: 2 (0 active)')
      // The pressed button is gone; the cursor goes on to the way back.
      expect(document.activeElement).toBe(byId('uploader-replace-11'))
    })

    it('does nothing when the person says no, and hands the cursor back to the button that asked', async () => {
      confirmMock.mockResolvedValue(false)
      await mountView()
      await click('uploader-revoke-11')
      expect(vi.mocked(apiPost)).not.toHaveBeenCalled()
      expect(text('uploader-status-11')).toContain('Active')
      expect(toasts()).toEqual([])
      expect(document.activeElement).toBe(byId('uploader-revoke-11'))
    })

    it('shows the server’s refusal above the list and keeps the uploader active', async () => {
      vi.mocked(apiPost).mockRejectedValue(new ApiError(404, 'Not Found', { message: 'No uploader with id 11' }))
      await mountView()
      await click('uploader-revoke-11')
      expect(text('uploaders-action-error')).toContain('Uploader “Clarus-PC” could not be revoked.')
      expect(text('uploaders-action-error')).toContain('No uploader with id 11')
      expect(toasts()).toEqual([])
      expect(document.activeElement).toBe(byId('uploader-revoke-11'))
    })
  })

  describe('as a Standort-Admin who is no system administrator', () => {
    beforeEach(() => {
      signIn('location-admin', [GRAZ])
      answerGets({ uploaders: [GRAZ_OCT] })
    })

    it('shows the page without the System rail and asks for nothing only an administrator may read', async () => {
      await mountView()
      expect(document.body.querySelector('[data-testid^="rail-"]')).toBeNull()
      expect(document.body.querySelector('aside')).toBeNull()
      expect(rowIds()).toEqual(['21'])
      expect(text('uploader-name-21')).toBe('OCT Graz')
      // Every request the page made is one a Standort-Admin may make.
      const paths = vi.mocked(apiGet).mock.calls.map((c) => c[0])
      expect(paths).toEqual([LIST])
      expect(paths.some((p) => p.includes('/admin/'))).toBe(false)
    })

    it('can create, rename, replace and revoke at its own location', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 22, displayName: 'Fundus', locationId: 2, locationCode: 'GRAZ', locationName: 'Klinik Graz' }), token: TOKEN })
      await mountView()
      expect(byId('uploaders-create')).not.toBeNull()
      expect(byId('uploader-rename-21')).not.toBeNull()
      expect(byId('uploader-replace-21')).not.toBeNull()
      expect(byId('uploader-revoke-21')).not.toBeNull()

      await click('uploaders-create')
      await type('uploader-name', 'Fundus')
      await click('uploader-form-submit')
      expect(vi.mocked(apiPost)).toHaveBeenCalledWith('/pages/api/v1/locations/2/uploaders', { displayName: 'Fundus' })
      expect(field('uploader-token-value')!.value).toBe(TOKEN)
    })

    it('is centred like the other pages without a rail', async () => {
      await mountView()
      expect(byId('page-header')!.parentElement!.classList.contains('mx-auto')).toBe(true)
    })
  })

  describe('in German, the working language', () => {
    it('speaks German on the list, in the dialogs and in the confirmations, with no key left untranslated', async () => {
      const german = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de: deMessages } })
      appI18n.global.locale.value = 'de-AT'
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView(german)
      const page = document.body.textContent!
      expect(page).toContain('Uploader und Tokens')
      expect(page).toContain('Uploader anlegen')
      expect(page).toContain('Letzte Meldung')
      expect(text('uploader-status-11')).toContain('Aktiv')
      expect(text('uploader-status-12')).toContain('Gesperrt')
      expect(text('uploader-created-11')).toContain('von root')

      await click('uploader-replace-11')
      expect(confirmMock.mock.calls[0][0].message).toContain('funktioniert danach sofort nicht mehr')

      await click('uploaders-create')
      expect(text('uploader-dialog-title')).toBe('Uploader anlegen')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')
      expect(text('uploader-token-title')).toBe('Token für „Optomed-PC“')
      expect(text('uploader-token-warning')).toContain('nur jetzt angezeigt')
      expect(document.body.textContent).toContain('Ich habe den Token gesichert')
      // vue-i18n prints the key itself for a message it cannot find.
      expect(document.body.textContent).not.toMatch(/\b(uploaders|adminLocations|system|common)\.[a-zA-Z]+/)
    })
  })

  describe('accessibility', () => {
    const RULES = {
      runOnly: { type: 'tag' as const, values: ['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'] },
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    }

    it('has no structural violations with the list on screen', async () => {
      await mountView()
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })

    it('has none with the name dialog open', async () => {
      await mountView()
      await click('uploaders-create')
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })

    it('has none with the token dialog open', async () => {
      vi.mocked(apiPost).mockResolvedValue({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      await mountView()
      await click('uploaders-create')
      await type('uploader-name', 'Optomed-PC')
      await click('uploader-form-submit')
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })

    it('has none for a Standort-Admin without the rail', async () => {
      signIn('location-admin', [GRAZ])
      answerGets({ uploaders: [GRAZ_OCT] })
      await mountView()
      expect(await axe(document.body, RULES)).toHaveNoViolations()
    })
  })
})

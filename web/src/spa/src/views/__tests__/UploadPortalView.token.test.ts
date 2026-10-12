/**
 * The public upload page and the uploader token.
 *
 * Pinned: the page takes the token from the link's fragment and empties the
 * address bar; when the server asks for a token the page says so in plain
 * German, once, at the top, and the files the person dropped are not failed
 * or lost but wait; the staff page, which has a login, never says it; and a
 * page without any token, on a server that serves anonymously, is the page
 * it always was.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createWebHistory } from 'vue-router'
import { axe } from 'vitest-axe'
import '@/test/a11y/setup'
import { stubWebStorage, type MemoryStorage } from '@/test/memoryStorage'

import deMessages from '@/locales/de.json'
import enMessages from '@/locales/en.json'

const api = vi.hoisted(() => ({
  listVisitsForDay: vi.fn(),
  resolveRows: vi.fn(),
  preflight: vi.fn(),
  commitFile: vi.fn(),
  undoItem: vi.fn(),
  undoJob: vi.fn(),
  sha256OfFile: vi.fn(),
  searchPatientsPublic: vi.fn(),
  listPatientEventsPublic: vi.fn(),
}))
vi.mock('@/api/uploadWorkbench', () => ({
  listVisitsForDay: (...a: unknown[]) => api.listVisitsForDay(...a),
  resolveRows: (...a: unknown[]) => api.resolveRows(...a),
  preflight: (...a: unknown[]) => api.preflight(...a),
  commitFile: (...a: unknown[]) => api.commitFile(...a),
  undoItem: (...a: unknown[]) => api.undoItem(...a),
  undoJob: (...a: unknown[]) => api.undoJob(...a),
  sha256OfFile: (...a: unknown[]) => api.sha256OfFile(...a),
  searchPatientsPublic: (...a: unknown[]) => api.searchPatientsPublic(...a),
  listPatientEventsPublic: (...a: unknown[]) => api.listPatientEventsPublic(...a),
  localIsoToday: () => '2026-10-12',
}))
vi.mock('@/lib/dicomHeader', () => ({
  readDicomHints: vi.fn().mockResolvedValue({ laterality: 'OS', acquisitionDate: '2026-10-12', modality: 'OP', modelName: 'CLARUS 700', deviceKey: 'clarus', sopInstanceUid: '1' }),
}))

import UploadPortalView from '@/views/UploadPortalView.vue'
import UploadWorkbench from '@/components/upload/UploadWorkbench.vue'
import UploadDropzone from '@/components/upload/UploadDropzone.vue'
import { useUploadWorkbenchStore } from '@/stores/uploadWorkbench'
import {
  UploadTokenError,
  clearUploaderToken,
  getUploaderToken,
  reportTokenProblem,
  setUploaderToken,
} from '@/lib/uploaderToken'

const BODY = 'abcXYZ019-_'.repeat(5).slice(0, 43)
const TOKEN = `mwu_${BODY}`

const de = createI18n({ legacy: false, locale: 'de', fallbackLocale: 'de', messages: { de: deMessages } })
const en = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])

const mounted: VueWrapper[] = []

function mountPage(i18n = de) {
  const w = mount(UploadPortalView, { global: { plugins: [i18n] }, attachTo: document.body })
  mounted.push(w)
  return w
}

async function settle(times = 8): Promise<void> {
  for (let i = 0; i < times; i++) await flushPromises()
}

const banner = () => document.body.querySelector('[data-testid="token-banner"]')

let local: MemoryStorage

beforeEach(() => {
  setActivePinia(createPinia())
  ;({ local } = stubWebStorage())
  window.history.replaceState(null, '', '/upload')
  clearUploaderToken()
  reportTokenProblem(null)
  for (const m of Object.values(api)) m.mockReset()
  api.listVisitsForDay.mockResolvedValue(null)
  api.resolveRows.mockResolvedValue({ scans: [] })
  api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
  api.sha256OfFile.mockImplementation(async (f: File) => `h-${f.name}`.padEnd(64, '0'))
  api.commitFile.mockResolvedValue({ ingestItemId: 12, jobId: null, kind: 'image', format: 'png', status: 'UNBOUND' })
})

afterEach(() => {
  for (const w of mounted.splice(0)) w.unmount()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('a page without a token, on a server that serves anonymously', () => {
  it('is the page it always was: no banner, and the same dropzone and visit panel', async () => {
    mountPage()
    await settle()
    expect(banner()).toBeNull()
    expect(document.body.querySelector('[data-testid="upload-dropzone-hero"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="batch-visit"]')).not.toBeNull()
    expect(getUploaderToken()).toBeNull()
  })

  it('takes a dropped photo as it always did', async () => {
    const w = mountPage()
    await settle()
    w.findComponent(UploadDropzone).vm.$emit('files-added', [new File([PNG], 'fundus.png', { type: 'image/png' })])
    await settle(12)
    const row = document.body.querySelector('[data-row-kind="image"]')
    expect(row?.getAttribute('data-row-state')).toBe('nopatient')
    expect(document.body.querySelector('[data-testid="row-waiting-token"]')).toBeNull()
  })
})

describe('a link with a token', () => {
  it('is taken from the fragment, kept for the session, and the address bar is emptied', async () => {
    window.history.replaceState(null, '', `/upload#token=${TOKEN}`)
    const router = createRouter({
      history: createWebHistory('/'),
      routes: [{ path: '/upload', name: 'upload-portal', component: UploadPortalView }],
    })
    // The page mounts under a router view, that is, after the router has resolved it.
    await router.push(window.location.pathname + window.location.hash)
    await router.isReady()
    const w = mount(UploadPortalView, { global: { plugins: [router, de] }, attachTo: document.body })
    mounted.push(w)
    await settle()

    expect(getUploaderToken()).toBe(TOKEN)
    expect(window.location.hash).toBe('')
    expect(window.location.href).not.toContain(BODY)
    expect(JSON.stringify(window.history.state)).not.toContain(BODY)
    expect(local.length).toBe(0)
    expect(banner()).toBeNull()
  })

  it('that is no token says so, and the page does not pretend otherwise', async () => {
    window.history.replaceState(null, '', '/upload#token=mwu_short')
    mountPage()
    await settle()
    expect(getUploaderToken()).toBeNull()
    expect(banner()?.textContent).toContain('Der Token im Link ist unvollständig oder ungültig.')
    expect(window.location.hash).toBe('')
  })

  it('that arrives later settles the complaint', async () => {
    mountPage()
    await settle()
    reportTokenProblem('rejected')
    await settle()
    expect(banner()).not.toBeNull()
    window.location.hash = `#token=${TOKEN}`
    await vi.waitFor(() => expect(getUploaderToken()).toBe(TOKEN))
    await settle()
    expect(banner()).toBeNull()
  })
})

describe('when the server wants a token', () => {
  it('says in German that the page needs one, and where it comes from', async () => {
    mountPage()
    await settle()
    reportTokenProblem('required')
    await settle()
    const el = banner()!
    expect(el.getAttribute('role')).toBe('alert')
    expect(el.getAttribute('data-problem')).toBe('required')
    expect(el.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Diese Seite braucht einen Uploader-Token. Bitte den Link verwenden, den Ihr Standort-Admin Ihnen gegeben hat.',
    )
  })

  it('says in German that the token was refused, and what to do', async () => {
    mountPage()
    await settle()
    reportTokenProblem('rejected')
    await settle()
    expect(banner()!.getAttribute('data-problem')).toBe('rejected')
    expect(banner()!.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Der Token wurde abgelehnt oder gesperrt. Bitte beim Standort-Admin einen neuen Link anfordern.',
    )
  })

  it('says it in English where the page is English', async () => {
    mountPage(en)
    await settle()
    reportTokenProblem('required')
    await settle()
    expect(banner()!.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'This page needs an uploader token. Please use the link your location admin gave you.',
    )
  })

  it('shows only the one complaint at a time, and takes it away when it is settled', async () => {
    mountPage()
    await settle()
    reportTokenProblem('required')
    reportTokenProblem('rejected')
    await settle()
    expect(document.body.querySelectorAll('[data-testid="token-banner"]')).toHaveLength(1)
    reportTokenProblem(null)
    await settle()
    expect(banner()).toBeNull()
  })

  it('keeps a dropped file as a row that waits, not as a failure', async () => {
    api.resolveRows.mockRejectedValue(new UploadTokenError('required', { message: 'uploader token required' }))
    const w = mountPage()
    await settle()
    const store = useUploadWorkbenchStore()
    w.findComponent(UploadDropzone).vm.$emit('files-added', [new File([PNG], 'fundus.png', { type: 'image/png' })])
    await settle(12)
    store.setPatientIdHint('HAE-001')
    await store.applyPatientIdHint()
    await settle()

    const row = document.body.querySelector('[data-row-kind="image"]')!
    expect(row.getAttribute('data-row-state')).toBe('parsing')
    expect(row.querySelector('[data-testid="row-waiting-token"]')?.textContent).toContain('Wartet auf einen gültigen Upload-Link')
    expect(row.querySelector('[data-testid="row-error"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="upload-summary"]')?.textContent).not.toContain('abgelehnt')
  })

  it('has no structural accessibility violations in the banner', async () => {
    mountPage()
    await settle()
    reportTokenProblem('rejected')
    await settle()
    const rules = {
      runOnly: { type: 'tag' as const, values: ['wcag2a', 'wcag2aa', 'wcag22aa', 'best-practice'] },
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    }
    expect(await axe(banner() as Element, rules)).toHaveNoViolations()
  })
})

describe('the staff page, which has a login', () => {
  it('never shows the banner, whatever has been reported', async () => {
    setUploaderToken(TOKEN)
    reportTokenProblem('required')
    const w = mount(UploadWorkbench, { props: { mode: 'staff' }, global: { plugins: [de] }, attachTo: document.body })
    mounted.push(w)
    await settle()
    expect(banner()).toBeNull()
  })
})

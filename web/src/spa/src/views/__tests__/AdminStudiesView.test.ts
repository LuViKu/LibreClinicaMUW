/**
 * AdminStudiesView — the system administrator's list of every study.
 *
 * Pins: sites are listed under their study; removed studies are listed
 * with their status and offer Restore instead of Remove; removal shows
 * what it will take (the server's count) and needs a reason before it
 * posts, and only once that count has loaded; sites are not removed here;
 * every row offers the ODM metadata download; a refusal from the server
 * (of the list or of a removal) is shown, not swallowed.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})
vi.mock('@/api/download', () => ({ apiDownload: vi.fn() }))

import { apiGet, apiPost, ApiError } from '@/api/client'
import { apiDownload } from '@/api/download'
import AdminStudiesView from '@/views/AdminStudiesView.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const STUDIES = [
  { oid: 'S_ALPHA', name: 'Alpha Study', uniqueIdentifier: 'alpha', principalInvestigator: 'Dr. A',
    createdDate: '2026-03-04', status: 'AVAILABLE', parentOid: null, sites: [
      { oid: 'S_ALPHA_1', name: 'Alpha Graz', uniqueIdentifier: 'alpha-graz', principalInvestigator: 'Dr. G',
        createdDate: '2026-03-05', status: 'AVAILABLE', parentOid: 'S_ALPHA', sites: [] },
    ] },
  { oid: 'S_BETA', name: 'Beta Study', uniqueIdentifier: 'beta', principalInvestigator: 'Dr. B',
    createdDate: '2025-11-30', status: 'REMOVED', parentOid: null, sites: [
      { oid: 'S_BETA_1', name: 'Beta Linz', uniqueIdentifier: 'beta-linz', principalInvestigator: 'Dr. L',
        createdDate: '2025-12-01', status: 'AUTO_REMOVED', parentOid: 'S_BETA', sites: [] },
    ] },
]

const PREVIEW = {
  oid: 'S_ALPHA', name: 'Alpha Study', siteNames: ['Alpha Graz'], roleBindings: 4, subjects: 12,
  groupClasses: 0, eventDefinitions: 3, events: 20, eventCrfs: 35, itemData: 410, datasets: 1,
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/studies', component: AdminStudiesView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/studies')
  await router.isReady()
  const w = mount(AdminStudiesView, { global: { plugins: [router, i18n] }, attachTo: document.body })
  await flushPromises()
  return w
}

function rowTexts(): string[] {
  return Array.from(document.body.querySelectorAll('[data-testid^="admin-study-"]')).map(
    (tr) => tr.getAttribute('data-testid')!.slice('admin-study-'.length),
  )
}

function button(text: string): HTMLButtonElement | undefined {
  return (Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]).find(
    (b) => b.textContent?.trim() === text,
  )
}

describe('AdminStudiesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiDownload).mockReset()
    vi.mocked(apiGet).mockImplementation(async (path: string) => {
      if (path === '/pages/api/v1/admin/studies') return STUDIES
      if (path === '/pages/api/v1/studies/S_ALPHA/removal-preview') return PREVIEW
      throw new Error(`unexpected GET ${path}`)
    })
  })

  it('lists every study with its sites under it, removed ones included', async () => {
    const w = await mountView()
    expect(rowTexts()).toEqual(['S_ALPHA', 'S_ALPHA_1', 'S_BETA', 'S_BETA_1'])
    const alpha = document.body.querySelector('[data-testid="admin-study-S_ALPHA"]')!.textContent!
    expect(alpha).toContain('alpha')
    expect(alpha).toContain('Dr. A')
    expect(alpha).toContain('04.03.2026')
    expect(document.body.querySelector('[data-testid="status-S_BETA"]')!.textContent).toContain('Removed')
    expect(document.body.querySelector('[data-testid="status-S_BETA_1"]')!.textContent).toContain(
      'Removed with its study',
    )
    // Only top-level studies are removed or restored here.
    expect(document.body.querySelector('[data-testid="remove-S_ALPHA"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="restore-S_BETA"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="remove-S_ALPHA_1"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="restore-S_BETA_1"]')).toBeNull()
    w.unmount()
  })

  it('names what a removal takes and removes only once a reason is given', async () => {
    vi.mocked(apiPost).mockResolvedValue({})
    const w = await mountView()
    ;(document.body.querySelector('[data-testid="remove-S_ALPHA"]') as HTMLButtonElement).click()
    await flushPromises()

    const impact = document.body.querySelector('[data-testid="study-removal-impact"]')!.textContent!
    expect(impact).toContain('Sites: 1')
    expect(impact).toContain('Alpha Graz')
    expect(impact).toContain('Subjects: 12')
    expect(impact).toContain('Entered values: 410')
    // Zero counts are not listed.
    expect(impact).not.toContain('Subject group classes')

    const confirm = document.body.querySelector('[data-testid="study-lifecycle-confirm"]') as HTMLButtonElement
    confirm.click()
    await flushPromises()
    expect(document.body.textContent).toContain('Enter a reason')
    expect(apiPost).not.toHaveBeenCalled()

    const reason = document.body.querySelector('#study-lifecycle-reason') as HTMLTextAreaElement
    reason.value = 'Study terminated by the sponsor'
    reason.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    confirm.click()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/studies/S_ALPHA/disable', {
      reason: 'Study terminated by the sponsor',
    })
    // The list is reloaded afterwards.
    expect(vi.mocked(apiGet).mock.calls.filter(([p]) => p === '/pages/api/v1/admin/studies')).toHaveLength(2)
    w.unmount()
  })

  it('does not remove a study whose removal count failed to load', async () => {
    vi.mocked(apiGet).mockImplementation(async (path: string) => {
      if (path === '/pages/api/v1/admin/studies') return STUDIES
      throw new ApiError(500, 'Server Error', { message: 'preview failed' })
    })
    vi.mocked(apiPost).mockResolvedValue({})
    const w = await mountView()
    ;(document.body.querySelector('[data-testid="remove-S_ALPHA"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(document.body.querySelector('[data-testid="study-removal-impact"]')).toBeNull()

    const reason = document.body.querySelector('#study-lifecycle-reason') as HTMLTextAreaElement
    reason.value = 'Study terminated by the sponsor'
    reason.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    const confirm = document.body.querySelector('[data-testid="study-lifecycle-confirm"]') as HTMLButtonElement
    expect(confirm.disabled).toBe(true)
    confirm.click()
    await flushPromises()
    expect(apiPost).not.toHaveBeenCalled()
    w.unmount()
  })

  it('shows the server’s refusal of a removal and keeps the dialog open', async () => {
    vi.mocked(apiPost).mockRejectedValue(
      new ApiError(409, 'Conflict', { message: 'Study was removed by another request; nothing was changed.' }),
    )
    const w = await mountView()
    ;(document.body.querySelector('[data-testid="remove-S_ALPHA"]') as HTMLButtonElement).click()
    await flushPromises()
    const reason = document.body.querySelector('#study-lifecycle-reason') as HTMLTextAreaElement
    reason.value = 'Study terminated by the sponsor'
    reason.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    ;(document.body.querySelector('[data-testid="study-lifecycle-confirm"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('Study was removed by another request; nothing was changed.')
    // Still open, and the list was not reloaded as after a success.
    expect(document.body.querySelector('[data-testid="study-lifecycle-confirm"]')).not.toBeNull()
    expect(vi.mocked(apiGet).mock.calls.filter(([p]) => p === '/pages/api/v1/admin/studies')).toHaveLength(1)
    w.unmount()
  })

  it('restores a removed study with a reason', async () => {
    vi.mocked(apiPost).mockResolvedValue({})
    const w = await mountView()
    ;(document.body.querySelector('[data-testid="restore-S_BETA"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(document.body.textContent).toContain('returns to the status it had when it was removed')

    const reason = document.body.querySelector('#study-lifecycle-reason') as HTMLTextAreaElement
    reason.value = 'Removed by mistake'
    reason.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    ;(document.body.querySelector('[data-testid="study-lifecycle-confirm"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/studies/S_BETA/restore', { reason: 'Removed by mistake' })
    w.unmount()
  })

  it('downloads the ODM metadata of a study or a site', async () => {
    const w = await mountView()
    ;(document.body.querySelector('[data-testid="metadata-S_ALPHA_1"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(apiDownload).toHaveBeenCalledWith(
      '/pages/api/v1/studies/S_ALPHA_1/metadata',
      'S_ALPHA_1_metadata.xml',
    )
    w.unmount()
  })

  it('shows the server’s refusal to anyone who is not a system administrator', async () => {
    vi.mocked(apiGet).mockRejectedValue(
      new ApiError(403, 'Forbidden', { message: 'Only a system administrator may list every study' }),
    )
    const w = await mountView()
    expect(document.body.querySelector('[role="alert"]')!.textContent).toContain(
      'Only a system administrator may list every study',
    )
    expect(rowTexts()).toEqual([])
    expect(button('Remove')).toBeUndefined()
    w.unmount()
  })
})

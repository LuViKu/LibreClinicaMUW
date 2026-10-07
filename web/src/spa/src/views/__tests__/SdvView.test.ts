/**
 * The SDV page as the Monitor uses it.
 *
 *  - A row carries no "Add query": it saved nothing (it only raised the row's
 *    counter in the page), and a query belongs on an item, which "Open CRF"
 *    reaches.
 *  - "Export" downloads the table as CSV; it had no handler.
 *  - "Open CRF" opens the CRF read-only for a Monitor; it went to data entry,
 *    which bounced the Monitor home.
 *  - The confirmation names the signed-in user, not `monitor_demo`.
 *  - The view by subject sums the CRFs per subject and verifies a subject,
 *    as legacy's "View By Study Subject ID" does.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  ApiError: class ApiError extends Error {},
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

// eslint-disable-next-line import/first
import { apiGet, apiPost } from '@/api/client'
// eslint-disable-next-line import/first
import SdvView from '@/views/SdvView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import type { SdvRow } from '@/types/sdv'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

function row(oid: string, subjectId: string, status: SdvRow['status'],
             requirement: SdvRow['requirement'] = 'required-100'): SdvRow {
  return {
    eventCrfOid: oid, subjectId, siteLabel: 'Default Study', eventLabel: 'V1 Inclusion',
    eventStartDate: '2020-10-06', crfName: 'Demographics / v1.0', crfLanguage: 'en',
    status, requirement, openQueries: status === 'query' ? 1 : 0,
    lastUpdatedAt: '2020-10-06T15:42:08Z',
  }
}

const ROWS: SdvRow[] = [
  row('1', 'M-001', 'query'),
  row('4', 'M-002', 'pending'),
  row('10', 'M-005', 'pending'),
  row('11', 'M-005', 'pending', 'required-partial'),
  row('6', 'M-003', 'verified'),
]

/** jsdom's Blob has no text(); FileReader reads it. */
function readText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

async function mountAs(username: string, role: 'Monitor' | 'Administrator' = 'Monitor') {
  setActivePinia(createPinia())
  useAuthStore().user = {
    username,
    role,
    activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false, role, roles: [role] },
  } as unknown as ReturnType<typeof useAuthStore>['user']
  vi.mocked(apiGet).mockResolvedValue(ROWS.map((r) => ({ ...r })))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/event-crfs/:eventCrfOid', name: 'crf-entry', component: { template: '<div />' } },
      { path: '/event-crfs/:eventCrfOid/readonly', name: 'crf-readonly', component: { template: '<div />' } },
    ],
  })
  const wrapper = mount(SdvView, { global: { plugins: [router, i18n] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('SdvView', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    document.body.innerHTML = ''
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('offers no "Add query" on a row', async () => {
    const w = await mountAs('manual_monitor')
    expect(w.findAll('tbody tr').length).toBeGreaterThan(0)
    expect(w.text()).not.toContain('Add query')
  })

  it('opens the CRF read-only for a Monitor, and for entry for an Administrator', async () => {
    const openCrf = (w: Awaited<ReturnType<typeof mountAs>>) =>
      w.findAll('a').find((a) => a.text() === 'Open CRF')!.attributes('href')
    const monitor = await mountAs('manual_monitor')
    expect(openCrf(monitor)).toBe('/event-crfs/1/readonly')
    monitor.unmount()
    const admin = await mountAs('root', 'Administrator')
    expect(openCrf(admin)).toBe('/event-crfs/1')
  })

  it('exports the filtered CRFs as CSV', async () => {
    const blobs: Blob[] = []
    // @ts-expect-error jsdom has no createObjectURL
    globalThis.URL.createObjectURL = vi.fn((b: Blob) => { blobs.push(b); return 'blob:sdv' })
    // @ts-expect-error jsdom has no revokeObjectURL
    globalThis.URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const w = await mountAs('manual_monitor')
    await w.get('#sdv-search').setValue('M-005')

    await w.findAll('button').find((b) => b.text() === 'Export')!.trigger('click')

    expect(click).toHaveBeenCalledTimes(1)
    expect(blobs).toHaveLength(1)
    const lines = (await readText(blobs[0])).replace(/^﻿/, '').split('\r\n')
    expect(lines[0]).toBe('Subject,Site,Event,Event date,CRF,Requirement,Status,Open queries,Last updated')
    expect(lines.slice(1)).toEqual([
      'M-005,Default Study,V1 Inclusion,2020-10-06,Demographics / v1.0,100% required,Pending,0,2020-10-06T15:42:08Z',
      'M-005,Default Study,V1 Inclusion,2020-10-06,Demographics / v1.0,Partial required,Pending,0,2020-10-06T15:42:08Z',
    ])
  })

  it('names the signed-in user in the verification confirmation', async () => {
    const w = await mountAs('manual_monitor')
    await w.get('input[aria-label="Select M-002 · Demographics / v1.0"]').setValue(true)
    await w.findAll('button').find((b) => b.text().startsWith('Mark 1 as verified'))!.trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('Mark as verified?')
    expect(document.body.textContent).not.toContain('monitor_demo')
    expect(document.body.querySelector('[data-testid="sdv-confirm-audit-note"]')?.textContent)
      .toContain('manual_monitor')
  })

  it('verifies a subject from the view by subject: its complete CRFs that need SDV', async () => {
    const w = await mountAs('manual_monitor')
    await w.get('[data-testid="sdv-view-subject"]').trigger('click')

    const m005 = w.get('[data-testid="sdv-subject-M-005"]')
    expect(m005.text()).toContain('Verify 2 CRF(s)')
    expect(w.get('[data-testid="sdv-subject-M-003"]').text()).toContain('All verified')
    expect(w.get('[data-testid="sdv-subject-M-001"]').text()).toContain('Nothing to verify')

    vi.mocked(apiPost).mockResolvedValueOnce({
      verified: ['10', '11'], rejected: [], verifiedCount: 2,
      verifiedAt: '2026-09-30T10:00:00Z', verifiedBy: 'manual_monitor',
    })
    await w.get('[data-testid="sdv-verify-subject-M-005"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="sdv-subject-confirm-body"]')?.textContent)
      .toContain('2 CRF(s) of M-005')
    ;(document.body.querySelector('[data-testid="sdv-subject-confirm"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/sdv/verify', { eventCrfOids: ['10', '11'], verified: true })
    expect(w.get('[data-testid="sdv-subject-M-005"]').text()).toContain('All verified')
  })
})

/**
 * The study audit log pages through the server's whole trail: it shows the
 * page's rows and the total, pages forward and back, and a changed filter
 * reloads from the first page with the filter sent to the server.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})
vi.mock('@/api/download', () => ({ apiDownload: vi.fn().mockResolvedValue(undefined) }))

import { apiGet } from '@/api/client'
import StudyAuditLogView from '@/views/StudyAuditLogView.vue'
import enMessages from '@/locales/en.json'
import type { AuditEvent } from '@/types/audit'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

function events(page: number): AuditEvent[] {
  return Array.from({ length: 2 }, (_, i) => ({
    id: `${page}-${i}`,
    occurredAt: '2026-05-30T10:00:00Z',
    variant: 'data',
    actor: 'root',
    title: `Row ${page}-${i}`,
  }))
}

/** The server: 250 rows in all, pages of 100; facets name two subjects. */
function serve() {
  vi.mocked(apiGet).mockImplementation(async (url: string) => {
    if (url.endsWith('/facets')) return { actors: ['root', 'system'], subjects: ['M-001', 'M-004'] } as never
    const page = Number(new URLSearchParams(url.split('?')[1]).get('page'))
    return { totalCount: 250, page, pageSize: 100, events: events(page) } as never
  })
}

function pageRequests(): URLSearchParams[] {
  return vi.mocked(apiGet).mock.calls
    .map((c) => c[0] as string)
    .filter((u) => u.startsWith('/pages/api/v1/audit?'))
    .map((u) => new URLSearchParams(u.split('?')[1]))
}

async function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(StudyAuditLogView, { global: { plugins: [pinia, i18n] } })
  await flushPromises()
  return wrapper
}

describe('StudyAuditLogView paging', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    serve()
  })

  it('shows the page and the total, and pages to older rows and back', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('Row 0-0')
    expect(wrapper.text()).toContain('2 of 250 events shown')
    expect(wrapper.find('[data-testid="audit-page-position"]').text()).toBe('Page 1 of 3')
    expect(wrapper.find('[data-testid="audit-page-previous"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-testid="audit-page-next"]').trigger('click')
    await flushPromises()
    expect(pageRequests().at(-1)!.get('page')).toBe('1')
    expect(wrapper.text()).toContain('Row 1-0')
    expect(wrapper.find('[data-testid="audit-page-position"]').text()).toBe('Page 2 of 3')

    await wrapper.find('[data-testid="audit-page-previous"]').trigger('click')
    await flushPromises()
    expect(pageRequests().at(-1)!.get('page')).toBe('0')
    wrapper.unmount()
  })

  it('offers the subjects of the whole log and filters on the server from page one', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="audit-page-next"]').trigger('click')
    await flushPromises()

    const subject = wrapper.find('#al-subject')
    expect(subject.findAll('option').map((o) => o.text())).toContain('M-004')
    await subject.setValue('M-004')
    await flushPromises()

    const last = pageRequests().at(-1)!
    expect(last.get('subjectId')).toBe('M-004')
    expect(last.get('page')).toBe('0')
    wrapper.unmount()
  })

  it('sends a date range to the server', async () => {
    const wrapper = await mountView()
    await wrapper.find('#al-from').setValue('2026-05-01')
    await flushPromises()
    expect(pageRequests().at(-1)!.get('from')).toBe('2026-05-01')
    wrapper.unmount()
  })
})

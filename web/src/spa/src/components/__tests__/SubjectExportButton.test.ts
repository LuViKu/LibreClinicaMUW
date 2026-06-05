/**
 * Phase E.6 — Data Export Phase 5 — SubjectExportButton specs.
 *
 * <p>Pins the load-bearing wire contract for the one-click per-subject
 * snapshot:
 *
 * <ul>
 *   <li>Each format option ({@code odm | csv | pdf}) maps to the
 *       same POST URL but flips the body's {@code format} key. A
 *       regression here is invisible to the operator until they open
 *       the wrong-extension file.</li>
 *   <li>The browser-side download filename comes from the
 *       {@code Content-Disposition} header — not from the SPA. The
 *       backend sanitises and stamps a date; if we ever swap that to
 *       a client-derived name we lose audit/operator parity.</li>
 *   <li>Backend 4xx/5xx populates the in-dropdown error region with
 *       the JSON {@code message} field, not a generic toast string.</li>
 * </ul>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import SubjectExportButton from '@/components/SubjectExportButton.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function mountButton(overrides: Partial<{ studyOid: string | null; subjectLabel: string; compact: boolean }> = {}) {
  return mount(SubjectExportButton, {
    props: {
      studyOid: 'S_DEMO1',
      subjectLabel: 'M-001',
      compact: false,
      ...overrides,
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

function blobResponse(body: string, opts: { filename?: string; contentType?: string } = {}): Response {
  const headers = new Headers({
    'Content-Type': opts.contentType ?? 'application/octet-stream',
  })
  if (opts.filename) {
    headers.set('Content-Disposition', `attachment; filename="${opts.filename}"`)
  }
  return new Response(body, { status: 200, headers })
}

describe('SubjectExportButton', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let createObjectURLMock: ReturnType<typeof vi.fn>
  let revokeObjectURLMock: ReturnType<typeof vi.fn>
  let anchorClicks: { href: string; download: string }[] = []
  let originalCreateElement: typeof document.createElement

  beforeEach(() => {
    anchorClicks = []
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    createObjectURLMock = vi.fn(() => 'blob:fake-url')
    revokeObjectURLMock = vi.fn()
    // jsdom doesn't implement URL.createObjectURL/revoke — stub on the
    // URL constructor directly. Both vi.stubGlobal and direct assignment
    // work; direct assignment is simpler.
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURLMock, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURLMock, configurable: true })

    // Capture anchor click() invocations without triggering a real
    // navigation (jsdom would warn).
    originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
      const el = originalCreateElement(tagName)
      if (tagName.toLowerCase() === 'a') {
        const anchor = el as HTMLAnchorElement
        const originalClick = anchor.click.bind(anchor)
        anchor.click = () => {
          anchorClicks.push({ href: anchor.href, download: anchor.download })
          // don't call originalClick — jsdom emits "Not implemented: navigation"
          void originalClick
        }
      }
      return el
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('opens the dropdown on trigger click', async () => {
    const wrapper = mountButton()
    expect(wrapper.find('[data-testid="subject-export-menu"]').exists()).toBe(false)
    await wrapper.find('[data-testid="subject-export-trigger"]').trigger('click')
    expect(wrapper.find('[data-testid="subject-export-menu"]').exists()).toBe(true)
    // All three format buttons rendered.
    expect(wrapper.find('[data-testid="subject-export-odm"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="subject-export-csv"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="subject-export-pdf"]').exists()).toBe(true)
  })

  it('POSTs to the studyOid/subjects/{label}/export endpoint with the chosen format', async () => {
    fetchMock.mockResolvedValueOnce(blobResponse('xml-bytes', {
      filename: 'M-001_odm_20260605.xml',
      contentType: 'application/xml',
    }))

    const wrapper = mountButton()
    await wrapper.find('[data-testid="subject-export-trigger"]').trigger('click')
    await wrapper.find('[data-testid="subject-export-odm"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/LibreClinica/pages/api/v1/studies/S_DEMO1/subjects/M-001/export')
    expect(init).toMatchObject({
      method: 'POST',
      credentials: 'include',
    })
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body)).toEqual({ format: 'odm' })
  })

  it.each(['odm', 'csv', 'pdf'] as const)(
    'wires the right format key for %s',
    async (fmt) => {
      fetchMock.mockResolvedValueOnce(blobResponse('bytes', { filename: `M-001_${fmt}_20260605.${fmt === 'odm' ? 'xml' : fmt}` }))
      const wrapper = mountButton()
      await wrapper.find('[data-testid="subject-export-trigger"]').trigger('click')
      await wrapper.find(`[data-testid="subject-export-${fmt}"]`).trigger('click')
      await flushPromises()
      const init = fetchMock.mock.calls[0][1]
      expect(JSON.parse(init.body)).toEqual({ format: fmt })
    },
  )

  it('honours the backend-stamped Content-Disposition filename when triggering the browser download', async () => {
    fetchMock.mockResolvedValueOnce(blobResponse('csv-bytes', {
      filename: 'M-001_csv_20260605.csv',
      contentType: 'text/csv',
    }))

    const wrapper = mountButton()
    await wrapper.find('[data-testid="subject-export-trigger"]').trigger('click')
    await wrapper.find('[data-testid="subject-export-csv"]').trigger('click')
    await flushPromises()

    expect(anchorClicks).toHaveLength(1)
    expect(anchorClicks[0].download).toBe('M-001_csv_20260605.csv')
    // jsdom resolves relative URLs against the test base — the value we
    // care about is the synthesised blob URL we put in href.
    expect(anchorClicks[0].href).toContain('blob:fake-url')
    expect(createObjectURLMock).toHaveBeenCalledTimes(1)
  })

  it('falls back to a synthesised filename when the header is missing', async () => {
    fetchMock.mockResolvedValueOnce(blobResponse('pdf-bytes', { contentType: 'application/pdf' }))

    const wrapper = mountButton()
    await wrapper.find('[data-testid="subject-export-trigger"]').trigger('click')
    await wrapper.find('[data-testid="subject-export-pdf"]').trigger('click')
    await flushPromises()

    expect(anchorClicks).toHaveLength(1)
    // {label}_{fmt}_{yyyymmdd}.{ext} — date varies day-to-day but the
    // first three segments are deterministic.
    expect(anchorClicks[0].download).toMatch(/^M-001_pdf_\d{8}\.pdf$/)
  })

  it('surfaces the backend JSON message on 4xx', async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({ message: 'Subject with label \'M-999\' not found in study \'S_DEMO1\'.' }),
      { status: 404, headers: { 'Content-Type': 'application/json' } },
    ))

    const wrapper = mountButton({ subjectLabel: 'M-999' })
    await wrapper.find('[data-testid="subject-export-trigger"]').trigger('click')
    await wrapper.find('[data-testid="subject-export-csv"]').trigger('click')
    await flushPromises()

    const err = wrapper.find('[data-testid="subject-export-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toContain('not found')
    // No download triggered.
    expect(anchorClicks).toHaveLength(0)
  })

  it('surfaces a generic message on 5xx', async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      '',
      { status: 500 },
    ))
    const wrapper = mountButton()
    await wrapper.find('[data-testid="subject-export-trigger"]').trigger('click')
    await wrapper.find('[data-testid="subject-export-csv"]').trigger('click')
    await flushPromises()

    const err = wrapper.find('[data-testid="subject-export-error"]')
    expect(err.exists()).toBe(true)
    // No body → fallback to the status code as the detail.
    expect(err.text()).toContain('500')
    expect(anchorClicks).toHaveLength(0)
  })

  it('disables the trigger when studyOid is null', async () => {
    const wrapper = mountButton({ studyOid: null })
    const btn = wrapper.find('[data-testid="subject-export-trigger"]')
    expect((btn.element as HTMLButtonElement).disabled).toBe(true)
    await btn.trigger('click')
    expect(wrapper.find('[data-testid="subject-export-menu"]').exists()).toBe(false)
  })
})

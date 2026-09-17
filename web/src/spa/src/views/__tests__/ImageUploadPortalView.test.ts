import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import ImageUploadPortalView from '../ImageUploadPortalView.vue'
import ImageDropzone from '@/components/imageportal/ImageDropzone.vue'
import enMessages from '@/locales/en.json'

// DR-025 — the public upload view talks to the portal client; stub it so the
// test doesn't hit the network.
const { commitMock, resolveMock, visitsMock } = vi.hoisted(() => ({
  commitMock: vi.fn(),
  resolveMock: vi.fn(),
  visitsMock: vi.fn(),
}))
vi.mock('@/api/imagePortal', () => ({
  commitImage: (...args: unknown[]) => commitMock(...args),
  resolvePatient: (...args: unknown[]) => resolveMock(...args),
  listTodaysVisits: (...args: unknown[]) => visitsMock(...args),
}))

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function mountView() {
  return mount(ImageUploadPortalView, { global: { plugins: [i18n] } })
}

beforeEach(() => {
  commitMock.mockReset()
  resolveMock.mockReset()
  visitsMock.mockReset()
  // Default: the institution has not enabled the visit list, which is what
  // the backend signals with a 404 and the client maps to null.
  visitsMock.mockResolvedValue(null)
  // jsdom has no object-URL support.
  ;(URL as unknown as { createObjectURL: () => string }).createObjectURL = vi.fn(() => 'blob:mock')
  ;(URL as unknown as { revokeObjectURL: () => void }).revokeObjectURL = vi.fn()
})

describe('ImageUploadPortalView', () => {
  it('shows the dropzone first, with the submit button disabled until a file is picked', () => {
    const w = mountView()
    expect(w.find('[data-testid="image-dropzone"]').exists()).toBe(true)
    const btn = w.find('[data-testid="upload-submit"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('picking a file shows a preview + submit; submitting calls commitImage and shows success', async () => {
    commitMock.mockResolvedValue({ imageIngestId: 1, status: 'UNBOUND' })
    const w = mountView()
    const file = new File([new Uint8Array([1, 2, 3])], 'fundus.jpg', { type: 'image/jpeg' })

    w.findComponent(ImageDropzone).vm.$emit('file-selected', file)
    await flushPromises()

    expect(w.find('[data-testid="image-preview"]').exists()).toBe(true)
    expect(w.find('[data-testid="upload-submit"]').exists()).toBe(true)

    await w.find('form').trigger('submit')
    await flushPromises()

    expect(commitMock).toHaveBeenCalledTimes(1)
    expect(commitMock.mock.calls[0][0]).toBe(file)
    expect(w.find('[data-testid="upload-success"]').exists()).toBe(true)
  })

  it('hides the visit picker when the institution has not enabled the list', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-testid="todays-visits"]').exists()).toBe(false)
  })

  it('picking a visit from the day\u2019s list sends its id, so the image is filed on arrival', async () => {
    visitsMock.mockResolvedValue([
      { studyEventId: 42, subjectLabel: 'M-001', eventLabel: 'V3 Day 90', studyName: 'HealthAEye', time: null },
      { studyEventId: 43, subjectLabel: 'M-002', eventLabel: 'V2 Day 30', studyName: 'HealthAEye', time: null },
    ])
    commitMock.mockResolvedValue({ imageIngestId: 7, status: 'BOUND' })
    const w = mountView()
    await flushPromises()

    const rows = w.findAll('[data-testid="visit-row"]')
    expect(rows).toHaveLength(2)
    await rows[1].trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="visit-chosen"]').text()).toContain('M-002')

    const file = new File([new Uint8Array([1])], 'fundus.jpg', { type: 'image/jpeg' })
    w.findComponent(ImageDropzone).vm.$emit('file-selected', file)
    await flushPromises()
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(commitMock.mock.calls[0][1]).toMatchObject({ studyEventId: 43, patientId: 'M-002' })
  })

  it('filters the visit list, because a clinic day is longer than a phone screen', async () => {
    visitsMock.mockResolvedValue([
      { studyEventId: 42, subjectLabel: 'M-001', eventLabel: 'V3 Day 90', studyName: 'HealthAEye', time: null },
      { studyEventId: 43, subjectLabel: 'X-777', eventLabel: 'V2 Day 30', studyName: 'HealthAEye', time: null },
    ])
    const w = mountView()
    await flushPromises()

    await w.find('#ip-visit-filter').setValue('x-7')
    await flushPromises()

    const rows = w.findAll('[data-testid="visit-row"]')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('X-777')
  })

  it('offers the visit found for a typed label, and sends it once accepted', async () => {
    resolveMock.mockResolvedValue({
      patientId: 'M-001',
      state: 'suggested',
      candidates: [
        {
          studyId: 1,
          studyName: 'HealthAEye',
          studyOid: 'S_HAE',
          studySubjectId: 5,
          subjectLabel: 'M-001',
          siteName: null,
          matchingEvent: {
            studyEventId: 99,
            eventCrfId: 3,
            definitionLabel: 'V3 Day 90',
            dateStart: '2026-09-18',
            matchPolicy: 'exact',
          },
        },
      ],
    })
    commitMock.mockResolvedValue({ imageIngestId: 8, status: 'BOUND' })
    const w = mountView()
    await flushPromises()

    const file = new File([new Uint8Array([1])], 'fundus.jpg', { type: 'image/jpeg' })
    w.findComponent(ImageDropzone).vm.$emit('file-selected', file)
    await flushPromises()

    await w.find('#ip-patient').setValue('M-001')
    await w.find('#ip-patient').trigger('blur')
    await flushPromises()

    const accept = w.find('[data-testid="accept-suggested-visit"]')
    expect(accept.exists()).toBe(true)
    await accept.trigger('click')
    await flushPromises()

    await w.find('form').trigger('submit')
    await flushPromises()

    expect(commitMock.mock.calls[0][1]).toMatchObject({ studyEventId: 99 })
  })

  it('does not offer a visit when the label matched more than one subject', async () => {
    resolveMock.mockResolvedValue({
      patientId: 'M-00',
      state: 'ambiguous',
      candidates: [
        { studyId: 1, studyName: 'A', studyOid: 'S_A', studySubjectId: 1, subjectLabel: 'M-001', siteName: null, matchingEvent: { studyEventId: 1, eventCrfId: null, definitionLabel: 'V1', dateStart: '2026-09-18', matchPolicy: 'exact' } },
        { studyId: 1, studyName: 'A', studyOid: 'S_A', studySubjectId: 2, subjectLabel: 'M-002', siteName: null, matchingEvent: null },
      ],
    })
    const w = mountView()
    await flushPromises()

    await w.find('#ip-patient').setValue('M-00')
    await w.find('#ip-patient').trigger('blur')
    await flushPromises()

    expect(w.find('[data-testid="accept-suggested-visit"]').exists()).toBe(false)
  })

  it('uploads without a visit when none was identified, rather than refusing the image', async () => {
    commitMock.mockResolvedValue({ imageIngestId: 9, status: 'UNBOUND' })
    const w = mountView()
    await flushPromises()

    const file = new File([new Uint8Array([1])], 'fundus.jpg', { type: 'image/jpeg' })
    w.findComponent(ImageDropzone).vm.$emit('file-selected', file)
    await flushPromises()
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(commitMock.mock.calls[0][1].studyEventId).toBeUndefined()
    expect(w.find('[data-testid="upload-success"]').exists()).toBe(true)
  })
})

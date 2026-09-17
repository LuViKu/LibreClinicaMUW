import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import ImageInboxView from '../ImageInboxView.vue'
import enMessages from '@/locales/en.json'

const { listMock, bindMock, dismissMock, confirmMock } = vi.hoisted(() => ({
  listMock: vi.fn(),
  bindMock: vi.fn(),
  dismissMock: vi.fn(),
  confirmMock: vi.fn(() => Promise.resolve(true)),
}))
vi.mock('@/api/imageInbox', () => ({
  listImageInbox: (...a: unknown[]) => listMock(...a),
  bindImage: (...a: unknown[]) => bindMock(...a),
  dismissImage: (...a: unknown[]) => dismissMock(...a),
}))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))
// The assign dialog pulls in the OCT-portal modals + their deps; stub it.
vi.mock('@/components/imageinbox/AssignImageDialog.vue', () => ({
  default: { name: 'AssignImageDialog', render: () => null },
}))

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function makeRow(over: Record<string, unknown> = {}) {
  return {
    id: 1, sourceKind: 'upload', patientId: 'HAE-001', laterality: 'OD',
    studyDate: '2026-09-09', modality: null, originalFilename: 'f.jpg',
    receivedAt: '2026-09-09T10:00:00Z',
    previewUrl: '/pages/api/v1/image-ingest/1/preview', hasPreview: true,
    suggestion: { state: 'suggested', studySubjectId: 5, subjectLabel: 'HAE-001',
      studyId: 2, studyName: 'RIS', studyEventId: 9, eventCrfId: 42 },
    ...over,
  }
}

beforeEach(() => {
  listMock.mockReset(); bindMock.mockReset(); dismissMock.mockReset()
  confirmMock.mockReset(); confirmMock.mockResolvedValue(true)
})

describe('ImageInboxView', () => {
  it('shows the empty state when there are no unbound images', async () => {
    listMock.mockResolvedValue({ images: [] })
    const w = mount(ImageInboxView, { global: { plugins: [i18n] } })
    await flushPromises()
    expect(w.find('[data-testid="inbox-empty"]').exists()).toBe(true)
  })

  it('one-click binds a suggested row via bindImage and removes it', async () => {
    listMock.mockResolvedValue({ images: [makeRow()] })
    bindMock.mockResolvedValue({ imageIngestId: 1, status: 'BOUND' })
    const w = mount(ImageInboxView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(w.find('[data-testid="inbox-grid"]').exists()).toBe(true)
    const btn = w.findAll('button').find((b) => b.text() === 'Bind suggestion')!
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()

    expect(bindMock).toHaveBeenCalledWith(1, { studySubjectId: 5, studyEventId: 9, eventCrfId: 42 })
    expect(w.find('[data-testid="inbox-empty"]').exists()).toBe(true)
  })
})

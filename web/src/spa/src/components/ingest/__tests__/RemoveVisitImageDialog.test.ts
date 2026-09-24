import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import de from '@/locales/de.json'

const unbindIngestItem = vi.fn()
vi.mock('@/api/ingest', () => ({
  unbindIngestItem: (...a: unknown[]) => unbindIngestItem(...a),
}))

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    constructor(public status: number, msg: string, public body: unknown = null) {
      super(msg)
    }
  }
  return { ApiError }
})

// The modal renders through a teleport in the real component; a stub keeps
// the dialog's own markup in the wrapper so the test can reach it.
vi.mock('@/components/Modal.vue', () => ({
  default: {
    props: ['open', 'labelledBy', 'panelClass'],
    template: '<div v-if="open"><slot name="header" /><slot /><slot name="footer" /></div>',
  },
}))

// eslint-disable-next-line import/first
import { ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import RemoveVisitImageDialog from '../RemoveVisitImageDialog.vue'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

const image = {
  id: 7,
  kind: 'image',
  sourceKind: 'remidio',
  device: 'remidio',
  patientId: 'HAE-002',
  laterality: 'OD',
  acquisitionDate: '2026-09-24',
  acquisitionDateSource: 'device',
  modality: null,
  originalFilename: null,
  byteSize: null,
  scanIndex: null,
  receivedAt: null,
  previewUrl: '/pages/api/v1/ingest/7/preview',
  hasPreview: true,
  suggestion: null,
}

function mountDialog() {
  return mount(RemoveVisitImageDialog, {
    props: { open: true, image },
    global: { plugins: [i18n] },
  })
}

describe('RemoveVisitImageDialog', () => {
  beforeEach(() => {
    unbindIngestItem.mockReset()
    unbindIngestItem.mockResolvedValue({ ingestItemId: 7, status: 'UNBOUND' })
  })

  it('defaults to "wrong visit": a plain unbind, back to the inbox', async () => {
    const w = mountDialog()
    await w.get('[data-testid="remove-visit-image-confirm"]').trigger('click')
    await flushPromises()

    expect(unbindIngestItem).toHaveBeenCalledWith(7, { dismiss: false, reason: undefined })
    expect(w.emitted('removed')?.[0]).toEqual(['pool'])
    expect(w.emitted('update:open')?.[0]).toEqual([false])
  })

  it('"not a study image" unbinds and dismisses in one request, with the reason', async () => {
    const w = mountDialog()
    await w.get('[data-testid="remove-intent-dismiss"]').setValue(true)
    await w.get('[data-testid="remove-visit-image-reason"]').setValue('  Testaufnahme ')
    await w.get('[data-testid="remove-visit-image-confirm"]').trigger('click')
    await flushPromises()

    expect(unbindIngestItem).toHaveBeenCalledWith(7, { dismiss: true, reason: 'Testaufnahme' })
    expect(w.emitted('removed')?.[0]).toEqual(['dismissed'])
  })

  it('the reason field only appears for dismiss, and a blank one is not sent', async () => {
    const w = mountDialog()
    expect(w.find('[data-testid="remove-visit-image-reason"]').exists()).toBe(false)
    await w.get('[data-testid="remove-intent-dismiss"]').setValue(true)
    expect(w.find('[data-testid="remove-visit-image-reason"]').exists()).toBe(true)
    await w.get('[data-testid="remove-visit-image-confirm"]').trigger('click')
    await flushPromises()

    expect(unbindIngestItem).toHaveBeenCalledWith(7, { dismiss: true, reason: undefined })
  })

  it('says in words when the visit is signed or locked', async () => {
    unbindIngestItem.mockRejectedValue(new ApiError(409, 'sealed', { reason: 'VISIT_SEALED' }))
    const w = mountDialog()
    await w.get('[data-testid="remove-visit-image-confirm"]').trigger('click')
    await flushPromises()

    expect(w.get('[data-testid="remove-visit-image-error"]').text()).toContain('unterschrieben oder gesperrt')
    expect(w.emitted('removed')).toBeUndefined()
  })

  it('names the image in the subtitle without exposing anything but kind, eye and date', () => {
    const w = mountDialog()
    expect(w.get('[data-testid="remove-visit-image-subtitle"]').text()).toBe('IMAGE · Rechtes Auge (OD) · 2026-09-24')
  })
})

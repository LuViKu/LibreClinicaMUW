/**
 * 2026-09-24 — the two lifecycle steps the inbox lacked: a way back from
 * dismissed, and dismissing a selection at once.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import de from '@/locales/de.json'

const listIngestInbox = vi.fn()
const ingestInboxCounts = vi.fn()
const restoreIngestItem = vi.fn()
const bulkDismissIngestItems = vi.fn()
const unbindIngestItem = vi.fn()
const confirmFn = vi.fn()

vi.mock('@/api/ingest', () => ({
  listIngestInbox: (...a: unknown[]) => listIngestInbox(...a),
  ingestInboxCounts: (...a: unknown[]) => ingestInboxCounts(...a),
  restoreIngestItem: (...a: unknown[]) => restoreIngestItem(...a),
  bulkDismissIngestItems: (...a: unknown[]) => bulkDismissIngestItems(...a),
  unbindIngestItem: (...a: unknown[]) => unbindIngestItem(...a),
  bindIngestItem: vi.fn(),
  bulkBindIngestItems: vi.fn(),
  dismissIngestItem: vi.fn(),
  isDateMismatch: () => false,
}))

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    constructor(public status: number, msg: string, public body: unknown = null) {
      super(msg)
    }
  }
  return { ApiError }
})

vi.mock('@/composables/useConfirm', () => ({
  useConfirm: () => (...a: unknown[]) => confirmFn(...a),
}))

vi.mock('@/components/ingest/AssignIngestDialog.vue', () => ({
  default: { name: 'AssignIngestDialog', template: '<div />' },
}))

// eslint-disable-next-line import/first
import { ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import IngestInboxView from '../IngestInboxView.vue'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function row(id: number, status = 'UNBOUND') {
  return {
    id, kind: 'image', sourceKind: 'remidio', device: 'remidio', patientId: 'TEST3',
    laterality: 'OD', acquisitionDate: '2026-09-24', acquisitionDateSource: 'device',
    modality: null, originalFilename: null, byteSize: 1000, scanIndex: null,
    receivedAt: '2026-09-24T10:00:00Z', previewUrl: `/pages/api/v1/ingest/${id}/preview`,
    hasPreview: true, suggestion: null, status,
  }
}

function mountInbox() {
  return mount(IngestInboxView, {
    global: { plugins: [i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('inbox lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ingestInboxCounts.mockResolvedValue({ unbound: 3, byKind: { image: 3 } })
    confirmFn.mockResolvedValue(true)
  })

  it('a dismissed file offers Restore and goes back to the inbox', async () => {
    listIngestInbox
      .mockResolvedValueOnce({ items: [row(1)], limit: 100, status: 'UNBOUND' })
      .mockResolvedValueOnce({ items: [row(5, 'DISMISSED')], limit: 100, status: 'DISMISSED' })
    restoreIngestItem.mockResolvedValue({ ingestItemId: 5, status: 'UNBOUND' })
    const w = mountInbox()
    await flushPromises()

    await w.get('[data-testid="status-select"]').setValue('DISMISSED')
    await flushPromises()

    expect(w.find('[data-testid="restore-5"]').exists()).toBe(true)
    await w.get('[data-testid="restore-5"]').trigger('click')
    await flushPromises()

    expect(restoreIngestItem).toHaveBeenCalledWith(5)
    expect(w.find('[data-testid="restore-5"]').exists()).toBe(false)
  })

  it('a selection can be dismissed at once, and refused rows are reported', async () => {
    listIngestInbox.mockResolvedValue({ items: [row(1), row(2), row(3)], limit: 100, status: 'UNBOUND' })
    bulkDismissIngestItems.mockResolvedValue({ dismissed: [1, 2], skipped: [{ id: 3, reason: 'WRONG_STATE' }] })
    const w = mountInbox()
    await flushPromises()

    const boxes = w.findAll('input[type="checkbox"]')
    expect(boxes.length).toBeGreaterThanOrEqual(3)
    for (const b of boxes.slice(0, 3)) await b.setValue(true)
    await flushPromises()

    await w.get('[data-testid="bulk-dismiss"]').trigger('click')
    await flushPromises()

    expect(confirmFn).toHaveBeenCalled()
    expect(bulkDismissIngestItems).toHaveBeenCalledWith([1, 2, 3])
    expect(w.get('[data-testid="inbox-error"]').text()).toContain('1 Datei(en)')
  })

  it('no bulk dismiss in the bound view — dismiss applies to unreconciled files only', async () => {
    listIngestInbox
      .mockResolvedValueOnce({ items: [row(1)], limit: 100, status: 'UNBOUND' })
      .mockResolvedValueOnce({ items: [row(9, 'BOUND')], limit: 100, status: 'BOUND' })
    const w = mountInbox()
    await flushPromises()
    await w.get('[data-testid="status-select"]').setValue('BOUND')
    await flushPromises()
    const box = w.find('input[type="checkbox"]')
    if (box.exists()) {
      await box.setValue(true)
      await flushPromises()
    }
    expect(w.find('[data-testid="bulk-dismiss"]').exists()).toBe(false)
  })

  it('unbinding a file on a signed visit is explained, not shown as a raw 409', async () => {
    listIngestInbox
      .mockResolvedValueOnce({ items: [row(1)], limit: 100, status: 'UNBOUND' })
      .mockResolvedValueOnce({ items: [row(9, 'BOUND')], limit: 100, status: 'BOUND' })
    unbindIngestItem.mockRejectedValue(new ApiError(409, 'sealed', { reason: 'VISIT_SEALED' }))
    const w = mountInbox()
    await flushPromises()
    await w.get('[data-testid="status-select"]').setValue('BOUND')
    await flushPromises()

    await w.get('[data-testid="unbind-9"]').trigger('click')
    await flushPromises()

    expect(w.get('[data-testid="inbox-error"]').text()).toContain('unterschrieben oder gesperrt')
    expect(w.find('[data-testid="unbind-9"]').exists()).toBe(true)
  })
})

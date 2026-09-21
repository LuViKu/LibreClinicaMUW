import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import de from '@/locales/de.json'

/**
 * P3.2 — the unified ingest inbox.
 *
 * The listing is not the interesting part. What is worth pinning is what the
 * merge of two queues into one made possible and what it risks: that a scan
 * with no preview still says what it is, that selecting several files binds
 * them as one decision without silently dropping the ones that fail, and that
 * undoing a filing is confirmed — because it also removes a CRF value.
 */

const listIngestInbox = vi.fn()
const ingestInboxCounts = vi.fn()
const bindIngestItem = vi.fn()
const bulkBindIngestItems = vi.fn()
const dismissIngestItem = vi.fn()
const unbindIngestItem = vi.fn()

vi.mock('@/api/ingest', () => ({
  listIngestInbox: (...a: unknown[]) => listIngestInbox(...a),
  ingestInboxCounts: (...a: unknown[]) => ingestInboxCounts(...a),
  bindIngestItem: (...a: unknown[]) => bindIngestItem(...a),
  bulkBindIngestItems: (...a: unknown[]) => bulkBindIngestItems(...a),
  dismissIngestItem: (...a: unknown[]) => dismissIngestItem(...a),
  unbindIngestItem: (...a: unknown[]) => unbindIngestItem(...a),
}))

const confirmMock = vi.fn()
vi.mock('@/composables/useConfirm', () => ({
  useConfirm: () => confirmMock,
}))

// Emit-capable stub: the tests drive the real @bind handler rather than
// reaching into the view's internals.
vi.mock('@/components/ingest/AssignIngestDialog.vue', () => ({
  default: {
    name: 'AssignIngestDialog',
    props: ['open', 'ingestItemId', 'initialPatientId'],
    emits: ['bind', 'close'],
    render: () => null,
  },
}))

import IngestInboxView from '../IngestInboxView.vue'

function row(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    kind: 'image',
    sourceKind: 'upload',
    device: 'remidio',
    patientId: 'M-001',
    laterality: 'OD',
    acquisitionDate: '2026-09-18',
    modality: null,
    originalFilename: 'a.jpg',
    byteSize: 2048,
    scanIndex: null,
    receivedAt: '2026-09-18T09:00:00Z',
    previewUrl: '/pages/api/v1/ingest/1/preview',
    hasPreview: true,
    suggestion: null,
    ...over,
  }
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
  return mount(IngestInboxView, { global: { plugins: [i18n] } })
}

describe('IngestInboxView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listIngestInbox.mockResolvedValue({ items: [row()], limit: 100, status: 'UNBOUND' })
    ingestInboxCounts.mockResolvedValue({ unbound: 1, byKind: { image: 1 } })
    confirmMock.mockResolvedValue(true)
  })

  it('asks for unreconciled files first — that is the working queue', async () => {
    mountView()
    await flushPromises()
    expect(listIngestInbox).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'UNBOUND', kind: null }),
    )
  })

  it('narrows to one kind when a chip is picked', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-testid="kind-e2e"]').trigger('click')
    await flushPromises()
    expect(listIngestInbox).toHaveBeenLastCalledWith(expect.objectContaining({ kind: 'e2e' }))
  })

  /**
   * An OCT volume has no preview until the pipeline renders one. Showing an
   * empty frame would read as a broken image rather than as a 200 MB scan.
   */
  it('a file with no preview still says what it is', async () => {
    listIngestInbox.mockResolvedValue({
      items: [row({ id: 7, kind: 'e2e', hasPreview: false, byteSize: 209715200 })],
      limit: 100,
      status: 'UNBOUND',
    })
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain(de.ingestInbox.kind.e2e)
    expect(w.text()).toContain('200 MB')
  })

  it('one file goes through the single-item endpoint, not the bulk one', async () => {
    bindIngestItem.mockResolvedValue({ ingestItemId: 1, status: 'BOUND' })
    const w = mountView()
    await flushPromises()
    await w.get('[data-testid="inbox-grid"] button:nth-of-type(1)').trigger('click')
    await w.findComponent({ name: 'AssignIngestDialog' }).vm.$emit('bind', {
      ingestItemId: 1, studySubjectId: 5, studyEventId: null, eventCrfId: null,
    })
    await flushPromises()
    expect(bindIngestItem).toHaveBeenCalledWith(1, expect.objectContaining({ studySubjectId: 5 }))
    expect(bulkBindIngestItems).not.toHaveBeenCalled()
  })

  describe('selecting several files', () => {
    beforeEach(() => {
      listIngestInbox.mockResolvedValue({
        items: [row({ id: 1 }), row({ id: 2 }), row({ id: 3 })],
        limit: 100,
        status: 'UNBOUND',
      })
    })

    it('shows the selection banner only once something is ticked', async () => {
      const w = mountView()
      await flushPromises()
      expect(w.find('[data-testid="bulk-banner"]').exists()).toBe(false)
      await w.get('[data-testid="select-2"]').trigger('change')
      expect(w.find('[data-testid="bulk-banner"]').exists()).toBe(true)
      expect(w.get('[data-testid="bulk-banner"]').text()).toContain('1')
    })

    it('says so when some of the selection could not be filed', async () => {
      bulkBindIngestItems.mockResolvedValue({
        bound: [1],
        skipped: [{ id: 2, reason: 'WRONG_STATE' }],
      })
      const w = mountView()
      await flushPromises()
      await w.get('[data-testid="select-1"]').trigger('change')
      await w.get('[data-testid="select-2"]').trigger('change')
      await w.get('[data-testid="bulk-bind"]').trigger('click')
      await w.findComponent({ name: 'AssignIngestDialog' }).vm.$emit('bind', {
        ingestItemId: 0, studySubjectId: 5, studyEventId: null, eventCrfId: null,
      })
      await flushPromises()
      expect(bulkBindIngestItems).toHaveBeenCalledWith([1, 2], expect.objectContaining({
        studySubjectId: 5,
      }))
      // Silently binding fewer than the operator selected is the failure to
      // avoid — they would believe all of them were filed.
      expect(w.get('[data-testid="inbox-error"]').text()).toContain('1')
    })
  })

  describe('undoing a filing', () => {
    beforeEach(() => {
      listIngestInbox.mockResolvedValue({
        items: [row({ id: 4 })],
        limit: 100,
        status: 'BOUND',
      })
    })

    it('offers undo only while looking at filed files', async () => {
      const w = mountView()
      await flushPromises()
      await w.get('[data-testid="status-select"]').setValue('BOUND')
      await flushPromises()
      expect(w.find('[data-testid="unbind-4"]').exists()).toBe(true)
    })

    it('confirms first, because it also removes a CRF value', async () => {
      confirmMock.mockResolvedValue(false)
      const w = mountView()
      await flushPromises()
      await w.get('[data-testid="status-select"]').setValue('BOUND')
      await flushPromises()
      await w.get('[data-testid="unbind-4"]').trigger('click')
      await flushPromises()
      expect(confirmMock).toHaveBeenCalled()
      expect(unbindIngestItem).not.toHaveBeenCalled()
    })
  })
})

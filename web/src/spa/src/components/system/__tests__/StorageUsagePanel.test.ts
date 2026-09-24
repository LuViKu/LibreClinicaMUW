import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import en from '@/locales/en.json'
import type { StorageResponse } from '@/types/systemHealth'

/**
 * DR-033 — the storage panel on the System Status page.
 *
 * Pinned: how full each disk is and, when free space shrank last week,
 * roughly when it runs out (coloured by urgency); each store's size, file
 * count and week's change, with a store that is absent or only partly
 * measured saying so; a store in the container's own layer is called out,
 * because it does not survive an upgrade; "measure now" starts a scan and
 * the panel polls until it is done.
 */

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn() }
})

import { apiGet, apiPost } from '@/api/client'
import StorageUsagePanel from '../StorageUsagePanel.vue'

const apiGetMock = vi.mocked(apiGet)
const apiPostMock = vi.mocked(apiPost)

const GIB = 1024 ** 3

function response(over: Partial<StorageResponse> = {}): StorageResponse {
  return {
    scanning: false,
    sampledAt: '2026-09-24T09:00:00Z',
    scanDurationMs: 1200,
    stores: [
      { key: 'ingest', path: '/var/lib/libreclinica/ingest', present: true, usedBytes: 12 * GIB, fileCount: 4210, complete: true, fsKey: 'sda1', containerLayer: false, usedBytesBefore: 10 * GIB, beforeAt: '2026-09-17T09:00:00Z' },
      { key: 'retinal-artifacts', path: '/var/lib/libreclinica/retinal-artifacts', present: true, usedBytes: 80 * GIB, fileCount: 250000, complete: false, fsKey: 'sda1', containerLayer: false, usedBytesBefore: null, beforeAt: null },
      { key: 'dicom-ingest', path: '/var/lib/libreclinica/dicom-ingest', present: false, usedBytes: 0, fileCount: 0, complete: true, fsKey: null, containerLayer: false, usedBytesBefore: null, beforeAt: null },
      { key: 'app-data', path: '/usr/local/tomcat/libreclinica.data', present: true, usedBytes: 2 * 1024 ** 2, fileCount: 12, complete: true, fsKey: 'overlay', containerLayer: true, usedBytesBefore: 2 * 1024 ** 2, beforeAt: '2026-09-17T09:00:00Z' },
    ],
    filesystems: [
      { key: 'sda1', type: 'ext4', containerLayer: false, totalBytes: 500 * GIB, usableBytes: 60 * GIB, usedPercent: 88, usableBytesBefore: 95 * GIB, beforeAt: '2026-09-17T09:00:00Z', daysUntilFull: 12.4, stores: ['ingest', 'retinal-artifacts'] },
      { key: 'overlay', type: 'overlay', containerLayer: true, totalBytes: 100 * GIB, usableBytes: 70 * GIB, usedPercent: 30, usableBytesBefore: 70 * GIB, beforeAt: '2026-09-17T09:00:00Z', daysUntilFull: null, stores: ['app-data'] },
    ],
    database: { sizeBytes: 3 * GIB, sizeBytesBefore: 2.5 * GIB, beforeAt: '2026-09-17T09:00:00Z', largestTables: [{ name: 'item_data', bytes: 900 * 1024 ** 2 }] },
    ...over,
  }
}

function mountPanel() {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  return mount(StorageUsagePanel, { global: { plugins: [i18n] } })
}

describe('StorageUsagePanel', () => {
  beforeEach(() => {
    // Only the poll's timer: flushPromises needs a real setImmediate.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    apiGetMock.mockReset()
    apiPostMock.mockReset()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows each disk, how full it is, and when it runs out at last week’s rate', async () => {
    apiGetMock.mockResolvedValue(response())
    const w = mountPanel()
    await flushPromises()

    const [data, overlay] = w.findAll('[data-testid="storage-fs"]')
    expect(data.text()).toContain('Disk with Inbox (images, DICOM), Retinal analysis results')
    expect(data.text()).toContain('60 GB free of 500 GB')
    expect(data.find('[data-testid="storage-used-percent"]').text()).toBe('88 % used')
    const full = data.find('[data-testid="storage-days-until-full"]')
    expect(full.text()).toBe("full in about 12 days at last week's rate")
    expect(full.classes()).toContain('text-amber-800')
    // the second disk did not shrink, and is the container's own layer
    expect(overlay.find('[data-testid="storage-not-filling"]').exists()).toBe(true)
    expect(overlay.text()).toContain("This is the container's own layer")
  })

  it('lists each store with its size, files and week, and says when a figure is partial or absent', async () => {
    apiGetMock.mockResolvedValue(response())
    const w = mountPanel()
    await flushPromises()

    const store = (key: string) => w.find(`[data-store="${key}"]`).text()
    expect(store('ingest')).toContain('12 GB')
    expect(store('ingest')).toContain('4,210')
    expect(store('ingest')).toContain('+2.0 GB')
    expect(store('retinal-artifacts')).toContain('at least: the measurement stopped at its limit')
    expect(store('dicom-ingest')).toContain('not present on this server')
    expect(store('app-data')).toContain('not on a mounted volume')
    expect(store('app-data')).toContain('±0 B')
    const db = w.find('[data-testid="storage-database"]').text()
    expect(db).toContain('3.0 GB')
    expect(db).toContain('+512 MB')
    expect(db).toContain('largest: item_data 900 MB')
    expect(w.text()).toContain('No measurement from a week ago yet')
  })

  it('starts a measurement, then polls until it is done', async () => {
    apiGetMock.mockResolvedValueOnce(response())
    apiPostMock.mockResolvedValue({ scanning: true, started: true })
    const w = mountPanel()
    await flushPromises()

    apiGetMock.mockResolvedValueOnce(response({ scanning: true }))
    apiGetMock.mockResolvedValueOnce(response({ scanning: false, sampledAt: '2026-09-24T10:05:00Z' }))
    await w.find('[data-testid="storage-rescan"]').trigger('click')
    await flushPromises()
    expect(apiPostMock).toHaveBeenCalledWith('/pages/api/v1/admin/storage/rescan', {})
    expect(w.find('[data-testid="storage-scanning"]').exists()).toBe(true)
    expect(w.find('[data-testid="storage-rescan"]').attributes('disabled')).toBeDefined()

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(w.find('[data-testid="storage-scanning"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(w.find('[data-testid="storage-scanning"]').exists()).toBe(false)
    expect(apiGetMock).toHaveBeenCalledTimes(3)

    // done: no further polling
    await vi.advanceTimersByTimeAsync(30000)
    expect(apiGetMock).toHaveBeenCalledTimes(3)
  })

  it('explains a server that has not measured yet', async () => {
    apiGetMock.mockResolvedValueOnce(response({ sampledAt: null, scanning: true, stores: [], filesystems: [] }))
    apiGetMock.mockResolvedValue(response())
    const w = mountPanel()
    await flushPromises()

    expect(w.find('[data-testid="storage-none"]').text()).toContain('Nothing measured yet')
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(w.find('[data-testid="storage-none"]').exists()).toBe(false)
  })

  it('says so when the request fails', async () => {
    apiGetMock.mockRejectedValue(new Error('boom'))
    const w = mountPanel()
    await flushPromises()

    expect(w.find('[role="alert"]').text()).toBe('Failed to load the storage figures.')
  })
})

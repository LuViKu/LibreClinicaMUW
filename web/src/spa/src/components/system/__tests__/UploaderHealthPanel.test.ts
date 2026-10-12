import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import en from '@/locales/en.json'
import de from '@/locales/de.json'
import type { Uploader, UploadersResponse } from '@/types/systemHealth'

/**
 * DR-033 — the uploader panel on the System Status page.
 *
 * Pinned: each program's state reads as what it means (a PC that shut down
 * in the evening is not an alarm, a closed or silent one is); the program's
 * own problems and the server's are spelt out, and a code this page does not
 * know yet is shown as it came rather than hidden; the queue and the disk are
 * there to act on; removing asks first; the arrivals per device sit beside
 * it; and a failed request says so instead of an empty table. Since uploaders
 * carry a token of their own, the two problems a missing or refused token
 * reports (token-required, token-rejected) are spelt out in both languages
 * and say what to do, while the old shared-token code keeps its text.
 */

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiDelete: vi.fn() }
})
const confirmMock = vi.fn()
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

import { apiDelete, apiGet } from '@/api/client'
import UploaderHealthPanel from '../UploaderHealthPanel.vue'

const apiGetMock = vi.mocked(apiGet)
const apiDeleteMock = vi.mocked(apiDelete)

const GIB = 1024 ** 3

function uploader(over: Partial<Uploader>): Uploader {
  return {
    id: 1, kind: 'export-watcher', name: 'CLARUS-PC', version: '2026-09-24', status: 'ok', issues: [],
    stopReason: null, firstSeenAt: '2026-09-20T08:00:00Z', lastSeenAt: '2026-09-24T09:59:00Z',
    secondsSinceSeen: 45, heartbeatIntervalSec: 120, running: true, enabled: true,
    lastActivityAt: '2026-09-24T09:59:00Z', lastUploadAt: null, uploadedToday: 12, pendingFiles: 0,
    oldestPendingMinutes: null, failedFiles: 0, diskFreeBytes: 120 * GIB, diskTotalBytes: 250 * GIB,
    ...over,
  }
}

const RESPONSE: UploadersResponse = {
  heartbeatEnabled: true,
  uploaders: [
    uploader({ id: 1 }),
    uploader({
      id: 2, name: 'SPECTRALIS-PC', status: 'warning', pendingFiles: 4, oldestPendingMinutes: 42, failedFiles: 2,
      issues: ['server-unreachable', 'files-failed', 'pending-stale', 'disk-low', 'brand-new-code'],
      diskFreeBytes: 3 * GIB,
    }),
    uploader({ id: 3, kind: 'optomed-bridge', name: 'CLARUS-PC', status: 'stopped', stopReason: 'session-end', running: false }),
    uploader({ id: 4, name: 'OLD-PC', status: 'stopped', stopReason: 'exit', running: false }),
    uploader({ id: 5, name: 'DEAD-PC', status: 'offline', secondsSinceSeen: 7200 }),
    uploader({ id: 6, name: 'OFF-PC', status: 'disabled', enabled: false }),
  ],
  ingestByDevice: [
    { device: 'clarus', sourceKind: 'upload', lastReceivedAt: new Date(Date.now() - 3_600_000).toISOString(), last24h: 5, last7d: 30 },
    { device: null, sourceKind: 'dicom', lastReceivedAt: null, last24h: 0, last7d: 2 },
  ],
}

function mountPanel(refreshKey = 0) {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  return mount(UploaderHealthPanel, { props: { refreshKey }, global: { plugins: [i18n] } })
}

function mountPanelIn(locale: 'de' | 'en') {
  const i18n = createI18n({ legacy: false, locale, messages: { de, en } })
  return mount(UploaderHealthPanel, { props: { refreshKey: 0 }, global: { plugins: [i18n] } })
}

function row(w: ReturnType<typeof mountPanel>, i: number) {
  return w.findAll('[data-testid="uploader-row"]')[i]
}

describe('UploaderHealthPanel', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiDeleteMock.mockReset()
    confirmMock.mockReset()
    apiGetMock.mockResolvedValue(RESPONSE)
  })

  it('reads each state as what it means', async () => {
    const w = mountPanel()
    await flushPromises()

    const status = (i: number) => row(w, i).find('[data-testid="uploader-status"]').text()
    expect(status(0)).toBe('OK')
    expect(status(1)).toBe('Needs attention')
    expect(status(2)).toBe('Logged off or shut down')
    expect(status(3)).toBe('Closed')
    expect(status(4)).toBe('Silent')
    expect(status(5)).toBe('Switched off')
    // an evening shutdown is not painted as an alarm; a closed program is
    expect(row(w, 2).find('[data-testid="uploader-status"]').classes()).toContain('bg-slate-50')
    expect(row(w, 3).find('[data-testid="uploader-status"]').classes()).toContain('bg-rose-50')
    expect(row(w, 0).text()).toContain('Export Watcher · 2026-09-24')
    expect(row(w, 2).text()).toContain('Optomed Bridge')
  })

  it('spells out the problems, and shows a code it does not know as it came', async () => {
    const w = mountPanel()
    await flushPromises()

    const issues = row(w, 1).find('[data-testid="uploader-issues"]').text()
    expect(issues).toContain('cannot reach the platform')
    expect(issues).toContain('files were moved to _failed after five attempts')
    expect(issues).toContain('files have been waiting 30 minutes or longer')
    expect(issues).toContain("the PC's disk is almost full")
    expect(issues).toContain('brand-new-code')
  })

  describe('the token problems', () => {
    const TOKEN_ROWS: UploadersResponse = {
      heartbeatEnabled: true,
      uploaders: [
        uploader({ id: 11, name: 'NO-TOKEN-PC', status: 'warning', issues: ['token-required'] }),
        uploader({ id: 12, name: 'BAD-TOKEN-PC', status: 'warning', issues: ['token-rejected'] }),
        uploader({ id: 13, kind: 'optomed-bridge', name: 'OLD-BRIDGE', status: 'warning', issues: ['worklist-token-rejected'] }),
      ],
      ingestByDevice: [],
    }
    const issues = (w: ReturnType<typeof mountPanel>, i: number) =>
      row(w, i).find('[data-testid="uploader-issues"]').text()

    it('says in English what is wrong and what to do, and keeps the old worklist text', async () => {
      apiGetMock.mockResolvedValue(TOKEN_ROWS)
      const w = mountPanelIn('en')
      await flushPromises()
      expect(issues(w, 0)).toBe("token missing – enter it in the uploader's settings")
      expect(issues(w, 1)).toBe(
        "token rejected – check it in the uploader's settings or replace it on the “Uploaders and tokens” page",
      )
      expect(issues(w, 2)).toBe('the platform rejected the worklist token')
    })

    it('says it in German, with real umlauts, and never shows the raw codes', async () => {
      apiGetMock.mockResolvedValue(TOKEN_ROWS)
      const w = mountPanelIn('de')
      await flushPromises()
      expect(issues(w, 0)).toBe('Token fehlt – in den Einstellungen des Uploaders eintragen')
      expect(issues(w, 1)).toBe(
        'Token abgelehnt – in den Einstellungen des Uploaders prüfen oder auf der Seite „Uploader und Tokens“ ersetzen',
      )
      expect(issues(w, 2)).toBe('die Plattform hat den Worklist-Token abgelehnt')
      for (const code of ['token-required', 'token-rejected', 'worklist-token-rejected']) {
        expect(w.text()).not.toContain(code)
      }
      // vue-i18n prints the key itself for a message it cannot find.
      expect(w.text()).not.toMatch(/adminSystemStatus\.uploaders\.issue/)
    })

    it('names the page where a rejected token is replaced as that page names itself', async () => {
      // The text sends the person to a page; the page must be called what the text calls it.
      expect(de.adminSystemStatus.uploaders.issue.tokenRejected).toContain(`„${de.uploaders.title}“`)
      expect(en.adminSystemStatus.uploaders.issue.tokenRejected).toContain(`“${en.uploaders.title}”`)
    })
  })

  it('shows the queue, what failed, the disk, and when it last reported', async () => {
    const w = mountPanel()
    await flushPromises()

    const warn = row(w, 1).text()
    expect(warn).toContain('4 waiting')
    expect(warn).toContain('oldest for 42 min')
    expect(warn).toContain('2 failed (in _failed)')
    expect(warn).toContain('3.0 GB free of 250 GB')
    expect(row(w, 0).text()).toContain('45 seconds ago')
    expect(row(w, 0).text()).toContain('12 uploaded today')
    expect(row(w, 4).text()).toContain('2 hours ago')
  })

  it('lists what arrived per device and way in', async () => {
    const w = mountPanel()
    await flushPromises()

    const rows = w.findAll('[data-testid="arrival-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('clarus')
    expect(rows[0].text()).toContain('Upload (page, Export Watcher, Bridge)')
    expect(rows[0].text()).toContain('1 hour ago')
    expect(rows[1].text()).toContain('not named')
    expect(rows[1].text()).toContain('DICOM receiver (network)')
  })

  it('asks before removing, and reloads after', async () => {
    apiDeleteMock.mockResolvedValue(undefined)
    const w = mountPanel()
    await flushPromises()

    confirmMock.mockResolvedValueOnce(false)
    await row(w, 3).find('[data-testid="uploader-remove"]').trigger('click')
    await flushPromises()
    expect(apiDeleteMock).not.toHaveBeenCalled()

    confirmMock.mockResolvedValueOnce(true)
    await row(w, 3).find('[data-testid="uploader-remove"]').trigger('click')
    await flushPromises()
    expect(confirmMock.mock.calls[1][0].message).toContain('Export Watcher on OLD-PC')
    expect(apiDeleteMock).toHaveBeenCalledWith('/pages/api/v1/admin/uploaders/4')
    expect(apiGetMock).toHaveBeenCalledTimes(2)
  })

  it('explains an empty list and heartbeats that are switched off', async () => {
    apiGetMock.mockResolvedValue({ heartbeatEnabled: false, uploaders: [], ingestByDevice: [] })
    const w = mountPanel()
    await flushPromises()

    expect(w.find('[data-testid="uploaders-empty"]').text()).toContain('No uploader has reported yet')
    expect(w.find('[data-testid="heartbeat-off"]').exists()).toBe(true)
    expect(w.text()).toContain('Nothing has arrived in the last 90 days.')
  })

  it('says so when the request fails', async () => {
    apiGetMock.mockRejectedValue(new Error('boom'))
    const w = mountPanel()
    await flushPromises()

    expect(w.find('[role="alert"]').text()).toBe('Failed to load the uploaders.')
  })

  it('fetches again when the page is refreshed', async () => {
    const w = mountPanel(0)
    await flushPromises()
    await w.setProps({ refreshKey: 1 })
    await flushPromises()

    expect(apiGetMock).toHaveBeenCalledTimes(2)
  })
})

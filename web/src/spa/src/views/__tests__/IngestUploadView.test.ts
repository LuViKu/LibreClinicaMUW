/**
 * DR-029 — the uploader behind a login is the same workbench in staff mode.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import enMessages from '@/locales/en.json'

const api = vi.hoisted(() => ({ listVisitsForDay: vi.fn() }))
vi.mock('@/api/uploadWorkbench', () => ({
  listVisitsForDay: (...a: unknown[]) => api.listVisitsForDay(...a),
  resolveRows: vi.fn(),
  preflight: vi.fn(),
  commitFile: vi.fn(),
  undoItem: vi.fn(),
  undoJob: vi.fn(),
  sha256OfFile: vi.fn(),
  searchPatientsPublic: vi.fn(),
  listPatientEventsPublic: vi.fn(),
  localIsoToday: () => '2026-09-18',
}))

import IngestUploadView from '@/views/IngestUploadView.vue'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

beforeEach(() => {
  setActivePinia(createPinia())
  api.listVisitsForDay.mockReset()
})

describe('IngestUploadView', () => {
  it('mounts the workbench in staff mode with a way back to the inbox', async () => {
    api.listVisitsForDay.mockResolvedValue([
      { studyEventId: 7, studySubjectId: 3, subjectLabel: 'M-003', eventLabel: 'Baseline', studyName: 'Default', time: null },
    ])
    const w = mount(IngestUploadView, {
      global: {
        plugins: [i18n],
        stubs: { RouterLink: { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' } },
      },
    })
    await flushPromises()
    await flushPromises()

    expect(w.find('h1').text()).toBe('Upload acquisitions')
    expect(w.find('[data-testid="upload-workbench"]').attributes('data-mode')).toBe('staff')
    expect(w.find('[data-testid="back-to-inbox"]').attributes('data-to')).toContain('ingest-inbox')
    // Staff mode lists the day's visits from the due-visits endpoint — no
    // institutional switch involved.
    expect(api.listVisitsForDay).toHaveBeenCalledWith('staff', undefined)
    expect(w.findAll('[data-testid="visit-row"]')).toHaveLength(1)
  })
})

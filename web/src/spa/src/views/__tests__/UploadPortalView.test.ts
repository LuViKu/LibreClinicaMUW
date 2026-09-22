/**
 * DR-029 — the public upload page: one door, every kind, no login.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import enMessages from '@/locales/en.json'

const api = vi.hoisted(() => ({
  listVisitsForDay: vi.fn(),
  resolveRows: vi.fn(),
  preflight: vi.fn(),
  commitFile: vi.fn(),
  undoItem: vi.fn(),
  undoJob: vi.fn(),
  sha256OfFile: vi.fn(),
  searchPatientsPublic: vi.fn(),
  listPatientEventsPublic: vi.fn(),
}))
vi.mock('@/api/uploadWorkbench', () => ({
  listVisitsForDay: (...a: unknown[]) => api.listVisitsForDay(...a),
  resolveRows: (...a: unknown[]) => api.resolveRows(...a),
  preflight: (...a: unknown[]) => api.preflight(...a),
  commitFile: (...a: unknown[]) => api.commitFile(...a),
  undoItem: (...a: unknown[]) => api.undoItem(...a),
  undoJob: (...a: unknown[]) => api.undoJob(...a),
  sha256OfFile: (...a: unknown[]) => api.sha256OfFile(...a),
  searchPatientsPublic: (...a: unknown[]) => api.searchPatientsPublic(...a),
  listPatientEventsPublic: (...a: unknown[]) => api.listPatientEventsPublic(...a),
  localIsoToday: () => '2026-09-18',
}))
vi.mock('@/lib/dicomHeader', () => ({
  readDicomHints: vi.fn().mockResolvedValue({
    laterality: 'OS', acquisitionDate: '2026-09-18', modality: 'OP', modelName: 'CLARUS 700', deviceKey: 'clarus', sopInstanceUid: '1',
  }),
}))

import UploadPortalView from '@/views/UploadPortalView.vue'
import UploadDropzone from '@/components/upload/UploadDropzone.vue'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function mountView() {
  return mount(UploadPortalView, { global: { plugins: [i18n] } })
}

const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])

async function settle(w: ReturnType<typeof mountView>, times = 6): Promise<void> {
  for (let i = 0; i < times; i++) {
    await flushPromises()
    await w.vm.$nextTick()
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  for (const m of Object.values(api)) m.mockReset()
  api.listVisitsForDay.mockResolvedValue(null)
  api.resolveRows.mockResolvedValue({ scans: [] })
  api.preflight.mockResolvedValue({ exists: false, ingestItemId: null, jobId: null })
  api.sha256OfFile.mockImplementation(async (f: File) => `h-${f.name}`.padEnd(64, '0'))
  api.commitFile.mockResolvedValue({ ingestItemId: 12, jobId: null, kind: 'image', format: 'png', status: 'UNBOUND' })
})

describe('UploadPortalView', () => {
  it('opens on the hero dropzone with the batch visit panel, in public mode', async () => {
    const w = mountView()
    await settle(w)
    expect(w.find('h1').text()).toBe('Upload acquisitions')
    expect(w.find('[data-testid="upload-workbench"]').attributes('data-mode')).toBe('public')
    expect(w.find('[data-testid="upload-dropzone-hero"]').exists()).toBe(true)
    expect(w.find('[data-testid="batch-visit"]').exists()).toBe(true)
    // The day list stays away until the institution enables it.
    expect(w.find('[data-testid="todays-visits"]').exists()).toBe(false)
    expect(api.listVisitsForDay).toHaveBeenCalledWith('public', undefined)
  })

  it('a dropped photo becomes a row that names its kind and waits for a patient', async () => {
    const w = mountView()
    await settle(w)
    w.findComponent(UploadDropzone).vm.$emit('files-added', [new File([PNG], 'fundus.png', { type: 'image/png' })])
    await settle(w, 12)

    const row = w.find('[data-row-kind="image"]')
    expect(row.exists()).toBe(true)
    expect(row.attributes('data-row-state')).toBe('nopatient')
    expect(row.find('[data-testid="row-kind"]').text()).toBe('PNG')
    // A photo says nothing about the eye, so the row asks.
    expect(row.find('select[data-testid^="row-laterality-"]').exists()).toBe(true)
    // The hero gives way to the slim strip once something is on the page.
    expect(w.find('[data-testid="upload-dropzone-slim"]').exists()).toBe(true)
  })

  it('a DICOM export shows the eye and date it carries, and no select', async () => {
    const w = mountView()
    await settle(w)
    const bytes = new Uint8Array(512)
    bytes.set([0x44, 0x49, 0x43, 0x4d], 128)
    w.findComponent(UploadDropzone).vm.$emit('files-added', [new File([bytes], 'export.dcm')])
    await settle(w, 12)

    const row = w.find('[data-row-kind="dicom"]')
    expect(row.exists()).toBe(true)
    expect(row.find('[data-testid="row-kind"]').text()).toBe('DICOM')
    expect(row.text()).toContain('OS')
    expect(row.text()).toContain('CLARUS 700')
    expect(row.find('select').exists()).toBe(false)
  })

  it('typing a label resolves it and offers the visit found for it', async () => {
    api.resolveRows.mockResolvedValue({
      scans: [{
        patientId: 'M-001', state: 'suggested',
        candidates: [{
          studyId: 1, studyName: 'HealthAEye', studyOid: 'S_HAE', studySubjectId: 5, subjectLabel: 'M-001', siteName: null,
          matchingEvent: { studyEventId: 99, eventCrfId: 3, definitionLabel: 'V3 Day 90', dateStart: '2026-09-18', matchPolicy: 'exact' },
        }],
      }],
    })
    const w = mountView()
    await settle(w)
    await w.find('#up-patient').setValue('M-001')
    await w.find('#up-patient').trigger('blur')
    await settle(w)

    expect(w.find('[data-testid="resolve-found"]').text()).toContain('M-001')
    await w.find('[data-testid="accept-suggested-visit"]').trigger('click')
    await settle(w)
    expect(w.find('[data-testid="batch-visit-chosen"]').text()).toContain('V3 Day 90')

    // A photo dropped now is filed against that visit straight away.
    w.findComponent(UploadDropzone).vm.$emit('files-added', [new File([PNG], 'fundus.png', { type: 'image/png' })])
    await settle(w, 12)
    const row = w.find('[data-row-kind="image"]')
    expect(row.attributes('data-row-state')).toBe('suggested')
    await row.find('[data-testid^="action-confirm-"]').trigger('click')
    await settle(w)
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    expect(api.commitFile.mock.calls[0][1]).toMatchObject({ eventCrfId: 3, studyEventId: null, patientId: 'M-001' })
  })

  it('a photo can go to the inbox without a visit', async () => {
    const w = mountView()
    await settle(w)
    w.findComponent(UploadDropzone).vm.$emit('files-added', [new File([PNG], 'fundus.png', { type: 'image/png' })])
    await settle(w, 12)
    await w.find('[data-testid^="action-park-"]').trigger('click')
    await settle(w)
    expect(api.commitFile).toHaveBeenCalledTimes(1)
    expect((api.commitFile.mock.calls[0][1] as { studyEventId: unknown }).studyEventId).toBeNull()
    expect(w.find('[data-row-kind="image"]').attributes('data-row-state')).toBe('committed')
  })

  it('an unsupported file is shown refused', async () => {
    const w = mountView()
    await settle(w)
    w.findComponent(UploadDropzone).vm.$emit('files-added', [new File(['nope'], 'notes.txt', { type: 'text/plain' })])
    await settle(w, 12)
    expect(w.find('[data-testid="row-error"]').exists()).toBe(true)
    expect(api.commitFile).not.toHaveBeenCalled()
  })

  it("shows the day's visits when the institution has enabled them, and picks the batch from there", async () => {
    api.listVisitsForDay.mockResolvedValue([
      { studyEventId: 42, studySubjectId: null, subjectLabel: 'M-001', eventLabel: 'V3 Day 90', studyName: 'HealthAEye', time: null },
      { studyEventId: 43, studySubjectId: null, subjectLabel: 'X-777', eventLabel: 'V2 Day 30', studyName: 'HealthAEye', time: '09:30' },
    ])
    const w = mountView()
    await settle(w)
    expect(w.findAll('[data-testid="visit-row"]')).toHaveLength(2)
    await w.find('#up-visit-filter').setValue('x-7')
    await settle(w)
    const rows = w.findAll('[data-testid="visit-row"]')
    expect(rows).toHaveLength(1)
    await rows[0].trigger('click')
    await settle(w)
    expect(w.find('[data-testid="batch-visit-chosen"]').text()).toContain('X-777')
  })
})

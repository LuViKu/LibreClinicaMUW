/**
 * Browser de-identification (layer 1) — the parts a person touches:
 * the preview + confirmation on each upload row, and the disabled CRF
 * attachment widget on the internet-facing deployment.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/uploadWorkbench', () => ({
  listVisitsForDay: vi.fn(async () => []),
  resolveRows: vi.fn(),
  preflight: vi.fn(),
  commitFile: vi.fn(),
  undoItem: vi.fn(),
  undoJob: vi.fn(),
  sha256OfFile: vi.fn(),
  searchPatientsPublic: vi.fn(),
  listPatientEventsPublic: vi.fn(),
  listVisibleSubjectLabels: vi.fn(async () => []),
  localIsoToday: () => '2026-10-06',
}))

import UploadWorkbench from '@/components/upload/UploadWorkbench.vue'
import CrfItemWidget from '@/components/CrfItemWidget.vue'
import { useUploadWorkbenchStore, type UploadRow } from '@/stores/uploadWorkbench'
import { useAuthStore } from '@/stores/auth'
import type { AuthenticatedUser } from '@/types/auth'
import type { CrfItem } from '@/types/crf'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function setDeid(flag: boolean | undefined) {
  useAuthStore().user = { username: 'u', deidentificationRequired: flag } as unknown as AuthenticatedUser
}

function readyRow(over: Partial<UploadRow> = {}): UploadRow {
  return {
    rowId: 'r1',
    file: new File([new Uint8Array(8)], 'scan.e2e'),
    kind: 'e2e',
    format: 'e2e',
    scan: { patientId: '', scanDate: new Date('2024-05-06T12:00:00Z'), laterality: 'OD', scanIndex: 0, nBscans: 25 },
    patientId: 'HAE-042',
    date: '2024-05-06',
    laterality: 'OD',
    state: 'suggested',
    candidates: [],
    selectedCandidate: { studyId: 1, studyName: 'S', studyOid: 'S_1', studySubjectId: 9, subjectLabel: 'HAE-042', siteName: null, matchingEvent: null },
    selectedEvent: { studyEventId: 42, eventCrfId: null, definitionLabel: 'V1', dateStart: '2024-05-06', matchPolicy: 'exact' },
    needsDeidConfirm: true,
    deidConfirmed: false,
    previewUrl: 'blob:preview',
    ...over,
  }
}

async function mountWorkbench(rows: UploadRow[]) {
  const w = mount(UploadWorkbench, {
    props: { mode: 'staff' },
    global: {
      plugins: [i18n],
      stubs: { RouterLink: { template: '<a><slot /></a>' }, BatchVisitPanel: true, PatientSearchModal: true, VisitPickerModal: true, StudyPickerModal: true },
    },
  })
  await flushPromises()
  const store = useUploadWorkbenchStore()
  store.rows.push(...rows)
  await flushPromises()
  return { w, store }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('upload row — preview and confirmation', () => {
  it('shows the banner, the preview and an unticked confirmation; Confirm is disabled until ticked', async () => {
    setDeid(true)
    const { w, store } = await mountWorkbench([readyRow()])
    expect(w.find('[data-testid="deid-banner"]').exists()).toBe(true)
    expect(w.find('[data-testid="deid-preview-r1"]').attributes('src')).toBe('blob:preview')
    const tick = w.find<HTMLInputElement>('[data-testid="deid-confirm-r1"]')
    expect(tick.element.checked).toBe(false)
    const confirm = w.find<HTMLButtonElement>('[data-testid="action-confirm-r1"]')
    expect(confirm.element.disabled).toBe(true)
    const confirmAll = w.find<HTMLButtonElement>('[data-testid="confirm-all"]')
    expect(confirmAll.element.disabled).toBe(true)

    await tick.setValue(true)
    expect(store.rows[0]!.deidConfirmed).toBe(true)
    expect(w.find<HTMLButtonElement>('[data-testid="action-confirm-r1"]').element.disabled).toBe(false)
    expect(w.find<HTMLButtonElement>('[data-testid="confirm-all"]').element.disabled).toBe(false)
  })

  it('says so when no preview can be rendered, and still asks for the confirmation', async () => {
    setDeid(true)
    const { w } = await mountWorkbench([readyRow({ previewUrl: null })])
    expect(w.find('[data-testid="deid-preview-r1"]').exists()).toBe(false)
    expect(w.find('[data-testid="deid-nopreview-r1"]').text()).toContain('No preview')
    expect(w.find('[data-testid="deid-confirm-r1"]').exists()).toBe(true)
  })

  it('offers one batch tick once several rows wait, and it ticks them all', async () => {
    setDeid(true)
    const { w, store } = await mountWorkbench([readyRow(), readyRow({ rowId: 'r2' })])
    await w.find<HTMLInputElement>('[data-testid="deid-confirm-all-previews"]').setValue(true)
    expect(store.rows.every((r) => r.deidConfirmed === true)).toBe(true)
  })

  it('does not offer OU as an eye for a de-identified DICOM row', async () => {
    setDeid(true)
    const { w } = await mountWorkbench([readyRow({ kind: 'dicom', format: 'dicom', scan: undefined, laterality: null })])
    const options = w.findAll('[data-testid="row-laterality-r1"] option').map((o) => o.text())
    expect(options).toEqual(['—', 'OD', 'OS'])
  })

  it('without the flag nothing changes: no banner, no panel, Confirm enabled', async () => {
    setDeid(false)
    const { w } = await mountWorkbench([readyRow({ needsDeidConfirm: undefined, deidConfirmed: undefined, previewUrl: undefined })])
    expect(w.find('[data-testid="deid-banner"]').exists()).toBe(false)
    expect(w.find('[data-testid="deid-panel-r1"]').exists()).toBe(false)
    expect(w.find<HTMLButtonElement>('[data-testid="action-confirm-r1"]').element.disabled).toBe(false)
  })
})

describe('CRF file item — attachments on the internet-facing deployment', () => {
  const item = { oid: 'I_FILE', label: 'Attachment', dataType: 'file', required: false } as unknown as CrfItem

  function mountItem() {
    return mount(CrfItemWidget, {
      global: { plugins: [i18n] },
      props: { item, modelValue: null, suppressLabel: true },
    })
  }

  it('disables the widget and explains why when de-identification is required', () => {
    setDeid(true)
    const w = mountItem()
    expect(w.text()).toContain('File attachments are disabled on this internet-facing deployment')
    for (const b of w.findAll<HTMLButtonElement>('button')) expect(b.element.disabled).toBe(true)
    expect(w.find<HTMLInputElement>('input[type="file"]').element.disabled).toBe(true)
  })

  it('is unchanged otherwise', () => {
    setDeid(false)
    const w = mountItem()
    expect(w.text()).not.toContain('internet-facing')
    expect(w.find<HTMLInputElement>('input[type="file"]').element.disabled).toBe(false)
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'

import de from '@/locales/de.json'

/**
 * P3.4 — the study's imaging catalogue, editable.
 *
 * What this pins is the part an administrator has to be able to see without
 * being told: that a modality accepting no file kinds is a checklist row
 * rather than a broken one, that a retired modality is visibly retired rather
 * than gone, and that retiring asks first — because a retired modality stops
 * ticking its CRF box and nobody watching a form would see why.
 */

const load = vi.fn()
const create = vi.fn()
const retire = vi.fn()
const putBinding = vi.fn()
const removeBinding = vi.fn()
const list = { value: [] as unknown[] }

vi.mock('@/stores/imagingModalities', () => ({
  useImagingModalitiesStore: () => ({
    get list() {
      return list.value
    },
    isLoading: false,
    error: null,
    load,
    create,
    retire,
    putBinding,
    removeBinding,
  }),
}))

const confirmMock = vi.fn()
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

import ImagingModalitiesPanel from '../ImagingModalitiesPanel.vue'

function modality(over: Record<string, unknown> = {}) {
  return {
    id: 1,
    code: 'FUNDUS_OPTOMED',
    labelDe: 'Handheld-Fundus',
    labelEn: 'Handheld fundus',
    device: 'OPTOMEDLUMO',
    kindsAccepted: 'dicom',
    lateralityRequired: true,
    autoMatchAeTitle: 'OPTOMEDLUMO',
    ordinal: 9,
    statusId: 1,
    bindings: [
      { id: 10, role: 'performed', laterality: 'OU', itemOid: 'I_HEALT_OPTOMED_PERFORMED', performedValue: '1' },
    ],
    ...over,
  }
}

function mountPanel(studyOid: string | null = 'S_HAE') {
  const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
  return mount(ImagingModalitiesPanel, {
    props: { studyOid },
    global: { plugins: [i18n] },
  })
}

describe('ImagingModalitiesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    list.value = [modality()]
    confirmMock.mockResolvedValue(true)
  })

  it('loads the catalogue of the bound study', async () => {
    mountPanel('S_HAE')
    await flushPromises()
    expect(load).toHaveBeenCalledWith('S_HAE')
  })

  it('says so rather than loading when no study is bound', async () => {
    const w = mountPanel(null)
    await flushPromises()
    expect(load).not.toHaveBeenCalled()
    expect(w.text()).toContain(de.imagingModalities.noStudy)
  })

  it('shows the acquisition, its device and what it ticks', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('FUNDUS_OPTOMED')
    expect(w.text()).toContain('OPTOMEDLUMO')
    expect(w.text()).toContain('I_HEALT_OPTOMED_PERFORMED')
  })

  /**
   * BCVA is on the visit plan and has a performed box, but no file ever
   * arrives for it. An empty kinds list is that, not a misconfiguration.
   */
  it('a modality that accepts no files reads as checklist-only', async () => {
    list.value = [modality({ kindsAccepted: '' })]
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain(de.imagingModalities.checklistOnly)
  })

  it('a modality with nothing bound says so instead of looking configured', async () => {
    list.value = [modality({ bindings: [] })]
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain(de.imagingModalities.noBindings)
  })

  it('a retired modality stays visible, marked', async () => {
    list.value = [modality({ statusId: 5 })]
    const w = mountPanel()
    await flushPromises()
    // Files filed under it keep naming it; hiding it would make an audit row
    // referencing it unreadable.
    expect(w.text()).toContain('FUNDUS_OPTOMED')
    expect(w.text()).toContain(de.imagingModalities.retired)
  })

  it('confirms before retiring, because the box silently stops being ticked', async () => {
    confirmMock.mockResolvedValue(false)
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="retire-1"]').trigger('click')
    await flushPromises()
    expect(confirmMock).toHaveBeenCalled()
    expect(retire).not.toHaveBeenCalled()
  })

  it('adds an acquisition with the file kinds that were ticked', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="new-code"]').setValue('OCT_SPECTRALIS')
    await w.get('[data-testid="new-label-de"]').setValue('OCT')
    await w.get('[data-testid="new-label-en"]').setValue('OCT')
    await w.get('[data-testid="new-device"]').setValue('spectralis')
    await w.get('input[value="e2e"]').setValue(true)
    await w.get('[data-testid="add-modality"]').trigger('submit')
    await flushPromises()
    expect(create).toHaveBeenCalledWith('S_HAE', expect.objectContaining({
      code: 'OCT_SPECTRALIS',
      device: 'spectralis',
      kindsAccepted: 'e2e',
    }))
  })

  it('saves a binding for the chosen role and eye', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="edit-bindings-1"]').trigger('click')
    await w.get('[data-testid="binding-oid-1"]').setValue('I_HEALT_OCT_PERFORMED')
    await w.get('[data-testid="save-binding-1"]').trigger('click')
    await flushPromises()
    expect(putBinding).toHaveBeenCalledWith('S_HAE', 1, expect.objectContaining({
      role: 'performed',
      laterality: 'OU',
      itemOid: 'I_HEALT_OCT_PERFORMED',
    }))
  })
})

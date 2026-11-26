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
const applyPerformed = vi.fn()
const itemAnswers = vi.fn()
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
    applyPerformed,
    itemAnswers,
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
    itemAnswers.mockResolvedValue({ itemOid: 'X', coded: false, responseType: 'text', answers: [] })
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

  it('asks before ticking "performed" for images already filed, and does nothing on no', async () => {
    confirmMock.mockResolvedValue(false)
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="apply-performed"]').trigger('click')
    await flushPromises()
    expect(confirmMock).toHaveBeenCalled()
    expect(applyPerformed).not.toHaveBeenCalled()
  })

  it('reports what the catch-up over the filed images did', async () => {
    applyPerformed.mockResolvedValue({ files: 176, outcomes: { WRITTEN: 160, ALREADY_SET: 10, NOT_APPLICABLE: 6 } })
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="apply-performed"]').trigger('click')
    await flushPromises()
    expect(applyPerformed).toHaveBeenCalledWith('S_HAE')
    const line = w.get('[data-testid="apply-performed-result"]').text()
    expect(line).toContain('176')
    expect(line).toContain('160')
    expect(line).toContain('10')
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

  /**
   * HealthAEye's "performed" items are coded Y/N; the form used to propose
   * `1`, which none of them has. The answers are offered now, and the one
   * meaning yes is chosen when exactly one does.
   */
  it('offers a coded item\'s answers and picks the one meaning yes', async () => {
    itemAnswers.mockResolvedValue({
      itemOid: 'I_HEALT_CLA_PERFORMED', coded: true, responseType: 'single-select',
      answers: [{ value: 'Y', text: 'Yes' }, { value: 'N', text: 'No' }],
    })
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="edit-bindings-1"]').trigger('click')
    const oid = w.get('[data-testid="binding-oid-1"]')
    await oid.setValue('I_HEALT_CLA_PERFORMED')
    await oid.trigger('change')
    await flushPromises()
    expect(itemAnswers).toHaveBeenCalledWith('S_HAE', 'I_HEALT_CLA_PERFORMED')
    const select = w.get('select[data-testid="binding-value-1"]')
    expect((select.element as HTMLSelectElement).value).toBe('Y')
    expect(select.text()).toContain('Y – Yes')

    await w.get('[data-testid="save-binding-1"]').trigger('click')
    await flushPromises()
    expect(putBinding).toHaveBeenCalledWith('S_HAE', 1, expect.objectContaining({
      itemOid: 'I_HEALT_CLA_PERFORMED',
      performedValue: 'Y',
    }))
  })

  it('does not save a coded binding until an answer is chosen', async () => {
    itemAnswers.mockResolvedValue({
      itemOid: 'I_EYE', coded: true, responseType: 'radio',
      answers: [{ value: '1', text: 'Rechts' }, { value: '2', text: 'Links' }, { value: '3', text: 'Beide' }],
    })
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="edit-bindings-1"]').trigger('click')
    const oid = w.get('[data-testid="binding-oid-1"]')
    await oid.setValue('I_EYE')
    await oid.trigger('change')
    await flushPromises()
    const save = w.get('[data-testid="save-binding-1"]')
    expect(save.attributes('disabled')).toBeDefined()
    expect(putBinding).not.toHaveBeenCalled()

    await w.get('select[data-testid="binding-value-1"]').setValue('3')
    expect(save.attributes('disabled')).toBeUndefined()
  })

  it('keeps a text field for a free-text item', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="edit-bindings-1"]').trigger('click')
    const oid = w.get('[data-testid="binding-oid-1"]')
    await oid.setValue('I_BLOOD_PRESSURE_SYS')
    await oid.trigger('change')
    await flushPromises()
    expect(w.find('input[data-testid="binding-value-1"]').exists()).toBe(true)
    expect(w.find('select[data-testid="binding-value-1"]').exists()).toBe(false)
  })

  it('says so when no CRF carries the item', async () => {
    itemAnswers.mockRejectedValue(new Error('404'))
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="edit-bindings-1"]').trigger('click')
    const oid = w.get('[data-testid="binding-oid-1"]')
    await oid.setValue('I_TYPO')
    await oid.trigger('change')
    await flushPromises()
    expect(w.get('[data-testid="binding-item-missing-1"]').text()).toBe(de.imagingModalities.itemNotFound)
  })

  it('flags a binding whose value is no answer of its item', async () => {
    list.value = [modality({
      bindings: [{ id: 10, role: 'performed', laterality: 'OU', itemOid: 'I_HEALT_OCT_PERFORMED',
        performedValue: '1', valueValid: false }],
    })]
    const w = mountPanel()
    await flushPromises()
    expect(w.get('[data-testid="binding-invalid-10"]').text()).toBe(de.imagingModalities.valueInvalid)
    expect(w.text()).toContain('= 1')
  })

  it('reports the images a binding with an unusable value could not tick', async () => {
    applyPerformed.mockResolvedValue({ files: 20, outcomes: { WRITTEN: 5, INVALID_VALUE: 7 } })
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="apply-performed"]').trigger('click')
    await flushPromises()
    const line = w.get('[data-testid="apply-performed-result"]').text()
    expect(line).toContain('7 nicht gesetzt')
  })
})

/**
 * Phase E.6 — ModalitiesView spec.
 *
 * Verifies:
 *   - table renders rows from the store
 *   - "Neue Modalität" button opens the create dialog
 *   - per-row Edit button populates the dialog with the existing row
 *   - per-row Delete button fires a confirm prompt + then store.remove
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
  }
})

import { apiGet, apiDelete } from '@/api/client'
import ModalitiesView from '@/views/ModalitiesView.vue'
import { useModalitiesStore } from '@/stores/modalities'
import type { Modality } from '@/types/modality'

// Lightweight i18n stub — the view references modalities.* keys the
// i18n worktree owns; using the silent fallback formatter avoids
// hard-coding strings here.
const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { loading: 'Loading…', saving: 'Saving…', cancel: 'Cancel' },
      nav: { buildStudy: 'Build study' },
      modalities: {
        title: 'Modalities',
        columns: {
          code: 'Code', labelDe: 'Label DE', labelEn: 'Label EN',
          oidOd: 'OD OID', oidOs: 'OS OID', type: 'Type', unit: 'Unit',
          ordinal: 'Ordinal', actions: 'Actions',
        },
        action: {
          new: 'Neue Modalität', edit: 'Bearbeiten', delete: 'Entfernen',
          deleteConfirm: 'Delete {code}?',
        },
        dialog: {
          titleNew: 'New modality', titleEdit: 'Edit modality',
          field: {
            code: 'Code', labelEn: 'Label EN', labelDe: 'Label DE',
            ordinal: 'Ordinal', itemOidOd: 'OD OID', itemOidOs: 'OS OID',
            dataType: 'Data type', unit: 'Unit',
          },
        },
        error: {
          duplicateCode: 'Code already exists',
          unknownOid: 'Unknown OID',
          missingOid: 'OD or OS OID required',
          network: 'Backend unreachable',
        },
      },
    },
  },
})

const FIXTURE: Modality[] = [
  {
    modalityId: 11,
    code: 'VA',
    labelEn: 'Visual acuity',
    labelDe: 'Visus',
    ordinal: 10,
    itemOidOd: 'I_VA_OD',
    itemOidOs: 'I_VA_OS',
    dataType: 'numeric',
    unit: 'logMAR',
  },
  {
    modalityId: 22,
    code: 'IOP',
    labelEn: 'Intraocular pressure',
    labelDe: 'Augeninnendruck',
    ordinal: 20,
    itemOidOd: 'I_IOP_OD',
    itemOidOs: 'I_IOP_OS',
    dataType: 'numeric',
    unit: 'mmHg',
  },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/build-study', name: 'build-study', component: { template: '<div />' } },
      { path: '/modalities', name: 'modalities', component: { template: '<div />' } },
    ],
  })
}

async function mountView() {
  vi.mocked(apiGet).mockResolvedValue(FIXTURE)
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = makeRouter()
  const wrapper = mount(ModalitiesView, {
    global: { plugins: [pinia, router, i18n] },
    attachTo: document.body,
  })
  // Let onMounted -> store.load() complete.
  await flushPromises()
  await nextTick()
  return wrapper
}

describe('ModalitiesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiDelete).mockReset()
    document.body.innerHTML = ''
  })

  it('renders one row per modality from the store', async () => {
    const wrapper = await mountView()
    const row1 = wrapper.find('[data-testid="modality-row-11"]')
    const row2 = wrapper.find('[data-testid="modality-row-22"]')
    expect(row1.exists()).toBe(true)
    expect(row2.exists()).toBe(true)
    expect(row1.text()).toContain('VA')
    expect(row1.text()).toContain('Visus')
    expect(row1.text()).toContain('I_VA_OD')
    expect(row2.text()).toContain('IOP')
    wrapper.unmount()
  })

  it('clicking "Neue Modalität" opens the dialog in create mode', async () => {
    const wrapper = await mountView()
    expect(document.body.querySelector('#modality-edit-title')).toBeNull()

    await wrapper.find('[data-testid="modalities-new"]').trigger('click')
    await flushPromises()

    const title = document.body.querySelector('#modality-edit-title')
    expect(title).not.toBeNull()
    // Empty form — code field is empty.
    const codeInput = document.body.querySelector('#modality-code') as HTMLInputElement
    expect(codeInput).not.toBeNull()
    expect(codeInput.value).toBe('')
    // Not readonly in create mode.
    expect(codeInput.readOnly).toBe(false)
    wrapper.unmount()
  })

  it('clicking per-row Edit populates the dialog with the existing row', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="modality-edit-11"]').trigger('click')
    await flushPromises()

    const codeInput = document.body.querySelector('#modality-code') as HTMLInputElement
    expect(codeInput.value).toBe('VA')
    // Code is readonly in edit mode.
    expect(codeInput.readOnly).toBe(true)
    const labelDe = document.body.querySelector('#modality-label-de') as HTMLInputElement
    expect(labelDe.value).toBe('Visus')
    wrapper.unmount()
  })

  it('Delete button fires confirm() then store.remove + DELETEs the right path', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(apiDelete).mockResolvedValueOnce(undefined as never)
    // load() called twice — initial onMounted, then post-delete refresh.
    vi.mocked(apiGet).mockResolvedValue([FIXTURE[1]])

    const wrapper = await mountView()
    await wrapper.find('[data-testid="modality-delete-11"]').trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(apiDelete).toHaveBeenCalledWith('/pages/api/v1/modalities/11')

    confirmSpy.mockRestore()
    wrapper.unmount()
  })

  it('cancelled confirm() skips the DELETE call', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = await mountView()
    await wrapper.find('[data-testid="modality-delete-11"]').trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(apiDelete).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
    wrapper.unmount()
  })

  it('Delete uses the store action — store.remove is observable via the wire', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(apiDelete).mockResolvedValueOnce(undefined as never)
    vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE).mockResolvedValueOnce([FIXTURE[1]])

    const wrapper = await mountView()
    const store = useModalitiesStore()
    expect(store.list.length).toBe(2)
    await wrapper.find('[data-testid="modality-delete-11"]').trigger('click')
    await flushPromises()
    // The post-DELETE load lands a 1-row list — confirms the wire went through.
    expect(store.list.length).toBe(1)

    confirmSpy.mockRestore()
    wrapper.unmount()
  })
})

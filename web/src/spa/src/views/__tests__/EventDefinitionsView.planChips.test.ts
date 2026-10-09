/**
 * DR-039 — the visit imaging plan offers inference tasks only on a modality
 * marked as an OCT one (`oct` in kindsAccepted). A fundus camera that also
 * exports DICOM gets no task chips.
 */
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiPatch: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
  },
  ApiNetworkError: class ApiNetworkError extends Error {},
}))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => () => Promise.resolve(true) }))

// eslint-disable-next-line import/first
import { apiGet } from '@/api/client'
// eslint-disable-next-line import/first
import EventDefinitionsView from '@/views/EventDefinitionsView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'
// eslint-disable-next-line import/first
import type { AuthenticatedUser } from '@/types/auth'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function modality(id: number, code: string, device: string, kindsAccepted: string) {
  return {
    id, code, labelDe: code, labelEn: code, device, kindsAccepted, lateralityRequired: true,
    autoMatchAeTitle: null, ordinal: id, statusId: 1, bindings: [],
  }
}

const MODALITIES = [
  modality(1, 'OCT_SPECTRALIS', 'spectralis', 'e2e,oct'),
  modality(2, 'FUNDUS_CLARUS', 'clarus', 'dicom,image'),
  modality(3, 'OCT_CIRRUS', 'cirrus', 'dicom,oct'),
]

describe('EventDefinitionsView — imaging plan task chips (DR-039)', () => {
  it('shows task chips only for modalities marked oct', async () => {
    ;(apiGet as unknown as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url.includes('/imaging-modalities')) return Promise.resolve({ modalities: MODALITIES })
      if (url.endsWith('/imaging-plan')) return Promise.resolve({ entries: [] })
      if (url.endsWith('/retinal-tasks')) return Promise.resolve({ tasks: [] })
      if (url.includes('/event-definitions')) {
        return Promise.resolve([{ sedId: 2, oid: 'SE_V2', name: 'Day 30', description: '', category: '',
          type: 'scheduled', repeating: false, ordinal: 1, status: 'available' }])
      }
      return Promise.resolve([])
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().user = {
      username: 'u', displayName: 'U', email: null, role: 'Data Manager', siteLabel: null, source: 'local',
      mfaSatisfied: true, profileComplete: true, locale: null, timezone: null,
      activeStudy: { id: 1, oid: 'S_1', name: 'Study', isSite: false, role: 'Data Manager', roles: ['Data Manager'] },
    } as unknown as AuthenticatedUser
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    const w = mount(EventDefinitionsView, { global: { plugins: [pinia, router, i18n] } })
    await flushPromises()

    const edit = w.findAll('button').find((b) => b.text() === enMessages.eventDefinitions.edit
      || b.text().toLowerCase() === 'edit')
    expect(edit, w.html()).toBeTruthy()
    await edit!.trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="ed-edit-plan-OCT_SPECTRALIS-task-fluid"]').exists()).toBe(true)
    expect(w.find('[data-testid="ed-edit-plan-OCT_CIRRUS-task-fluid"]').exists()).toBe(true)
    expect(w.find('[data-testid="ed-edit-plan-FUNDUS_CLARUS"]').exists()).toBe(true)
    expect(w.find('[data-testid="ed-edit-plan-FUNDUS_CLARUS-task-fluid"]').exists()).toBe(false)
  })
})

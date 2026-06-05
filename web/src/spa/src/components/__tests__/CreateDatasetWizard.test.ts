/**
 * Phase E.6 — Data Export Phase 2 — CreateDatasetWizard spec.
 *
 * Smokes the step-flow + the draft-survives-step-navigation behaviour.
 * Mounts the wizard against a hydrated datasets store, so we exercise
 * the {@code setStep} round-trip without standing up the actual
 * backend.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

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

import { apiGet } from '@/api/client'
import CreateDatasetWizard from '@/components/CreateDatasetWizard.vue'
import { useAuthStore } from '@/stores/auth'
import { useDatasetsStore } from '@/stores/datasets'
import { FLAG_DEFAULTS, type EventTreeNode } from '@/types/export'
import enMessages from '@/locales/en.json'

const TREE: EventTreeNode[] = [
  {
    eventOid: 'SE_BASELINE',
    eventName: 'Baseline',
    eventOrdinal: 1,
    repeating: false,
    crfs: [
      {
        crfOid: 'F_VITAL',
        crfName: 'Vital signs',
        versions: [
          {
            versionId: 10,
            versionOid: 'F_VITAL_V1',
            versionName: 'Vitals v1',
            items: [
              { itemId: 100, oid: 'I_HR', name: 'Heart rate', dataType: 'number' },
            ],
          },
        ],
      },
    ],
  },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/datasets/new', name: 'dataset-new', component: { template: '<div />' } },
      { path: '/datasets', name: 'datasets', component: { template: '<div />' } },
      { path: '/pick-study', name: 'pick-study', component: { template: '<div />' } },
    ],
  })
}

function seedAuth() {
  const auth = useAuthStore()
  auth.user = {
    username: 'tester',
    displayName: 'Tester',
    email: null,
    role: 'Data Manager',
    siteLabel: null,
    profileComplete: true,
    activeStudy: { id: 1, oid: 'S_DEMO', name: 'Demo', site: null },
  } as unknown as ReturnType<typeof useAuthStore>['user']
  auth.state = 'authenticated'
}

async function mountWizard() {
  const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
  const router = makeRouter()
  router.push({ name: 'dataset-new' })
  await router.isReady()

  const wrapper = mount(CreateDatasetWizard, {
    global: { plugins: [router, i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('CreateDatasetWizard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiGet).mockImplementation((path: string) => {
      if (path.includes('/event-tree')) return Promise.resolve(TREE) as Promise<unknown>
      return Promise.resolve(null) as Promise<unknown>
    })
    seedAuth()
  })

  it('starts at step 0 with a blank draft', async () => {
    const wrapper = await mountWizard()
    const datasets = useDatasetsStore()
    expect(datasets.draft).not.toBeNull()
    expect(datasets.draft?.step).toBe(0)
    expect(datasets.draft?.name).toBe('')
    // Scope step shows the dataset-name field.
    expect(wrapper.find('#dataset-name').exists()).toBe(true)
  })

  it('blocks advance when scope is incomplete', async () => {
    const wrapper = await mountWizard()
    const datasets = useDatasetsStore()
    // The "Next" button targets step 1, but should refuse to move
    // forward without a name + selection.
    const nextBtn = wrapper
      .findAll('button')
      .find((b) => b.text().trim() === 'Next')
    expect(nextBtn).toBeTruthy()
    await nextBtn!.trigger('click')
    expect(datasets.draft?.step).toBe(0)
    expect(wrapper.text()).toContain('Dataset name is required')
  })

  it('advances + remembers state across steps', async () => {
    const wrapper = await mountWizard()
    const datasets = useDatasetsStore()
    datasets.patchDraft({
      name: 'Baseline cohort',
      eventDefinitionOids: ['SE_BASELINE'],
      crfVersionIds: [10],
      itemIds: [100],
    })
    await flushPromises()

    const nextBtn = wrapper
      .findAll('button')
      .find((b) => b.text().trim() === 'Next')
    await nextBtn!.trigger('click')
    expect(datasets.draft?.step).toBe(1)
    expect(wrapper.text()).toContain('Inclusion flags')

    await nextBtn!.trigger('click')
    expect(datasets.draft?.step).toBe(2)
    // Phase 3 FilterBuilder now lives where PR #114 had a placeholder.
    expect(wrapper.text()).toContain('No filters yet')

    await nextBtn!.trigger('click')
    expect(datasets.draft?.step).toBe(3)
    expect(wrapper.text()).toContain('Review')
    // Draft state preserved.
    expect(datasets.draft?.name).toBe('Baseline cohort')
    expect(datasets.draft?.itemIds).toEqual([100])
    // Defaults still applied.
    expect(datasets.draft?.includeFlags.gender).toBe(FLAG_DEFAULTS.gender)
  })

  it('Back button decrements the step', async () => {
    const wrapper = await mountWizard()
    const datasets = useDatasetsStore()
    datasets.setStep(2)
    await flushPromises()
    const backBtn = wrapper
      .findAll('button')
      .find((b) => b.text().trim() === 'Back')
    await backBtn!.trigger('click')
    expect(datasets.draft?.step).toBe(1)
  })
})

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn().mockResolvedValue([]),
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

import EventDefinitionsView from '@/views/EventDefinitionsView.vue'
import GroupClassesView from '@/views/GroupClassesView.vue'
import RulesView from '@/views/RulesView.vue'
import DatasetListView from '@/views/DatasetListView.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'
import type { AuthenticatedUser } from '@/types/auth'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

type Role = AuthenticatedUser['role']

/**
 * The create control of each study-build page shows exactly for the roles the
 * backend gate admits: userMayEditStudy (director, coordinator, sysadmin) for
 * event definitions, group classes and rules; roleMayEditExports (those and
 * the monitor) for datasets.
 */
async function mountAs(view: object, role: Role) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    username: 'u', displayName: 'U', email: null, role, siteLabel: null, source: 'local',
    mfaSatisfied: true, profileComplete: true, locale: null, timezone: null,
    activeStudy: { id: 1, oid: 'S_1', name: 'Study', isSite: false, role, roles: [role] },
  } as unknown as AuthenticatedUser
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  const w = mount(view, { global: { plugins: [pinia, router, i18n] } })
  await flushPromises()
  return w
}

const BUILD_PAGES: Array<[string, object, string]> = [
  ['EventDefinitionsView', EventDefinitionsView, enMessages.eventDefinitions.createAction],
  ['GroupClassesView', GroupClassesView, enMessages.groupClasses.createAction],
  ['RulesView', RulesView, enMessages.rules.create.button.new],
]

describe('study-build pages: write controls by role', () => {
  beforeEach(() => vi.clearAllMocks())

  for (const [name, view, label] of BUILD_PAGES) {
    it(`${name}: CRC, Data Manager and Administrator get the create control, a Monitor does not`, async () => {
      for (const role of ['CRC', 'Data Manager', 'Administrator'] as const) {
        expect((await mountAs(view, role)).text(), `${name} ${role}`).toContain(label)
      }
      expect((await mountAs(view, 'Monitor')).text(), `${name} Monitor`).not.toContain(label)
    })
  }

  it('DatasetListView: CRC and Monitor get the new-dataset control, an Investigator does not', async () => {
    for (const role of ['CRC', 'Monitor', 'Data Manager'] as const) {
      expect((await mountAs(DatasetListView, role)).find('[data-testid="dataset-new-button"]').exists(), role).toBe(true)
    }
    expect((await mountAs(DatasetListView, 'Investigator')).find('[data-testid="dataset-new-button"]').exists()).toBe(false)
  })
})

/**
 * CrfLibraryView — the library's CRF and version actions.
 *
 * Mounted with the real English messages so the confirmations say what the
 * server does: removing a CRF or a version takes the rows below it, and
 * Restore brings them back.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import en from '@/locales/en.json'

const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn(() => Promise.resolve(true)) }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

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

import { ApiError, apiGet, apiPost, apiPut } from '@/api/client'
import CrfLibraryView from '@/views/CrfLibraryView.vue'
import { useAuthStore } from '@/stores/auth'
import type { Crf, CrfVersion } from '@/types/crfLibrary'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en },
})

const V1: CrfVersion = {
  oid: 'F_DEMO_V1',
  name: 'v1.0',
  description: '',
  revisionNotes: '',
  status: 'available',
  uploadedAt: '2026-06-01T00:00:00Z',
}
const ACTIVE: Crf = { oid: 'F_DEMO', name: 'Demographics', description: '', status: 'available', versions: [V1] }
const REMOVED: Crf = {
  oid: 'F_OLD',
  name: 'Old form',
  description: '',
  status: 'removed',
  versions: [{ ...V1, oid: 'F_OLD_V1', status: 'auto-removed' }],
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/crf-library', name: 'crf-library', component: { template: '<div />' } },
      { path: '/crf-library/:crfOid', name: 'crf-detail', component: { template: '<div />' } },
      { path: '/crf-authoring-canvas/:crfOid', name: 'crfAuthoringCanvas', component: { template: '<div />' } },
    ],
  })
}

async function mountView(role: 'Data Manager' | 'Investigator' = 'Data Manager'): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    username: 'dm',
    displayName: 'Demo DM',
    email: null,
    role,
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: null,
    timezone: null,
    activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false, settings: {} },
  } as never
  vi.mocked(apiGet).mockResolvedValue([ACTIVE])
  const wrapper = mount(CrfLibraryView, {
    global: { plugins: [pinia, makeRouter(), i18n], stubs: { BuildStudyRail: true } },
  })
  await flushPromises()
  return wrapper
}

/** Ticks "Include removed", which reloads the list with the removed CRFs. */
async function showRemoved(wrapper: VueWrapper, rows: Crf[]): Promise<void> {
  vi.mocked(apiGet).mockResolvedValue(rows)
  await wrapper.find('input[type="checkbox"]').setValue(true)
  await flushPromises()
}

describe('CrfLibraryView', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPut).mockReset()
    confirmMock.mockReset()
    confirmMock.mockResolvedValue(true)
  })

  it('says, before removing a CRF, that its versions, assignments and event CRFs go with it', async () => {
    const wrapper = await mountView()
    vi.mocked(apiPost).mockResolvedValue({ ...ACTIVE, status: 'removed' })

    await wrapper.find('[data-testid="crf-library-disable-F_DEMO"]').trigger('click')
    await flushPromises()

    const message = (confirmMock.mock.calls[0] as unknown as [{ message: string }])[0].message
    expect(message).toContain('every event CRF entered on it')
    expect(message).toContain('Restore brings them back')
    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/crfs/F_DEMO/disable', {})
  })

  it('offers Restore on a removed CRF only, and restores it after confirming', async () => {
    const wrapper = await mountView()
    await showRemoved(wrapper, [ACTIVE, REMOVED])

    expect(wrapper.find('[data-testid="crf-library-restore-F_OLD"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="crf-library-disable-F_OLD"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="crf-library-restore-F_DEMO"]').exists()).toBe(false)

    vi.mocked(apiPost).mockResolvedValue({ ...REMOVED, status: 'available', versions: [{ ...V1, oid: 'F_OLD_V1' }] })
    await wrapper.find('[data-testid="crf-library-restore-F_OLD"]').trigger('click')
    await flushPromises()

    const message = (confirmMock.mock.calls[0] as unknown as [{ message: string }])[0].message
    expect(message).toContain('at the status they had')
    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/crfs/F_OLD/restore', {})
    expect(wrapper.find('[data-testid="crf-library-restore-F_OLD"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="crf-library-disable-F_OLD"]').exists()).toBe(true)
  })

  it('does not restore when the confirmation is declined', async () => {
    const wrapper = await mountView()
    await showRemoved(wrapper, [REMOVED])
    confirmMock.mockResolvedValue(false)

    await wrapper.find('[data-testid="crf-library-restore-F_OLD"]').trigger('click')
    await flushPromises()

    expect(apiPost).not.toHaveBeenCalled()
  })

  it('shows a removed CRF\'s versions as removed with it, without version actions', async () => {
    const wrapper = await mountView()
    await showRemoved(wrapper, [REMOVED])

    const text = wrapper.text()
    expect(text).toContain('Removed with the CRF')
    const buttons = wrapper.findAll('button').map((b) => b.text())
    expect(buttons).not.toContain('Lock')
    expect(buttons.filter((b) => b === 'Restore')).toHaveLength(1)
    expect(buttons).not.toContain('Remove')
  })

  it('edits name and description in place and saves the trimmed values', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="crf-library-edit-F_DEMO"]').trigger('click')

    const name = wrapper.find('#crf-edit-name-F_DEMO')
    expect((name.element as HTMLInputElement).value).toBe('Demographics')
    await name.setValue('  Demographics II  ')
    await wrapper.find('#crf-edit-desc-F_DEMO').setValue(' Baseline form ')
    vi.mocked(apiPut).mockResolvedValue({ ...ACTIVE, name: 'Demographics II', description: 'Baseline form' })
    await wrapper.find('[data-testid="crf-library-edit-save"]').trigger('click')
    await flushPromises()

    expect(apiPut).toHaveBeenCalledWith('/pages/api/v1/crfs/F_DEMO', {
      name: 'Demographics II',
      description: 'Baseline form',
    })
    expect(wrapper.find('[data-testid="crf-library-edit-form-F_DEMO"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Demographics II')
  })

  it('shows the server\'s answer when the edit is refused', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="crf-library-edit-F_DEMO"]').trigger('click')
    vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(400, 'Bad Request', {
      message: 'Validation failed',
      errors: [{ field: 'name', message: "A CRF named 'AE' already exists" }],
    }))
    await wrapper.find('#crf-edit-name-F_DEMO').setValue('AE')
    await wrapper.find('[data-testid="crf-library-edit-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain("A CRF named 'AE' already exists")

    vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(403, 'Forbidden', {
      message: "Only the CRF's owner, as Data Manager or study administrator, or a system administrator may change a CRF's name and description",
    }))
    await wrapper.find('[data-testid="crf-library-edit-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain("Only the CRF's owner")
    expect(wrapper.find('[data-testid="crf-library-edit-form-F_DEMO"]').exists()).toBe(true)
  })

  it('previews a published version read-only, without forking it into the builder', async () => {
    const wrapper = await mountView()
    vi.mocked(apiGet).mockImplementation(async (url: string) => {
      if (url.endsWith('/contents')) {
        return {
          versionName: '',
          versionDescription: '',
          revisionNotes: '',
          sections: [{
            label: 'S_VITALS',
            title: 'Vitals',
            instructions: '',
            ordinal: 1,
            items: [{ name: 'HEIGHT', oid: 'I_HEIGHT', descriptionLabel: 'Body height', dataType: 'INT' }],
          }],
          groups: [],
        }
      }
      return []
    })

    await wrapper.find('[data-testid="crf-library-preview-F_DEMO_V1"]').trigger('click')
    await flushPromises()

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/crfs/F_DEMO/versions/F_DEMO_V1/contents')
    const preview = wrapper.find('[data-testid="crf-preview-root"]')
    expect(preview.exists()).toBe(true)
    expect(preview.text()).toContain('Preview · Demographics · v1.0')
    expect(preview.text()).toContain('Body height')
    expect(preview.text()).toContain('no data is persisted')
    expect(apiPost).not.toHaveBeenCalled()

    await wrapper.find('[data-testid="crf-preview-close"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="crf-preview-root"]').exists()).toBe(false)
  })

  it('says so when a version cannot be loaded for the preview', async () => {
    const wrapper = await mountView()
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, 'Server Error', { message: 'Version contents load failed' }))

    await wrapper.find('[data-testid="crf-library-preview-F_DEMO_V1"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="crf-library-action-error"]').text()).toContain('Version contents load failed')
    expect(wrapper.find('[data-testid="crf-preview-root"]').exists()).toBe(false)
  })

  it('links each CRF to its view', async () => {
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="crf-library-detail-F_DEMO"]').attributes('href')).toBe('/crf-library/F_DEMO')
  })

  it('offers no CRF actions to a role that may not manage CRFs', async () => {
    const wrapper = await mountView('Investigator')
    await showRemoved(wrapper, [ACTIVE, REMOVED])

    expect(wrapper.find('[data-testid="crf-library-restore-F_OLD"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="crf-library-disable-F_DEMO"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="crf-library-edit-F_DEMO"]').exists()).toBe(false)
  })
})

/**
 * Multi-role per (user, study) — UserRolesDialog spec.
 *
 * Pins the load-bearing wire contract for the per-study multi-select
 * surface: the dialog groups single-role bindings by study, exposes a
 * checkbox-per-role group, and on Save invokes
 * {@link useUsersStore.setStudyRoles} with the union of checked roles.
 *
 * Notes:
 *   - {@code RoleDots} is owned by the sibling M2 slice; we stub it
 *     to a `<div data-testid="role-dots" :data-roles="…">` so this
 *     test stays unblocked.
 *   - Auth store's `availableStudies` is seeded with the test studies
 *     so the "Add study" picker has something to render.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
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

import { apiGet, apiPut } from '@/api/client'
import UserRolesDialog from '@/components/UserRolesDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useUsersStore } from '@/stores/users'
import type { RoleBinding, StudyUser } from '@/types/user'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

const USER: StudyUser = {
  id: '7',
  username: 'alice',
  displayName: 'Alice Smith',
  email: 'alice@example.org',
  role: 'Investigator',
  siteLabel: null,
  auth: 'local',
  lastLoginAt: null,
  active: true,
  locked: false,
}

const BINDING_S1_INV: RoleBinding = {
  studyId: 1,
  studyOid: 'S1',
  studyName: 'Study One',
  siteLabel: null,
  role: 'Investigator',
  active: true,
}
const BINDING_S1_DM: RoleBinding = { ...BINDING_S1_INV, role: 'Data Manager' }

function seedAuth() {
  const auth = useAuthStore()
  // The StudyOption type carries extra openapi-generated fields the
  // tests don't need; cast through unknown to keep the fixture compact.
  const studies = [
    { id: 1, oid: 'S1', name: 'Study One', parentOid: null, parentName: null, role: 'Administrator' },
    { id: 2, oid: 'S2', name: 'Study Two', parentOid: null, parentName: null, role: 'Administrator' },
  ] as unknown as ReturnType<typeof useAuthStore>['availableStudies']
  auth.availableStudies = studies
}

function mountDialog() {
  return mount(UserRolesDialog, {
    props: { open: true, user: USER },
    global: {
      plugins: [i18n],
      stubs: {
        // RoleDots is owned by the M2 slice — stub to expose the
        // roles via a data-attribute the test can assert against.
        RoleDots: {
          props: ['roles'],
          template: '<div data-testid="role-dots" :data-roles="(roles||[]).join(\',\')"></div>',
        },
      },
    },
    attachTo: document.body,
  })
}

function checkboxFor(roleLabel: string): HTMLInputElement | null {
  // Find label by text, then its first input descendant.
  const labels = Array.from(document.body.querySelectorAll('label')) as HTMLLabelElement[]
  const lbl = labels.find((l) => l.textContent?.trim() === roleLabel)
  if (!lbl) return null
  return lbl.querySelector('input[type="checkbox"]') as HTMLInputElement | null
}

function clickSaveOnFirstRow() {
  const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
  const save = buttons.find((b) => b.textContent?.trim() === 'Save')
  if (save) save.click()
}

describe('UserRolesDialog — per-study multi-select', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedAuth()
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPut).mockReset()
  })

  it('renders one row per study and forwards the merged role list to RoleDots', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([BINDING_S1_INV, BINDING_S1_DM])

    const wrapper = mountDialog()
    await flushPromises()
    await nextTick()

    const dots = document.body.querySelector('[data-testid="role-dots"]') as HTMLElement | null
    expect(dots).not.toBeNull()
    const roles = (dots?.getAttribute('data-roles') ?? '').split(',').sort()
    expect(roles).toEqual(['Data Manager', 'Investigator'])

    // Only ONE row, because both bindings collapse to a single study.
    const rows = document.body.querySelectorAll('[data-testid^="role-row-"]')
    expect(rows.length).toBe(1)
    wrapper.unmount()
  })

  it('unticking a role then Save calls setStudyRoles with the surviving roles only', async () => {
    vi.mocked(apiGet)
      .mockResolvedValueOnce([BINDING_S1_INV, BINDING_S1_DM])
      .mockResolvedValueOnce([BINDING_S1_INV])
    vi.mocked(apiPut).mockResolvedValueOnce([BINDING_S1_INV])

    const wrapper = mountDialog()
    await flushPromises()
    await nextTick()

    // Untick Data Manager.
    const dmBox = checkboxFor('Study Director')!
    expect(dmBox.checked).toBe(true)
    dmBox.checked = false
    dmBox.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()

    clickSaveOnFirstRow()
    await flushPromises()

    expect(apiPut).toHaveBeenCalledTimes(1)
    const [path, payload] = vi.mocked(apiPut).mock.calls[0] as [string, { roles: string[] }]
    expect(path).toBe('/pages/api/v1/users/alice/roles/S1')
    expect(payload.roles.sort()).toEqual(['Investigator'])
    wrapper.unmount()
  })

  it('ticking an additional role then Save calls setStudyRoles with the union', async () => {
    vi.mocked(apiGet)
      .mockResolvedValueOnce([BINDING_S1_INV, BINDING_S1_DM])
      .mockResolvedValueOnce([BINDING_S1_INV, BINDING_S1_DM, { ...BINDING_S1_INV, role: 'Monitor' }])
    vi.mocked(apiPut).mockResolvedValueOnce([
      BINDING_S1_INV,
      BINDING_S1_DM,
      { ...BINDING_S1_INV, role: 'Monitor' },
    ])

    const wrapper = mountDialog()
    await flushPromises()
    await nextTick()

    const monitorBox = checkboxFor('Monitor')!
    expect(monitorBox.checked).toBe(false)
    monitorBox.checked = true
    monitorBox.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()

    clickSaveOnFirstRow()
    await flushPromises()

    expect(apiPut).toHaveBeenCalledTimes(1)
    const [path, payload] = vi.mocked(apiPut).mock.calls[0] as [string, { roles: string[] }]
    expect(path).toBe('/pages/api/v1/users/alice/roles/S1')
    expect(payload.roles.sort()).toEqual(['Data Manager', 'Investigator', 'Monitor'])
    wrapper.unmount()
  })

  it('does NOT offer Administrator as a per-study checkbox', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([BINDING_S1_INV])
    const wrapper = mountDialog()
    await flushPromises()
    await nextTick()
    // No checkbox label matches "Administrator".
    expect(checkboxFor('Administrator')).toBeNull()
    wrapper.unmount()
  })

  it('Save button stays hidden until the row is dirty', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([BINDING_S1_INV, BINDING_S1_DM])
    const wrapper = mountDialog()
    await flushPromises()
    await nextTick()

    const buttons = () => Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    expect(buttons().some((b) => b.textContent?.trim() === 'Save')).toBe(false)

    const dmBox = checkboxFor('Study Director')!
    dmBox.checked = false
    dmBox.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()

    expect(buttons().some((b) => b.textContent?.trim() === 'Save')).toBe(true)
    wrapper.unmount()
  })

  it('Remove study confirms then calls setStudyRoles with []', async () => {
    vi.mocked(apiGet)
      .mockResolvedValueOnce([BINDING_S1_INV, BINDING_S1_DM])
      .mockResolvedValueOnce([])
    vi.mocked(apiPut).mockResolvedValueOnce([])
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    const wrapper = mountDialog()
    await flushPromises()
    await nextTick()

    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const removeBtn = buttons.find((b) => b.textContent?.trim() === 'Remove study')!
    expect(removeBtn).toBeTruthy()
    removeBtn.click()
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalled()
    expect(apiPut).toHaveBeenCalledTimes(1)
    const [, payload] = vi.mocked(apiPut).mock.calls[0] as [string, { roles: string[] }]
    expect(payload.roles).toEqual([])

    confirmSpy.mockRestore()
    wrapper.unmount()
  })

  it('Add study path calls setStudyRoles for the chosen study + checked roles', async () => {
    // Empty initial bindings — so Study One AND Study Two are both available.
    vi.mocked(apiGet)
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([BINDING_S1_INV])
    vi.mocked(apiPut).mockResolvedValueOnce([BINDING_S1_INV])

    const wrapper = mountDialog()
    await flushPromises()
    await nextTick()

    // Pick study S1 in the picker.
    const studyPicker = document.body.querySelector('#add-role-study') as HTMLSelectElement
    expect(studyPicker).not.toBeNull()
    studyPicker.value = 'S1'
    studyPicker.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()

    // Tick Investigator.
    const invBox = checkboxFor('Investigator')!
    invBox.checked = true
    invBox.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()

    // Click "Add study" submit button.
    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const addBtn = buttons.find((b) => b.textContent?.trim() === 'Add study')!
    expect(addBtn).toBeTruthy()
    expect(addBtn.disabled).toBe(false)
    addBtn.click()
    await flushPromises()

    expect(apiPut).toHaveBeenCalledTimes(1)
    const [path, payload] = vi.mocked(apiPut).mock.calls[0] as [string, { roles: string[] }]
    expect(path).toBe('/pages/api/v1/users/alice/roles/S1')
    expect(payload.roles).toEqual(['Investigator'])
    wrapper.unmount()
  })
})

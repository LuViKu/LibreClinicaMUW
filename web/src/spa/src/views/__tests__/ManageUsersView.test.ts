/**
 * ManageUsersView — how the list names a user's role.
 *
 * The legacy data entry roles `ra` and `ra2` reach the SPA projected as
 * Investigator. The list shows them under their own name, as the roles
 * dialog does, and the Investigator filter does not count them as
 * investigators.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => vi.fn(() => Promise.resolve(true)) }))
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn().mockResolvedValue([]), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import ManageUsersView from '@/views/ManageUsersView.vue'
import { useAuthStore } from '@/stores/auth'
import { useUsersStore } from '@/stores/users'
import type { StudyUser } from '@/types/user'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function user(username: string, extra: Partial<StudyUser> = {}): StudyUser {
  return {
    id: username,
    username,
    displayName: username,
    email: null,
    role: 'Investigator',
    siteLabel: null,
    auth: 'local',
    lastLoginAt: null,
    active: true,
    locked: false,
    ...extra,
  }
}

function mountView() {
  return mount(ManageUsersView, {
    global: {
      plugins: [i18n],
      stubs: {
        BuildStudyRail: true,
        InviteUserDialog: true,
        EditUserDialog: true,
        UserRolesDialog: true,
        UserDetailsDialog: true,
      },
    },
  })
}

/** The role cell of the user's row. */
function roleCell(wrapper: ReturnType<typeof mountView>, username: string): string | null {
  const row = wrapper.findAll('tr').find((tr) => tr.text().includes(username))
  return row ? row.findAll('td')[1]!.text() : null
}

describe('ManageUsersView — role column', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().user = { username: 'admin', role: 'Administrator', userType: 'SYSADMIN' } as unknown as ReturnType<
      typeof useAuthStore
    >['user']
    useUsersStore().rows = [
      user('clerk-one', { legacyRole: 'ra' }),
      user('clerk-two', { legacyRole: 'ra2' }),
      user('physician'),
    ]
  })

  it('names a legacy data entry role, not the Investigator it is projected as', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(roleCell(wrapper, 'clerk-two')).toBe('Data Entry Person 2 (legacy)')
    expect(roleCell(wrapper, 'clerk-one')).toBe('Data Entry Person (legacy)')
    expect(roleCell(wrapper, 'physician')).toBe('Investigator')
  })

  it('does not list a legacy data entry user under Investigator', async () => {
    const wrapper = mountView()
    useUsersStore().roleFilter = 'Investigator'
    await nextTick()
    expect(roleCell(wrapper, 'physician')).toBe('Investigator')
    expect(roleCell(wrapper, 'clerk-one')).toBeNull()
    expect(roleCell(wrapper, 'clerk-two')).toBeNull()
  })
})

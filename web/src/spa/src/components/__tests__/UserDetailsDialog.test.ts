/**
 * UserDetailsDialog and its entry point on Manage Users — the account
 * details a periodic access review needs, which the SPA did not show:
 * both administrator flags, and who created and last changed the account.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { apiGet } from '@/api/client'
import UserDetailsDialog from '@/components/UserDetailsDialog.vue'
import ManageUsersView from '@/views/ManageUsersView.vue'
import { useAuthStore } from '@/stores/auth'
import type { StudyUser } from '@/types/user'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const TECH: StudyUser = {
  id: '3', username: 'theo', displayName: 'Theo Tech', email: 'theo@example.org', role: 'Administrator',
  siteLabel: null, auth: 'local', lastLoginAt: '2026-09-01T08:00:00Z', active: true, locked: false,
  firstName: 'Theo', lastName: 'Tech', phone: null, institutionalAffiliation: 'MUW IT', userType: 'TECHADMIN',
  createdDate: '2026-01-15', ownerUsername: 'root', updatedDate: '2026-02-01', updaterUsername: 'manual_admin',
}
const PLAIN: StudyUser = {
  ...TECH, id: '4', username: 'paula', displayName: 'Paula Plain', role: 'Investigator', userType: 'USER',
  firstName: 'Paula', lastName: 'Plain', updatedDate: null, updaterUsername: null,
}

function detail(key: string): string {
  return document.body.querySelector(`[data-testid="user-detail-${key}"]`)!.textContent!.trim()
}

describe('UserDetailsDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
  })

  it('shows both administrator flags and who created and last changed the account', async () => {
    const w = mount(UserDetailsDialog, { props: { open: true, user: TECH }, global: { plugins: [i18n] }, attachTo: document.body })
    await flushPromises()
    // A technical administrator is a business administrator too, as on the legacy page.
    expect(detail('businessAdmin')).toBe('Yes')
    expect(detail('technicalAdmin')).toBe('Yes')
    expect(detail('affiliation')).toBe('MUW IT')
    expect(detail('phone')).toBe('—')
    expect(detail('created')).toBe('15.01.2026 by root')
    expect(detail('updated')).toBe('01.02.2026 by manual_admin')
    w.unmount()
  })

  it('shows an ordinary account as neither, and a never-changed one as such', async () => {
    const w = mount(UserDetailsDialog, { props: { open: true, user: PLAIN }, global: { plugins: [i18n] }, attachTo: document.body })
    await flushPromises()
    expect(detail('businessAdmin')).toBe('No')
    expect(detail('technicalAdmin')).toBe('No')
    expect(detail('updated')).toBe('—')
    w.unmount()
  })
})

describe('ManageUsersView — access review', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiGet).mockResolvedValue([TECH, PLAIN])
  })

  it('marks administrator accounts and opens the details from the row', async () => {
    useAuthStore().user = { username: 'admin', role: 'Administrator', userType: 'SYSADMIN' } as unknown as ReturnType<
      typeof useAuthStore
    >['user']
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }] })
    await router.push('/manage-users')
    await router.isReady()
    const w = mount(ManageUsersView, {
      global: { plugins: [router, i18n], stubs: { BuildStudyRail: true } },
      attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.querySelector('[data-testid="user-type-theo"]')!.textContent).toContain('Technical administrator')
    expect(document.body.querySelector('[data-testid="user-type-paula"]')).toBeNull()

    ;(document.body.querySelector('[data-testid="details-theo"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(detail('created')).toBe('15.01.2026 by root')
    w.unmount()
  })
})

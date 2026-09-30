/**
 * EditUserDialog — what it pre-fills and what it sends.
 *
 * Pins the two defects the dialog had: phone and affiliation were never
 * pre-filled (only the display name was split into first and last name),
 * and a field left empty was left out of the PUT, so a phone number could
 * not be cleared. Clearing works for the optional phone only; the
 * required affiliation cannot be emptied. Also pins the account type:
 * sent only when changed, and not offered on a technical administrator's
 * account to anyone who is not one.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { apiPut } from '@/api/client'
import EditUserDialog from '@/components/EditUserDialog.vue'
import { useAuthStore } from '@/stores/auth'
import type { StudyUser } from '@/types/user'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const USER: StudyUser = {
  id: '7',
  username: 'alice',
  displayName: 'Alice Maria Smith',
  email: 'alice@example.org',
  role: 'Investigator',
  siteLabel: null,
  auth: 'local',
  lastLoginAt: null,
  active: true,
  locked: false,
  firstName: 'Alice Maria',
  lastName: 'Smith',
  phone: '+43 1 40400',
  institutionalAffiliation: 'Ophthalmology',
  userType: 'USER',
}

function seedCaller(userType: 'SYSADMIN' | 'TECHADMIN') {
  useAuthStore().user = { username: 'admin', role: 'Administrator', userType } as unknown as ReturnType<
    typeof useAuthStore
  >['user']
}

function mountDialog(user: StudyUser = USER) {
  return mount(EditUserDialog, { props: { open: false, user }, global: { plugins: [i18n] }, attachTo: document.body })
}

/** The dialog hydrates when it opens, as it does in the view. */
async function open(wrapper: ReturnType<typeof mountDialog>) {
  await wrapper.setProps({ open: true })
  await flushPromises()
}

function input(id: string): HTMLInputElement {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}

function setValue(id: string, value: string) {
  const el = input(id)
  el.value = value
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

function saveButton(): HTMLButtonElement {
  const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
  return buttons.find((b) => b.textContent?.trim() === 'Save changes')!
}

function lastPut(): Record<string, unknown> {
  const calls = vi.mocked(apiPut).mock.calls
  return calls[calls.length - 1]![1] as Record<string, unknown>
}

describe('EditUserDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedCaller('SYSADMIN')
    vi.mocked(apiPut).mockReset()
    vi.mocked(apiPut).mockResolvedValue({ ...USER })
    document.body.innerHTML = ''
  })

  it('pre-fills every field from the row, phone and affiliation included', async () => {
    const w = mountDialog()
    await open(w)
    expect(input('edit-user-firstname').value).toBe('Alice Maria')
    expect(input('edit-user-lastname').value).toBe('Smith')
    expect(input('edit-user-email').value).toBe('alice@example.org')
    expect(input('edit-user-phone').value).toBe('+43 1 40400')
    expect(input('edit-user-affiliation').value).toBe('Ophthalmology')
    w.unmount()
  })

  it('sends an emptied phone, so the number is cleared', async () => {
    const w = mountDialog()
    await open(w)
    setValue('edit-user-phone', '')
    await nextTick()
    saveButton().click()
    await flushPromises()

    expect(apiPut).toHaveBeenCalledTimes(1)
    expect(lastPut()).toHaveProperty('phone', '')
    expect(lastPut().institutionalAffiliation).toBe('Ophthalmology')
    w.unmount()
  })

  it('refuses to empty the required affiliation and sends nothing', async () => {
    const w = mountDialog()
    await open(w)
    setValue('edit-user-affiliation', '  ')
    await nextTick()

    expect(saveButton().disabled).toBe(true)
    expect(document.body.textContent).toContain('Required: this field cannot be emptied.')
    saveButton().click()
    await flushPromises()
    expect(apiPut).not.toHaveBeenCalled()
    w.unmount()
  })

  it('leaves an affiliation the account never had out of the request', async () => {
    const w = mountDialog({ ...USER, institutionalAffiliation: null })
    await open(w)
    setValue('edit-user-phone', '0664')
    await nextTick()
    saveButton().click()
    await flushPromises()

    expect(apiPut).toHaveBeenCalledTimes(1)
    expect(lastPut()).not.toHaveProperty('institutionalAffiliation')
    expect(lastPut().phone).toBe('0664')
    w.unmount()
  })

  it('sends no account type when it is unchanged', async () => {
    const w = mountDialog()
    await open(w)
    saveButton().click()
    await flushPromises()
    expect(lastPut()).not.toHaveProperty('userType')
    w.unmount()
  })

  it('sends the account type when it changes', async () => {
    const w = mountDialog()
    await open(w)
    const select = input('edit-user-usertype') as unknown as HTMLSelectElement
    select.value = 'SYSADMIN'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    saveButton().click()
    await flushPromises()
    expect(lastPut().userType).toBe('SYSADMIN')
    w.unmount()
  })

  it('does not offer a technical administrator’s type to a business administrator', async () => {
    const w = mountDialog({ ...USER, userType: 'TECHADMIN' })
    await open(w)
    const select = input('edit-user-usertype') as unknown as HTMLSelectElement
    expect(select.disabled).toBe(true)
    expect(select.value).toBe('TECHADMIN')
    saveButton().click()
    await flushPromises()
    expect(lastPut()).not.toHaveProperty('userType')
    w.unmount()
  })

  it('offers the technical administrator type to a technical administrator only', async () => {
    const w = mountDialog()
    await open(w)
    const options = () =>
      Array.from((input('edit-user-usertype') as unknown as HTMLSelectElement).options).map((o) => o.value)
    expect(options()).toEqual(['USER', 'SYSADMIN'])
    w.unmount()

    seedCaller('TECHADMIN')
    const w2 = mountDialog()
    await open(w2)
    expect(options()).toEqual(['USER', 'SYSADMIN', 'TECHADMIN'])
    w2.unmount()
  })
})

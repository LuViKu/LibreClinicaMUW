/**
 * Phase E.6 (DR-014 follow-up) — InviteUserDialog SSO branch spec.
 *
 * Pins the load-bearing wire contract change: when the operator
 * picks the SSO auth-method radio and types an institutional
 * principal (eppn), the request POSTed to `/pages/api/v1/users`
 * carries `externalId` AND omits any password-related field. The
 * server-side composite-unique check on (external_id_provider,
 * external_id) is what makes the row matchable at first SSO login,
 * so dropping the field here would silently break the whole feature.
 *
 * The reverse case is also pinned: when the operator picks the
 * (default) local-account radio, no `externalId` is on the wire.
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

import { apiGet, apiPost } from '@/api/client'
import InviteUserDialog from '@/components/InviteUserDialog.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function seedAuthWithStudy() {
  const auth = useAuthStore()
  auth.user = {
    username: 'root',
    displayName: 'Root',
    email: null,
    role: 'Administrator',
    siteLabel: null,
    profileComplete: true,
    activeStudy: {
      id: 1,
      oid: 'S_DEFAULTS1',
      name: 'Default Study',
      site: null,
    },
  } as unknown as ReturnType<typeof useAuthStore>['user']
  auth.availableStudies = STUDIES
}

/** The studies the caller may grant: a parent, its site, another study. */
const STUDIES = [
  { oid: 'S_DEFAULTS1', name: 'Default Study', uniqueIdentifier: 'default', parentOid: null, parentName: null,
    role: 'Data Manager', isSite: false, isActive: true },
  { oid: 'S_WIEN', name: 'Wien', uniqueIdentifier: 'wien', parentOid: 'S_DEFAULTS1', parentName: 'Default Study',
    role: 'Data Manager', isSite: true, isActive: false },
  { oid: 'S_OTHER', name: 'Other Study', uniqueIdentifier: 'other', parentOid: null, parentName: null,
    role: 'Data Manager', isSite: false, isActive: false },
] as ReturnType<typeof useAuthStore>['availableStudies']

function pickStudy(oid: string) {
  const select = document.body.querySelector('#invite-study') as HTMLSelectElement
  select.value = oid
  select.dispatchEvent(new Event('change', { bubbles: true }))
}

function mountDialog() {
  return mount(InviteUserDialog, {
    props: { open: true },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('InviteUserDialog — SSO branch (Phase E.6)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedAuthWithStudy()
    vi.mocked(apiPost).mockReset()
  })

  it('renders the local + SSO radio group', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    // Two radios, both inside the auth-method fieldset.
    const fieldset = document.body.querySelector('[data-testid="invite-authmethod"]')
    expect(fieldset).not.toBeNull()
    const radios = fieldset!.querySelectorAll('input[type="radio"][name="invite-authmethod"]')
    expect(radios.length).toBe(2)
    const values = Array.from(radios).map((r) => (r as HTMLInputElement).value).sort()
    expect(values).toEqual(['local', 'sso'])
    wrapper.unmount()
  })

  it('hides the externalId input until SSO is selected', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    // Default is local; no externalId input yet.
    expect(document.body.querySelector('#invite-externalid')).toBeNull()

    // Flip to SSO.
    const ssoRadio = document.body.querySelector(
      'input[type="radio"][name="invite-authmethod"][value="sso"]',
    ) as HTMLInputElement
    ssoRadio.checked = true
    ssoRadio.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    await flushPromises()

    expect(document.body.querySelector('#invite-externalid')).not.toBeNull()
    wrapper.unmount()
  })

  it('posts externalId on submit when SSO is picked + preserves eppn case', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      user: {
        id: '42',
        username: 'newuser',
        displayName: 'New User',
        email: 'newuser@example.org',
        role: 'Investigator',
        siteLabel: null,
        auth: 'sso',
        lastLoginAt: null,
        active: true,
      },
      generatedPassword: null,
    })

    const wrapper = mountDialog()
    await flushPromises()

    // Fill the required fields. Note the mixed case in the eppn — must NOT be lowercased on the wire.
    const setVal = (sel: string, val: string) => {
      const el = document.body.querySelector(sel) as HTMLInputElement
      el.value = val
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    setVal('#invite-username', 'newuser')
    setVal('#invite-firstname', 'New')
    setVal('#invite-lastname', 'User')
    setVal('#invite-email', 'newuser@example.org')
    setVal('#invite-affiliation', 'Dept. of Ophthalmology')

    // Flip to SSO.
    const ssoRadio = document.body.querySelector(
      'input[type="radio"][name="invite-authmethod"][value="sso"]',
    ) as HTMLInputElement
    ssoRadio.checked = true
    ssoRadio.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    await flushPromises()

    setVal('#invite-externalid', 'New.User@meduniwien.ac.at')
    pickStudy('S_DEFAULTS1')
    await nextTick()

    // Submit button is the last (success-action) button — last button in the footer with text "Create user".
    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const submit = buttons.find((b) => b.textContent?.trim() === 'Create user')!
    expect(submit).toBeTruthy()
    expect(submit.disabled).toBe(false)
    submit.click()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledTimes(1)
    const [path, payload] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe('/pages/api/v1/users')
    // The wire contract: externalId present, case preserved verbatim.
    expect(payload.externalId).toBe('New.User@meduniwien.ac.at')
    // Phone optional, role default.
    expect(payload.username).toBe('newuser')
    expect(payload.studyOid).toBe('S_DEFAULTS1')
    expect(payload.studyId).toBeUndefined()
    expect(payload.role).toBe('Investigator')

    wrapper.unmount()
  })

  it('omits externalId from the payload when local is picked (default)', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      user: {
        id: '42',
        username: 'newuser',
        displayName: 'New User',
        email: 'newuser@example.org',
        role: 'Investigator',
        siteLabel: null,
        auth: 'pending-invite',
        lastLoginAt: null,
        active: true,
      },
      generatedPassword: 'P@ssw0rd!',
    })

    const wrapper = mountDialog()
    await flushPromises()

    const setVal = (sel: string, val: string) => {
      const el = document.body.querySelector(sel) as HTMLInputElement
      el.value = val
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    setVal('#invite-username', 'newuser')
    setVal('#invite-firstname', 'New')
    setVal('#invite-lastname', 'User')
    setVal('#invite-email', 'newuser@example.org')
    setVal('#invite-affiliation', 'Dept. of Ophthalmology')
    pickStudy('S_DEFAULTS1')
    await nextTick()

    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const submit = buttons.find((b) => b.textContent?.trim() === 'Create user')!
    submit.click()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledTimes(1)
    const [, payload] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(payload.externalId).toBeUndefined()
    expect(payload.username).toBe('newuser')
    wrapper.unmount()
  })

  it('keeps submit disabled until SSO branch has a valid-looking eppn', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    const setVal = (sel: string, val: string) => {
      const el = document.body.querySelector(sel) as HTMLInputElement
      el.value = val
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    setVal('#invite-username', 'newuser')
    setVal('#invite-firstname', 'New')
    setVal('#invite-lastname', 'User')
    setVal('#invite-email', 'newuser@example.org')
    setVal('#invite-affiliation', 'Dept. of Ophthalmology')

    const ssoRadio = document.body.querySelector(
      'input[type="radio"][name="invite-authmethod"][value="sso"]',
    ) as HTMLInputElement
    ssoRadio.checked = true
    ssoRadio.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    await flushPromises()

    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const submit = buttons.find((b) => b.textContent?.trim() === 'Create user')!

    // No externalId yet → disabled.
    expect(submit.disabled).toBe(true)

    // Bare value without '@' → still disabled.
    setVal('#invite-externalid', 'justastring')
    await nextTick()
    expect(submit.disabled).toBe(true)

    // Valid eppn → enabled.
    setVal('#invite-externalid', 'someone@meduniwien.ac.at')
    await nextTick()
    expect(submit.disabled).toBe(false)

    wrapper.unmount()
  })
})

/**
 * The account type: the dialog creates an ordinary user unless the
 * operator picks an administrator type, and offers the technical
 * administrator type to a technical administrator only.
 */
describe('InviteUserDialog — account type', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedAuthWithStudy()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPost).mockResolvedValue({
      user: { id: '43', username: 'newadmin', displayName: 'New Admin', email: null, role: 'Investigator',
        siteLabel: null, auth: 'pending-invite', lastLoginAt: null, active: true, locked: false },
      generatedPassword: 'P@ssw0rd!',
    })
  })

  function fillRequired() {
    const setVal = (sel: string, val: string) => {
      const el = document.body.querySelector(sel) as HTMLInputElement
      el.value = val
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    setVal('#invite-username', 'newadmin')
    setVal('#invite-firstname', 'New')
    setVal('#invite-lastname', 'Admin')
    setVal('#invite-email', 'newadmin@example.org')
    setVal('#invite-affiliation', 'Dept. of Ophthalmology')
    pickStudy('S_DEFAULTS1')
  }

  function userTypeSelect(): HTMLSelectElement {
    return document.body.querySelector('#invite-usertype') as HTMLSelectElement
  }

  async function submit() {
    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    buttons.find((b) => b.textContent?.trim() === 'Create user')!.click()
    await flushPromises()
  }

  it('creates an ordinary user by default', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    fillRequired()
    await nextTick()
    await submit()
    const [, payload] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(payload.userType).toBe('USER')
    wrapper.unmount()
  })

  it('creates a business administrator when that type is picked', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    fillRequired()
    const select = userTypeSelect()
    select.value = 'SYSADMIN'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    await submit()
    const [, payload] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(payload.userType).toBe('SYSADMIN')
    wrapper.unmount()
  })

  it('offers the technical administrator type to a technical administrator only', async () => {
    const options = () => Array.from(userTypeSelect().options).map((o) => o.value)
    const wrapper = mountDialog()
    await flushPromises()
    expect(options()).toEqual(['USER', 'SYSADMIN'])
    wrapper.unmount()

    const auth = useAuthStore()
    auth.user = { ...auth.user, userType: 'TECHADMIN' } as unknown as ReturnType<typeof useAuthStore>['user']
    vi.mocked(apiGet).mockResolvedValue([])
    const again = mountDialog()
    await flushPromises()
    expect(options()).toEqual(['USER', 'SYSADMIN', 'TECHADMIN'])
    again.unmount()
  })
})

/**
 * The study of the initial binding: chosen in the dialog, never taken
 * from the caller's active study.
 */
describe('InviteUserDialog — study / site', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedAuthWithStudy()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockResolvedValue({
      user: { id: '44', username: 'newbie', displayName: 'New Bie', email: null, role: 'Investigator',
        siteLabel: 'Wien', auth: 'pending-invite', lastLoginAt: null, active: true, locked: false },
      generatedPassword: 'P@ssw0rd!',
    })
  })

  function fillAllButStudy() {
    const setVal = (sel: string, val: string) => {
      const el = document.body.querySelector(sel) as HTMLInputElement
      el.value = val
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    setVal('#invite-username', 'newbie')
    setVal('#invite-firstname', 'New')
    setVal('#invite-lastname', 'Bie')
    setVal('#invite-email', 'newbie@example.org')
    setVal('#invite-affiliation', 'Dept. of Ophthalmology')
  }

  function submitButton(): HTMLButtonElement {
    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    return buttons.find((b) => b.textContent?.trim() === 'Create user')!
  }

  it('preselects nothing and labels a site with its study', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    const select = document.body.querySelector('#invite-study') as HTMLSelectElement
    expect(select.value).toBe('')
    const labels = Array.from(select.options).map((o) => o.textContent?.trim())
    expect(labels).toEqual(['Select a study or site…', 'Default Study', 'Default Study › Wien', 'Other Study'])
    wrapper.unmount()
  })

  it('refuses to submit without a study and says so', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    fillAllButStudy()
    await nextTick()
    submitButton().click()
    await flushPromises()
    expect(apiPost).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('Select a study or site.')
    wrapper.unmount()
  })

  it('sends the chosen site, not the active study', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    fillAllButStudy()
    pickStudy('S_WIEN')
    await nextTick()
    submitButton().click()
    await flushPromises()
    expect(apiPost).toHaveBeenCalledTimes(1)
    const [, payload] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(payload.studyOid).toBe('S_WIEN')
    expect(payload.studyId).toBeUndefined()
    wrapper.unmount()
  })

  it('offers a system administrator every open study and site', async () => {
    const auth = useAuthStore()
    auth.user = { ...auth.user, userType: 'SYSADMIN' } as unknown as ReturnType<typeof useAuthStore>['user']
    vi.mocked(apiGet).mockResolvedValue([
      { oid: 'S_HAE', name: 'HealthAEye', uniqueIdentifier: 'hae', principalInvestigator: null, createdDate: null,
        status: 'AVAILABLE', parentOid: null, sites: [
          { oid: 'S_HAEVIE', name: 'Wien', uniqueIdentifier: 'hae-vie', principalInvestigator: null,
            createdDate: null, status: 'AVAILABLE', parentOid: 'S_HAE', sites: [] },
        ] },
    ])
    const wrapper = mountDialog()
    await flushPromises()
    const select = document.body.querySelector('#invite-study') as HTMLSelectElement
    expect(select.value).toBe('')
    expect(Array.from(select.options).map((o) => o.textContent?.trim()))
      .toEqual(['Select a study or site…', 'HealthAEye', 'HealthAEye › Wien'])
    wrapper.unmount()
  })
})

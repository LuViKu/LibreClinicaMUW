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

import { apiPost } from '@/api/client'
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
    expect(payload.studyId).toBe(1)
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

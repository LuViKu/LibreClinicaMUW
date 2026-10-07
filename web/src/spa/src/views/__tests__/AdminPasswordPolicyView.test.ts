import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import en from '@/locales/en.json'

/**
 * The password-policy page. It saves the whole form in one PUT, so every
 * field must be able to send back what it was loaded with, or the page
 * cannot be saved at all.
 *
 * Since R1.2 the form also carries the account lockout the legacy /Configure
 * page edited: shown as stored, sent with the rest, the count greyed out while
 * the lockout is off, and the server's refusal shown next to the count.
 */

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPut: vi.fn() }
})

import { apiGet, apiPut, ApiError } from '@/api/client'
import AdminPasswordPolicyView from '../AdminPasswordPolicyView.vue'

const apiGetMock = vi.mocked(apiGet)
const apiPutMock = vi.mocked(apiPut)

/** As a database still holding the 2012 seed reads: no maximum length. */
const POLICY = {
  requireLower: false,
  requireUpper: false,
  requireDigits: false,
  requireSpecials: false,
  minLength: 8,
  maxLength: 0,
  expirationDays: 360,
  changeRequiredOnFirstLogin: true,
  specialsAlphabet: '!@#$%&*()',
  lockoutEnabled: true,
  lockoutFailedAttempts: 3,
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  return mount(AdminPasswordPolicyView, { global: { plugins: [i18n], stubs: { SystemRail: true } } })
}

describe('AdminPasswordPolicyView', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiPutMock.mockReset()
    apiGetMock.mockResolvedValue({ ...POLICY })
    apiPutMock.mockImplementation((_path, body) => Promise.resolve(body))
  })

  it('offers 0 as "no maximum", so a policy without a maximum length can be saved', async () => {
    const w = mountView()
    await flushPromises()

    const max = w.get('#maxLength')
    // The browser refuses to submit a number below its min; the seed's
    // "no maximum" must not be below it.
    expect(max.attributes('min')).toBe('0')
    expect((max.element as HTMLInputElement).value).toBe('0')
    expect(w.text()).toContain('0 = no maximum')

    await w.get('form').trigger('submit')
    await flushPromises()
    expect(apiPutMock).toHaveBeenCalledTimes(1)
    expect(apiPutMock.mock.calls[0][1]).toMatchObject({ maxLength: 0, minLength: 8 })
  })

  it('shows the lockout as stored', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('Account lockout')
    expect((w.get('[data-testid="lockout-enabled"]').element as HTMLInputElement).checked).toBe(true)
    const count = w.get('#lockoutFailedAttempts')
    expect((count.element as HTMLInputElement).value).toBe('3')
    expect(count.attributes('min')).toBe('1')
    expect(count.attributes('max')).toBe('25')
    expect(count.attributes('disabled')).toBeUndefined()
  })

  it('sends the lockout with the rest of the policy', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('#lockoutFailedAttempts').setValue('7')
    await w.get('form').trigger('submit')
    await flushPromises()

    expect(apiPutMock).toHaveBeenCalledTimes(1)
    const [path, body] = apiPutMock.mock.calls[0]
    expect(path).toBe('/pages/api/v1/admin/password-policy')
    expect(body).toMatchObject({ lockoutEnabled: true, lockoutFailedAttempts: 7, minLength: 8 })
    expect(w.text()).toContain('Password policy saved.')
  })

  it('greys out the count while the lockout is off, and still sends the switch', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-testid="lockout-enabled"]').setValue(false)
    expect(w.get('#lockoutFailedAttempts').attributes('disabled')).toBeDefined()

    await w.get('form').trigger('submit')
    await flushPromises()
    expect(apiPutMock.mock.calls[0][1]).toMatchObject({ lockoutEnabled: false, lockoutFailedAttempts: 3 })
  })

  it("shows the server's refusal next to the count", async () => {
    apiPutMock.mockRejectedValueOnce(new ApiError(400, 'Validation failed.', {
      message: 'Validation failed.',
      errors: [{ field: 'lockoutFailedAttempts', message: 'lockoutFailedAttempts must be between 1 and 25.' }],
    }))
    const w = mountView()
    await flushPromises()
    await w.get('#lockoutFailedAttempts').setValue('30')
    await w.get('form').trigger('submit')
    await flushPromises()

    expect(w.text()).toContain('lockoutFailedAttempts must be between 1 and 25.')
    expect(w.get('#lockoutFailedAttempts').attributes('aria-invalid')).toBe('true')
    expect(w.text()).not.toContain('Password policy saved.')
  })
})

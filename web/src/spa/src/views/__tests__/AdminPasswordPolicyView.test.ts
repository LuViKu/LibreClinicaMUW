import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import en from '@/locales/en.json'

/**
 * The password-policy page. It saves the whole form in one PUT, so every
 * field must be able to send back what it was loaded with, or the page
 * cannot be saved at all.
 */

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPut: vi.fn() }
})

import { apiGet, apiPut } from '@/api/client'
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
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import en from '@/locales/en.json'

/**
 * The login history (R1.1): the only screen over audit_user_login.
 *
 * Pinned: every status reads as words, the SSO codes and an unknown code
 * included, never as the enum name; the filters go to the server, and a new
 * filter starts again at page one; the pager moves through the server's
 * pages with the total the server counted; the export sends the same filters
 * and no page, because it is the whole filtered result; and a refused filter
 * shows the server's reason.
 */

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})
vi.mock('@/api/download', () => ({
  apiDownload: vi.fn().mockResolvedValue({ filename: 'login-history.csv', bytes: 10 }),
}))

import { apiGet, ApiError } from '@/api/client'
import { apiDownload } from '@/api/download'
import DateInput from '@/components/DateInput.vue'
import AdminLoginHistoryView from '../AdminLoginHistoryView.vue'

const apiGetMock = vi.mocked(apiGet)
const apiDownloadMock = vi.mocked(apiDownload)

function attempt(id: number, status: string, statusCode: number | null, extra: Record<string, unknown> = {}) {
  return {
    id,
    userName: 'alice',
    userAccountId: 3,
    attemptedAt: '2026-09-30T08:00:00Z',
    status,
    statusCode,
    details: null,
    ...extra,
  }
}

const PAGE = {
  totalCount: 120,
  page: 0,
  pageSize: 50,
  rows: [
    attempt(1, 'SSO_LOGIN', 6, { userName: 'jdoe', details: 'sso-principal=jdoe@meduniwien.ac.at' }),
    attempt(2, 'SSO_LOGIN_FAILED', 7, { userName: 'stranger', userAccountId: null, details: 'sso-principal=stranger sso-provider=shibboleth' }),
    attempt(3, 'FAILED_LOGIN_LOCKED', 3),
    attempt(4, 'SUCCESSFUL_LOGOUT', 4),
    attempt(5, 'FAILED_LOGIN', 2, { userName: 'typo-name', userAccountId: null }),
    attempt(6, 'UNKNOWN', 42),
  ],
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  // The rail reads the route and has its own test; this page has no router.
  return mount(AdminLoginHistoryView, { global: { plugins: [i18n], stubs: { SystemRail: true } } })
}

function lastQuery(): URLSearchParams {
  const url = String(apiGetMock.mock.calls.at(-1)?.[0] ?? '')
  expect(url.startsWith('/pages/api/v1/admin/login-history?')).toBe(true)
  return new URLSearchParams(url.split('?')[1])
}

describe('AdminLoginHistoryView', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiGetMock.mockResolvedValue(PAGE)
    apiDownloadMock.mockClear()
  })

  it('asks for the first page and names every status in words, the SSO codes included', async () => {
    const w = mountView()
    await flushPromises()

    expect(apiGetMock).toHaveBeenCalledTimes(1)
    const q = lastQuery()
    expect(q.get('page')).toBe('0')
    expect(q.get('pageSize')).toBe('50')

    expect(w.get('[data-testid="login-row-1"]').text()).toContain('SSO login')
    expect(w.get('[data-testid="login-row-1"]').text()).toContain('jdoe')
    expect(w.get('[data-testid="login-row-2"]').text()).toContain('SSO login refused')
    expect(w.get('[data-testid="login-row-2"]').text()).toContain('sso-provider=shibboleth')
    expect(w.get('[data-testid="login-row-3"]').text()).toContain('Refused: account locked')
    expect(w.get('[data-testid="login-row-4"]').text()).toContain('Logout')
    expect(w.get('[data-testid="login-row-5"]').text()).toContain('Failed login')
    expect(w.get('[data-testid="login-row-6"]').text()).toContain('Unknown status (42)')
    for (const raw of ['SSO_LOGIN', 'FAILED_LOGIN', 'SUCCESSFUL_LOGOUT', 'UNKNOWN']) {
      expect(w.find('tbody').text()).not.toContain(raw)
    }
    expect(w.text()).toContain('1–50 of 120')
    expect(w.text()).toContain('Page 1 of 3')
  })

  it('pages through the server, previous disabled on the first page and next on the last', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-testid="page-prev"]').attributes('disabled')).toBeDefined()

    await w.get('[data-testid="page-next"]').trigger('click')
    await flushPromises()
    expect(lastQuery().get('page')).toBe('1')
    expect(w.text()).toContain('51–100 of 120')

    await w.get('[data-testid="page-next"]').trigger('click')
    await flushPromises()
    expect(lastQuery().get('page')).toBe('2')
    expect(w.text()).toContain('101–120 of 120')
    expect(w.get('[data-testid="page-next"]').attributes('disabled')).toBeDefined()

    await w.get('[data-testid="page-prev"]').trigger('click')
    await flushPromises()
    expect(lastQuery().get('page')).toBe('1')
  })

  it('sends the filters to the server, and a new filter starts again at page one', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-testid="page-next"]').trigger('click')
    await flushPromises()
    expect(lastQuery().get('page')).toBe('1')

    await w.get('#lh-status').setValue('FAILED_LOGIN,FAILED_LOGIN_LOCKED,SSO_LOGIN_FAILED')
    await flushPromises()
    let q = lastQuery()
    expect(q.get('status')).toBe('FAILED_LOGIN,FAILED_LOGIN_LOCKED,SSO_LOGIN_FAILED')
    expect(q.get('page')).toBe('0')

    const [fromInput, toInput] = w.findAllComponents(DateInput)
    fromInput.vm.$emit('update:modelValue', '2026-09-01')
    await flushPromises()
    toInput.vm.$emit('update:modelValue', '2026-09-30')
    await flushPromises()
    q = lastQuery()
    expect(q.get('from')).toBe('2026-09-01')
    expect(q.get('to')).toBe('2026-09-30')
    expect(q.get('status')).toBe('FAILED_LOGIN,FAILED_LOGIN_LOCKED,SSO_LOGIN_FAILED')
    expect(q.get('page')).toBe('0')
  })

  it('asks the server once typing pauses, not at every keystroke', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-testid="page-next"]').trigger('click')
    await flushPromises()

    const calls = apiGetMock.mock.calls.length
    await w.get('#lh-user').setValue('j')
    await w.get('#lh-user').setValue('jd')
    await w.get('#lh-user').setValue('  jdoe ')
    await flushPromises()
    expect(apiGetMock.mock.calls.length).toBe(calls)

    await new Promise((resolve) => setTimeout(resolve, 350))
    await flushPromises()
    expect(apiGetMock.mock.calls.length).toBe(calls + 1)
    const q = lastQuery()
    expect(q.get('user')).toBe('jdoe')
    expect(q.get('page')).toBe('0')
  })

  it('offers each status, and all refusals at once, by its name in words', async () => {
    const w = mountView()
    await flushPromises()
    const options = w.findAll('#lh-status option').map((o) => [o.attributes('value'), o.text()])
    expect(options).toEqual([
      ['', 'All statuses'],
      ['FAILED_LOGIN,FAILED_LOGIN_LOCKED,SSO_LOGIN_FAILED', 'All refused logins'],
      ['SUCCESSFUL_LOGIN', 'Successful login'],
      ['FAILED_LOGIN', 'Failed login'],
      ['FAILED_LOGIN_LOCKED', 'Refused: account locked'],
      ['SUCCESSFUL_LOGOUT', 'Logout'],
      ['SSO_LOGIN', 'SSO login'],
      ['SSO_LOGIN_FAILED', 'SSO login refused'],
      ['ACCESS_CODE_VIEWED', 'Access code viewed'],
    ])
  })

  it('exports the whole filtered result: the same filters, no page', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('#lh-status').setValue('SSO_LOGIN_FAILED')
    await flushPromises()
    await w.get('[data-testid="page-next"]').trigger('click')
    await flushPromises()

    await w.get('[data-testid="login-history-export"]').trigger('click')
    await flushPromises()

    expect(apiDownloadMock).toHaveBeenCalledTimes(1)
    const [path, fallbackName] = apiDownloadMock.mock.calls[0]
    expect(path.startsWith('/pages/api/v1/admin/login-history/export.csv?')).toBe(true)
    expect(fallbackName).toBe('login-history.csv')
    const q = new URLSearchParams(path.split('?')[1])
    expect(q.get('status')).toBe('SSO_LOGIN_FAILED')
    expect(q.has('page')).toBe(false)
    expect(q.has('pageSize')).toBe(false)
  })

  it("shows the server's reason when it refuses a filter", async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(400, 'Validation failed.', {
      message: 'Validation failed.',
      errors: [{ field: 'to', message: 'to must not be before from.' }],
    }))
    const w = mountView()
    await flushPromises()
    expect(w.get('[role="alert"]').text()).toBe('to must not be before from.')
    expect(w.find('[data-testid="login-row-1"]').exists()).toBe(false)
  })

  it('says so when nothing matches', async () => {
    apiGetMock.mockResolvedValue({ totalCount: 0, page: 0, pageSize: 50, rows: [] })
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('No login attempts match these filters.')
    expect(w.text()).toContain('0–0 of 0')
    expect(w.get('[data-testid="page-next"]').attributes('disabled')).toBeDefined()
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUsersStore } from '../users'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { StudyUser } from '@/types/user'

/**
 * Phase E.6 unlock-user — Vitest coverage for the users store
 * `unlock()` action.
 *
 * Strategy mirrors {@link ../__tests__/auth.test.ts}: vi.mock the
 * `@/api/client` module so each test wires only the apiPost return it
 * needs. The store's other actions already get covered via
 * ManageUsersView component tests; this file is the first net-new
 * unit-test spec for {@link useUsersStore} and scopes itself to
 * unlock-user per playbook §3.1 sequencing.
 */
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

const FIXTURE_LOCKED: StudyUser = {
  id: '42',
  username: 'bob',
  displayName: 'Bob Builder',
  email: 'bob@example.org',
  role: 'Investigator',
  siteLabel: 'Site A',
  auth: 'local',
  lastLoginAt: null,
  active: true,
  locked: true,
}

const FIXTURE_UNLOCKED: StudyUser = { ...FIXTURE_LOCKED, locked: false }

describe('useUsersStore.unlock', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiPost).mockReset()
  })

  it('POSTs to /pages/api/v1/users/{username}/unlock with sendEmail:false', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      user: FIXTURE_UNLOCKED,
      generatedPassword: 'TempPass-7xy',
    })
    const store = useUsersStore()

    await store.unlock('bob')

    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/users/bob/unlock',
      { sendEmail: false },
    )
  })

  it('replaces the matching matrix row on success so the Locked badge clears', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      user: FIXTURE_UNLOCKED,
      generatedPassword: 'TempPass-7xy',
    })
    const store = useUsersStore()
    store.rows.push(FIXTURE_LOCKED)
    expect(store.rows[0].locked).toBe(true)

    const result = await store.unlock('bob')

    expect(result).toEqual({
      ok: true,
      user: FIXTURE_UNLOCKED,
      generatedPassword: 'TempPass-7xy',
    })
    expect(store.rows[0].locked).toBe(false)
  })

  it('passes the generated cleartext one-time password through for local users', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      user: FIXTURE_UNLOCKED,
      generatedPassword: 'OneTime!42',
    })
    const store = useUsersStore()

    const result = await store.unlock('bob')

    expect(result.ok).toBe(true)
    if (result.ok) expect(result.generatedPassword).toBe('OneTime!42')
  })

  it('returns null generatedPassword when the backend swallowed it (sendEmail flow)', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      user: FIXTURE_UNLOCKED,
      generatedPassword: null,
    })
    const store = useUsersStore()

    const result = await store.unlock('bob')

    expect(result.ok).toBe(true)
    if (result.ok) expect(result.generatedPassword).toBeNull()
  })

  it('maps a 409 not-currently-locked into store.error + returns ok:false', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(409, 'conflict', { message: "User 'bob' is not currently locked" }),
    )
    const store = useUsersStore()

    const result = await store.unlock('bob')

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.message).toBe("User 'bob' is not currently locked")
    }
    expect(store.error).toBe("User 'bob' is not currently locked")
  })

  it('maps a 400 directory-owned credential into store.error + returns ok:false', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(400, 'bad request', {
        message: "User 'alice' is authenticated via the identity provider — lock state is owned by that workflow",
      }),
    )
    const store = useUsersStore()

    const result = await store.unlock('alice')

    expect(result.ok).toBe(false)
    expect(store.error).toContain('identity provider')
  })

  it('rethrows on 401/403 so the navigation guard can catch it', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(403, 'forbidden', { message: 'sysadmin only' }),
    )
    const store = useUsersStore()

    await expect(store.unlock('bob')).rejects.toBeInstanceOf(ApiError)
    expect(store.error).toBe('sysadmin only')
  })

  it('maps a network failure into a friendly error + returns ok:false', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(new ApiNetworkError('boom', new Error('boom')))
    const store = useUsersStore()

    const result = await store.unlock('bob')

    expect(result.ok).toBe(false)
    expect(store.error).toContain('Backend nicht erreichbar')
  })
})

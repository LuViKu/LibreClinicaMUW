import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUsersStore } from '../users'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { StudyUser } from '@/types/user'

/**
 * Phase E.6 — Vitest coverage for the users store.
 *
 * This file combines two cohorts the harmonizer merged from sibling
 * branches:
 *
 *   - {@link useUsersStore.unlock}        (unlock-user cluster)
 *   - {@link useUsersStore.resetPassword} (auth-admin cluster)
 *
 * Strategy: vi.mock the `@/api/client` module so each test wires only
 * the apiPost return it needs. Both cohorts share the same mock seam
 * and identical `apiPost` import.
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

describe('useUsersStore.resetPassword', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiPost).mockReset()
  })

  it('resolves to {ok:true, generatedPassword} on a 200 happy-path response', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({ generatedPassword: 'tmp-Pw#42' })
    const store = useUsersStore()

    const result = await store.resetPassword('physician')

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/users/physician/resetPassword',
      { sendEmail: false },
    )
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.generatedPassword).toBe('tmp-Pw#42')
    }
    expect(store.error).toBeNull()
  })

  it('encodes the username path segment so directory-style usernames round-trip', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({ generatedPassword: null })
    const store = useUsersStore()
    await store.resetPassword('ad/user&name')
    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/users/ad%2Fuser%26name/resetPassword',
      { sendEmail: false },
    )
  })

  it('resolves to {ok:false, message} on a 400 directory-owned response (SSO / LDAP)', async () => {
    const body = {
      message:
        "User 'sso-user' is authenticated via the identity provider — "
        + 'password resets must go through that workflow',
    }
    vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(400, 'Bad Request', body))
    const store = useUsersStore()

    const result = await store.resetPassword('sso-user')

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.message).toContain('identity provider')
    }
    expect(store.error).toContain('identity provider')
  })

  it('re-throws the ApiError on 401 anonymous so the router auth-guard runs', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(401, 'Unauthorized', { message: 'Not authenticated' }),
    )
    const store = useUsersStore()

    await expect(store.resetPassword('physician')).rejects.toBeInstanceOf(ApiError)
    expect(store.error).toContain('Not authenticated')
  })

  it('re-throws the ApiError on 403 non-sysadmin so the router auth-guard runs', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(403, 'Forbidden', {
        message: 'Your role does not permit user administration — sysadmin only',
      }),
    )
    const store = useUsersStore()

    await expect(store.resetPassword('physician')).rejects.toBeInstanceOf(ApiError)
    expect(store.error).toContain('sysadmin only')
  })

  it('resolves to {ok:false, message} with a German fallback on network failure', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(new ApiNetworkError('refused', undefined))
    const store = useUsersStore()

    const result = await store.resetPassword('physician')

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.message).toContain('Backend nicht erreichbar')
    }
    expect(store.error).toContain('Backend nicht erreichbar')
  })
})

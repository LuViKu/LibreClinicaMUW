import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUsersStore } from '../users'

/**
 * Phase E.6 `auth-admin` test-gap closure — pins the
 * {@link useUsersStore.resetPassword} branches against the LIVE
 * `POST /api/v1/users/{username}/resetPassword` adapter shipped under
 * Phase E A7.4.
 *
 * Per the Phase E.6 build playbook (§ 3.6) the controller surface and
 * SPA store action are already in production; what's missing is the
 * Vitest coverage. The four scenarios pinned here mirror the four
 * documented branches:
 *
 *   1. happy-path 200 → `{generatedPassword}` propagates
 *   2. SSO / LDAP 400 ("directory-owned credential") → resolved
 *      `{ok:false, message}` carrying the operator-visible reason
 *   3. anonymous 401 → re-thrown so the router-level auth guard
 *      bounces the user back to /login
 *   4. non-sysadmin 403 → re-thrown for the same reason
 *
 * Mocks `@/api/client` rather than spinning up an HTTP fixture — the
 * resetPassword action's contract surface is the
 * `apiPost('/pages/api/v1/users/.../resetPassword', …)` call shape and
 * the error-translation logic, both of which are pure in-process code.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiPost: vi.fn(),
  }
})

import { apiPost, ApiError, ApiNetworkError } from '@/api/client'

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
    // No banner-level error is set on success — the view surfaces the
    // generated password directly to the operator.
    expect(store.error).toBeNull()
  })

  it('encodes the username path segment so directory-style usernames round-trip', async () => {
    // Some institutional LDAP usernames carry slashes / ampersands —
    // the store uses encodeURIComponent so the path stays well-formed.
    vi.mocked(apiPost).mockResolvedValueOnce({ generatedPassword: null })
    const store = useUsersStore()
    await store.resetPassword('ad/user&name')
    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/users/ad%2Fuser%26name/resetPassword',
      { sendEmail: false },
    )
  })

  it('resolves to {ok:false, message} on a 400 directory-owned response (SSO / LDAP)', async () => {
    // Backend returns this shape when the target carries
    // `external_id_provider` or `authtype=ldap`. SPA surfaces the
    // operator-visible reason rather than re-throwing because the user
    // isn't necessarily logged out — they just picked the wrong row.
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
    // The store also writes the message to the banner so a separate
    // view (e.g. a ManageUsersView toast) can read it.
    expect(store.error).toContain('identity provider')
  })

  it('re-throws the ApiError on 401 anonymous so the router auth-guard runs', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(401, 'Unauthorized', { message: 'Not authenticated' }),
    )
    const store = useUsersStore()

    await expect(store.resetPassword('physician')).rejects.toBeInstanceOf(ApiError)
    // The router-level guard relies on the throw + the error banner
    // both being populated — the redirect logic reads `auth.isLoggedIn`
    // but a stale banner could mislead the operator on the next view.
    // The store prefers the backend's `message` over its localized
    // fallback so the operator sees the canonical reason string.
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
    // ApiNetworkError implies the request never reached the server —
    // the SPA surfaces a translated "Backend nicht erreichbar" message
    // rather than re-throwing, matching the other write-paths in the
    // users store (createUser / updateUser / restoreUser).
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

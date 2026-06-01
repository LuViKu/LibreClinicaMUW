import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ProfileValidationError, useAuthStore } from '../auth'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { AuthenticatedUser, StudyOption } from '@/types/auth'

/**
 * Phase E.5 B2 — Vitest coverage for the auth store.
 *
 * The auth store is the only Pinia store without a fixture-injected
 * spec (the others all gained one during M5/M7/M9/M10/M12 mock removal).
 * This suite covers every public action over the apiGet/apiPost
 * client + the raw window.fetch paths used for the Spring Security
 * form login and the logout call.
 *
 * Strategy: vi.mock('@/api/client') stubs apiGet/apiPost/apiPut;
 * vi.stubGlobal('fetch') swaps the form-login + logout transports.
 * Each test wires only what it needs and resets stubs afterward.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
  }
})

import { apiGet, apiPost, apiPut } from '@/api/client'

const FIXTURE_USER: AuthenticatedUser = {
  username: 'root',
  displayName: 'Root User',
  email: 'root@example.org',
  role: 'Data Manager',
  siteLabel: null,
  source: 'local',
  mfaSatisfied: true,
  profileComplete: true,
  locale: 'en',
  timezone: 'Europe/Vienna',
  activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
}

const FIXTURE_USER_NEEDS_PROFILE: AuthenticatedUser = {
  ...FIXTURE_USER,
  profileComplete: false,
}

const FIXTURE_STUDIES: StudyOption[] = [
  { oid: 'S_DEFAULTS1', name: 'Default Study', parentOid: null, parentName: null, role: 'Data Manager', isSite: false, isActive: true },
  { oid: 'S_MUC',       name: 'München Site',  parentOid: 'S_DEFAULTS1', parentName: 'Default Study', role: 'Investigator', isSite: true,  isActive: false },
]

/** Build a fetch Response stub Spring-Security style. */
function fetchResponse(opts: { ok?: boolean; status?: number; url?: string; bodyJson?: unknown } = {}): Response {
  const status = opts.status ?? 200
  return {
    ok: opts.ok ?? (status >= 200 && status < 300),
    status,
    url: opts.url ?? 'http://localhost/LibreClinica/',
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => opts.bodyJson ?? null,
    text: async () => (opts.bodyJson == null ? '' : JSON.stringify(opts.bodyJson)),
  } as unknown as Response
}

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPut).mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('initial state', () => {
    it('starts anonymous + empty', () => {
      const store = useAuthStore()
      expect(store.user).toBeNull()
      expect(store.state).toBe('anonymous')
      expect(store.isAnonymous).toBe(true)
      expect(store.isAuthenticated).toBe(false)
      expect(store.needsProfile).toBe(false)
      expect(store.availableStudies).toEqual([])
    })
  })

  describe('bootstrap()', () => {
    it('populates user + transitions to authenticated on /me 200 with profileComplete=true', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER)
      const store = useAuthStore()
      await store.bootstrap()
      expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/me')
      expect(store.user).toEqual(FIXTURE_USER)
      expect(store.state).toBe('authenticated')
      expect(store.isAuthenticated).toBe(true)
    })

    it('transitions to profile-incomplete when profileComplete=false', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER_NEEDS_PROFILE)
      const store = useAuthStore()
      await store.bootstrap()
      expect(store.state).toBe('profile-incomplete')
      expect(store.needsProfile).toBe(true)
      expect(store.isAuthenticated).toBe(false)
    })

    it('clears to anonymous on 401', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(401, 'Unauthorized', null))
      const store = useAuthStore()
      await store.bootstrap()
      expect(store.user).toBeNull()
      expect(store.state).toBe('anonymous')
      // 401 is the expected "anonymous user" signal — no error toast.
      expect(store.error).toBeNull()
    })

    it('clears + records error on network failure', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiNetworkError('boom', new Error('ECONNREFUSED')))
      const store = useAuthStore()
      await store.bootstrap()
      expect(store.user).toBeNull()
      expect(store.state).toBe('anonymous')
      expect(store.error).toBe('Backend unreachable.')
    })
  })

  describe('localLogin()', () => {
    it('POSTs URL-encoded credentials to j_spring_security_check, then re-hydrates', async () => {
      const fetchMock = vi.fn().mockResolvedValue(fetchResponse({ url: 'http://localhost/LibreClinica/MainMenu' }))
      vi.stubGlobal('fetch', fetchMock)
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER)

      const store = useAuthStore()
      await store.localLogin('root', '12345678')

      expect(fetchMock).toHaveBeenCalledTimes(1)
      const [url, init] = fetchMock.mock.calls[0]
      expect(url).toBe('/LibreClinica/j_spring_security_check')
      expect(init?.method).toBe('POST')
      expect(init?.credentials).toBe('include')
      expect((init?.headers as Record<string, string>)['Content-Type'])
        .toBe('application/x-www-form-urlencoded')
      expect(init?.body).toContain('j_username=root')
      expect(init?.body).toContain('j_password=12345678')
      expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/me')
      expect(store.user).toEqual(FIXTURE_USER)
      expect(store.state).toBe('authenticated')
    })

    it('flags invalid credentials on the errorLogin redirect', async () => {
      const fetchMock = vi.fn().mockResolvedValue(fetchResponse({
        url: 'http://localhost/LibreClinica/pages/login/login?errorLogin=true',
      }))
      vi.stubGlobal('fetch', fetchMock)

      const store = useAuthStore()
      await store.localLogin('root', 'badpw')
      expect(store.error).toBe('Invalid username or password.')
      expect(store.state).toBe('anonymous')
      expect(apiGet).not.toHaveBeenCalled()
    })

    it('flags locked accounts on the errorLocked redirect', async () => {
      const fetchMock = vi.fn().mockResolvedValue(fetchResponse({
        url: 'http://localhost/LibreClinica/pages/login/login?errorLocked=true',
      }))
      vi.stubGlobal('fetch', fetchMock)

      const store = useAuthStore()
      await store.localLogin('root', '12345678')
      expect(store.error).toBe('Account locked. Contact your administrator.')
    })

    it('flags 2FA-outdated on the 2faOutdated redirect', async () => {
      const fetchMock = vi.fn().mockResolvedValue(fetchResponse({
        url: 'http://localhost/LibreClinica/pages/login/login?action=2faOutdated',
      }))
      vi.stubGlobal('fetch', fetchMock)

      const store = useAuthStore()
      await store.localLogin('root', '12345678')
      expect(store.error).toContain('2FA')
    })

    it('rejects empty username/password without hitting the network', async () => {
      const fetchMock = vi.fn()
      vi.stubGlobal('fetch', fetchMock)
      const store = useAuthStore()
      await store.localLogin('', '')
      expect(store.error).toBe('Username and password are required.')
      expect(fetchMock).not.toHaveBeenCalled()
    })
  })

  describe('loadStudies()', () => {
    it('populates availableStudies on success', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_STUDIES)
      const store = useAuthStore()
      await store.loadStudies()
      expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/studies')
      expect(store.availableStudies).toEqual(FIXTURE_STUDIES)
    })

    it('records error + clears list on network failure', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiNetworkError('boom', new Error()))
      const store = useAuthStore()
      await store.loadStudies()
      expect(store.availableStudies).toEqual([])
      expect(store.error).toBe('Backend unreachable.')
    })
  })

  describe('pickStudy()', () => {
    it('POSTs the OID + refreshes user state', async () => {
      vi.mocked(apiPost).mockResolvedValueOnce(FIXTURE_USER)
      const store = useAuthStore()
      await store.pickStudy('S_DEFAULTS1')
      expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/me/activeStudy', { oid: 'S_DEFAULTS1' })
      expect(store.user).toEqual(FIXTURE_USER)
      expect(store.state).toBe('authenticated')
    })

    it('rethrows + records error on 403', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(403, 'Forbidden', null))
      const store = useAuthStore()
      await expect(store.pickStudy('S_NOACCESS')).rejects.toThrow()
      expect(store.error).toContain('Forbidden')
    })
  })

  describe('completeProfile()', () => {
    it('PUTs to /me/profile + transitions to authenticated on success', async () => {
      // Need a starting user since completeProfile bails when user is null.
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER_NEEDS_PROFILE)
      const store = useAuthStore()
      await store.bootstrap()
      expect(store.state).toBe('profile-incomplete')

      vi.mocked(apiPut).mockResolvedValueOnce(FIXTURE_USER)
      await store.completeProfile({
        displayName: 'Dr. Test',
        locale: 'en',
        timezone: 'Europe/Vienna',
      })
      expect(apiPut).toHaveBeenCalledWith('/pages/api/v1/me/profile', {
        displayName: 'Dr. Test',
        locale: 'en',
        timezone: 'Europe/Vienna',
      })
      expect(store.state).toBe('authenticated')
      expect(store.user?.locale).toBe('en')
    })

    it('throws ProfileValidationError carrying per-field errors on 400', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER_NEEDS_PROFILE)
      const store = useAuthStore()
      await store.bootstrap()

      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(400, 'Validation failed', {
        message: 'Validation failed',
        errors: [
          { field: 'displayName', message: 'Display name is required' },
          { field: 'locale', message: 'Locale must be one of: de-AT, de, en' },
        ],
      }))

      try {
        await store.completeProfile({ displayName: '', locale: 'klingon', timezone: 'UTC' })
        throw new Error('expected ProfileValidationError')
      } catch (e) {
        expect(e).toBeInstanceOf(ProfileValidationError)
        const err = e as ProfileValidationError
        expect(err.errors).toHaveLength(2)
        expect(err.errors[0]!.field).toBe('displayName')
        expect(err.errors[1]!.field).toBe('locale')
      }
      // State stays profile-incomplete on validation failure.
      expect(store.state).toBe('profile-incomplete')
    })

    it('records error + rethrows on non-validation failure', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER_NEEDS_PROFILE)
      const store = useAuthStore()
      await store.bootstrap()

      vi.mocked(apiPut).mockRejectedValueOnce(new ApiNetworkError('boom', new Error()))
      await expect(store.completeProfile({
        displayName: 'x', locale: 'en', timezone: 'UTC',
      })).rejects.toThrow()
      expect(store.error).toBe('Backend unreachable.')
    })

    it('is a no-op when user is null (anonymous state)', async () => {
      const store = useAuthStore()
      await store.completeProfile({ displayName: 'x', locale: 'en', timezone: 'UTC' })
      expect(apiPut).not.toHaveBeenCalled()
    })
  })

  describe('logout()', () => {
    it('fetches /Logout + clears local state', async () => {
      const fetchMock = vi.fn().mockResolvedValue(fetchResponse({}))
      vi.stubGlobal('fetch', fetchMock)
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER)

      const store = useAuthStore()
      await store.bootstrap()
      expect(store.state).toBe('authenticated')

      await store.logout()
      expect(fetchMock).toHaveBeenCalledWith('/LibreClinica/Logout', expect.objectContaining({
        method: 'GET',
        credentials: 'include',
      }))
      expect(store.user).toBeNull()
      expect(store.state).toBe('anonymous')
      expect(store.availableStudies).toEqual([])
    })

    it('still clears local state even when the network call fails', async () => {
      const fetchMock = vi.fn().mockRejectedValue(new Error('network down'))
      vi.stubGlobal('fetch', fetchMock)
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER)

      const store = useAuthStore()
      await store.bootstrap()
      await store.logout()
      expect(store.user).toBeNull()
      expect(store.state).toBe('anonymous')
    })
  })

  describe('ssoBounce()', () => {
    it('records error when SSO entryUrl is null', () => {
      const store = useAuthStore()
      store.ssoConfig.entryUrl = null
      store.ssoBounce()
      expect(store.error).toContain('SSO is not configured')
    })
  })

  describe('needsStudyPick', () => {
    it('is true when authenticated without an active study', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce({ ...FIXTURE_USER, activeStudy: null })
      const store = useAuthStore()
      await store.bootstrap()
      expect(store.needsStudyPick).toBe(true)
    })

    it('is false when an active study is bound', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE_USER)
      const store = useAuthStore()
      await store.bootstrap()
      expect(store.needsStudyPick).toBe(false)
    })
  })
})

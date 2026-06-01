import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  AuthState, AuthenticatedUser, SsoConfig, UserRole, StudyOption,
} from '@/types/auth'

/**
 * Phase E.8 + E.4 M1 — Auth store.
 *
 * Holds the current user + SSO config + auth state. Drives:
 *   - `TopBar` chip + breadcrumb
 *   - Router guards (anonymous routes → /login; profile-incomplete →
 *     /first-login; missing active study → /pick-study;
 *     role-mismatched routes → home)
 *   - LoginView, FirstLoginView, StudyPickerView
 *
 * Wired to the real backend as of M1 (2026-06-01):
 *   - `bootstrap()` calls `GET /pages/api/v1/me` on app load.
 *   - `localLogin()` POSTs `j_spring_security_check` (Spring Security
 *     form-login filter) then re-hydrates from /me.
 *   - `loadStudies()` calls `GET /pages/api/v1/studies` to populate
 *     the picker.
 *   - `pickStudy(oid)` POSTs `/me/activeStudy` to bind the session-
 *     scoped study and refresh the user.
 *
 * Set `VITE_USE_MOCK_API=true` to bypass the network entirely; the
 * dev-mode `switchTo()` role shortcut + mock SSO bounce still work
 * for offline UX iteration. The flag is removed in milestone 13.
 */
const USE_MOCK_API = import.meta.env.VITE_USE_MOCK_API === 'true'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthenticatedUser | null>(null)
  const state = ref<AuthState>('anonymous')
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const availableStudies = ref<StudyOption[]>([])

  const ssoConfig = ref<SsoConfig>({
    enabled: true,
    buttonLabel: 'Sign in with MedUni Wien',
    entryUrl: 'http://localhost:8080/LibreClinica/saml2/authenticate/meduniwien',
    providerHint: 'You will be redirected to login.meduniwien.ac.at for single sign-on.',
  })

  const isAuthenticated = computed(() => state.value === 'authenticated')
  const needsProfile = computed(() => state.value === 'profile-incomplete')
  const isAnonymous = computed(() => state.value === 'anonymous')
  const needsStudyPick = computed(
    () => state.value === 'authenticated' && !user.value?.activeStudy,
  )

  /**
   * Boot the store on app load. Tries /me first; on 401 we know the
   * user is anonymous. Mock-mode reads sessionStorage for the dev
   * persona instead.
   */
  async function bootstrap(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      if (USE_MOCK_API) {
        const persisted = typeof window !== 'undefined'
          ? window.sessionStorage.getItem('libreclinica.mock_user')
          : null
        if (persisted) {
          user.value = JSON.parse(persisted) as AuthenticatedUser
          state.value = user.value!.profileComplete ? 'authenticated' : 'profile-incomplete'
        } else {
          user.value = null
          state.value = 'anonymous'
        }
        return
      }

      try {
        const me = await apiGet<AuthenticatedUser>('/pages/api/v1/me')
        user.value = me
        state.value = me.profileComplete ? 'authenticated' : 'profile-incomplete'
      } catch (e) {
        if (e instanceof ApiError && e.isUnauthorized) {
          user.value = null
          state.value = 'anonymous'
        } else {
          throw e
        }
      }
    } catch (e) {
      error.value = e instanceof ApiError ? e.message
        : e instanceof ApiNetworkError ? 'Backend unreachable.'
        : 'Failed to load current user.'
      user.value = null
      state.value = 'anonymous'
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Local-account form submission. Real backend: POSTs to Spring
   * Security's `j_spring_security_check`, follows the redirect to
   * /MainMenu, then calls /me to re-hydrate.
   */
  async function localLogin(username: string, password: string): Promise<void> {
    error.value = null
    if (!username || !password) {
      error.value = 'Username and password are required.'
      return
    }

    if (USE_MOCK_API) {
      await new Promise((resolve) => setTimeout(resolve, 40))
      if (password === 'wrong') {
        error.value = 'Invalid username or password.'
        return
      }
      setMockUser({
        username, displayName: username, email: `${username}@example.org`,
        role: 'Investigator', siteLabel: 'München', source: 'local',
        mfaSatisfied: false, profileComplete: true, activeStudy: null,
      })
      return
    }

    isLoading.value = true
    try {
      const body = new URLSearchParams({ j_username: username, j_password: password })
      const response = await fetch('/LibreClinica/j_spring_security_check', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body.toString(),
      })

      const finalUrl = response.url || ''
      if (/errorLogin|action=2faOutdated|errorLocked/.test(finalUrl)) {
        if (/errorLocked/.test(finalUrl)) error.value = 'Account locked. Contact your administrator.'
        else if (/2faOutdated/.test(finalUrl)) error.value = '2FA setup required. Sign in via the legacy UI to enroll.'
        else error.value = 'Invalid username or password.'
        return
      }
      if (!response.ok) {
        error.value = `Login failed (HTTP ${response.status}).`
        return
      }

      // Auth succeeded — JSESSIONID is set on this origin. Re-hydrate
      // from /me to get the canonical user representation. SecureController
      // also auto-binds the user's stored active_study_id as soon as
      // an authenticated request reaches a /pages controller, so /me's
      // activeStudy field reflects that auto-bind.
      await bootstrap()
    } catch (e) {
      error.value = e instanceof Error ? `Login failed: ${e.message}` : 'Login failed.'
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Load the user's available studies for the picker.
   */
  async function loadStudies(): Promise<void> {
    if (USE_MOCK_API) {
      availableStudies.value = [
        { oid: 'S_DEFAULTS1', name: 'Default Study', parentOid: null, parentName: null,
          role: 'Investigator', isSite: false, isActive: true },
      ]
      return
    }
    try {
      availableStudies.value = await apiGet<StudyOption[]>('/pages/api/v1/studies')
    } catch (e) {
      error.value = e instanceof ApiError ? e.message
        : e instanceof ApiNetworkError ? 'Backend unreachable.'
        : 'Failed to load studies.'
      availableStudies.value = []
    }
  }

  /**
   * Bind the active study on the server, then refresh the user.
   */
  async function pickStudy(oid: string): Promise<void> {
    if (USE_MOCK_API) {
      // Mock: just stash the chosen study on the user object.
      const opt = availableStudies.value.find((s) => s.oid === oid)
      if (user.value && opt) {
        user.value = {
          ...user.value,
          activeStudy: { oid: opt.oid, name: opt.name, isSite: opt.isSite },
          role: opt.role,
          siteLabel: opt.isSite ? opt.name : null,
        }
        persist(user.value)
      }
      return
    }
    try {
      const refreshed = await apiPost<AuthenticatedUser>('/pages/api/v1/me/activeStudy', { oid })
      user.value = refreshed
      state.value = refreshed.profileComplete ? 'authenticated' : 'profile-incomplete'
    } catch (e) {
      error.value = e instanceof ApiError ? e.message
        : e instanceof ApiNetworkError ? 'Backend unreachable.'
        : 'Failed to set active study.'
      throw e
    }
  }

  /**
   * SSO bounce — in production redirects to `ssoConfig.entryUrl`.
   * In dev (mock mode) we mock the post-bounce identity directly.
   */
  function ssoBounce(): void {
    if (!USE_MOCK_API && ssoConfig.value.entryUrl) {
      window.location.assign(ssoConfig.value.entryUrl)
      return
    }
    setMockUser({
      username: 'm.mueller',
      displayName: 'Dr. Maria Müller',
      email: 'm.mueller@meduniwien.ac.at',
      role: 'Investigator',
      siteLabel: 'München',
      source: 'sso',
      mfaSatisfied: true,
      profileComplete: false,
      activeStudy: null,
    })
  }

  /**
   * Dev-mode role-switcher invoked from the TopBar's "View as" chip.
   * Only usable in mock mode (the real backend's user role comes
   * from the bound study).
   */
  function switchTo(role: UserRole): void {
    if (!USE_MOCK_API) return
    const presets: Record<UserRole, AuthenticatedUser> = {
      Investigator: {
        username: 'user_demo', displayName: 'Dr. user_demo', email: 'user_demo@meduniwien.ac.at',
        role: 'Investigator', siteLabel: 'München', source: 'sso', mfaSatisfied: true, profileComplete: true,
        activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
      },
      Monitor: {
        username: 'monitor_demo', displayName: 'Mona Demo', email: 'monitor_demo@example.org',
        role: 'Monitor', siteLabel: null, source: 'local', mfaSatisfied: false, profileComplete: true,
        activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
      },
      'Data Manager': {
        username: 'dm_demo', displayName: 'Dora Manager', email: 'dm_demo@meduniwien.ac.at',
        role: 'Data Manager', siteLabel: null, source: 'sso', mfaSatisfied: true, profileComplete: true,
        activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
      },
      Administrator: {
        username: 'admin', displayName: 'System Administrator', email: null,
        role: 'Administrator', siteLabel: null, source: 'local', mfaSatisfied: false, profileComplete: true,
        activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
      },
      CRC: {
        username: 'crc_demo', displayName: 'Lisa Koordinator', email: 'crc_demo@example.org',
        role: 'CRC', siteLabel: 'München', source: 'local', mfaSatisfied: false, profileComplete: true,
        activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
      },
    }
    setMockUser(presets[role])
  }

  function completeProfile(patch: { displayName: string; locale: string; timezone: string }): void {
    if (!user.value) return
    user.value = { ...user.value, displayName: patch.displayName, profileComplete: true }
    state.value = 'authenticated'
    persist(user.value)
  }

  async function logout(): Promise<void> {
    if (!USE_MOCK_API) {
      try {
        await fetch('/LibreClinica/Logout', { method: 'GET', credentials: 'include' })
      } catch { /* best-effort — clear local state regardless */ }
    }
    user.value = null
    state.value = 'anonymous'
    availableStudies.value = []
    if (typeof window !== 'undefined') {
      window.sessionStorage.removeItem('libreclinica.mock_user')
    }
  }

  function setMockUser(u: AuthenticatedUser) {
    user.value = u
    state.value = u.profileComplete ? 'authenticated' : 'profile-incomplete'
    persist(u)
  }

  function persist(u: AuthenticatedUser) {
    if (typeof window !== 'undefined' && USE_MOCK_API) {
      window.sessionStorage.setItem('libreclinica.mock_user', JSON.stringify(u))
    }
  }

  return {
    user,
    state,
    isLoading,
    error,
    ssoConfig,
    availableStudies,
    isAuthenticated,
    needsProfile,
    isAnonymous,
    needsStudyPick,
    bootstrap,
    localLogin,
    loadStudies,
    pickStudy,
    ssoBounce,
    switchTo,
    completeProfile,
    logout,
  }
})

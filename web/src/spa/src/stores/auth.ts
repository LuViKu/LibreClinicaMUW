import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  AuthState, AuthenticatedUser, SsoConfig, StudyOption,
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
 * Fully backend-driven as of Phase E.4 M13 (2026-06-01):
 *   - `bootstrap()` calls `GET /pages/api/v1/me` on app load.
 *   - `localLogin()` POSTs `j_spring_security_check` (Spring Security
 *     form-login filter) then re-hydrates from /me.
 *   - `loadStudies()` calls `GET /pages/api/v1/studies` to populate
 *     the picker.
 *   - `pickStudy(oid)` POSTs `/me/activeStudy` to bind the session-
 *     scoped study and refresh the user.
 *
 * Mock-mode (the previous `VITE_USE_MOCK_API` escape hatch, sessionStorage
 * persona persistence, and the TopBar `switchTo()` dev shortcut) was
 * removed in milestone 13 per the plan. The backend is now the single
 * source of truth; if it's unreachable the UI hard-fails with an error
 * toast rather than silently rendering demo data.
 */
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
   * Boot the store on app load. Calls /me; on 401 we know the user is
   * anonymous and the router will redirect to /login.
   */
  async function bootstrap(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      const me = await apiGet<AuthenticatedUser>('/pages/api/v1/me')
      user.value = me
      state.value = me.profileComplete ? 'authenticated' : 'profile-incomplete'
    } catch (e) {
      if (e instanceof ApiError && e.isUnauthorized) {
        user.value = null
        state.value = 'anonymous'
      } else {
        error.value = e instanceof ApiError ? e.message
          : e instanceof ApiNetworkError ? 'Backend unreachable.'
          : 'Failed to load current user.'
        user.value = null
        state.value = 'anonymous'
      }
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Local-account form submission. POSTs to Spring Security's
   * `j_spring_security_check`, then re-hydrates from /me.
   * SecureController auto-binds the user's stored active_study_id
   * on the next /pages/* request, so /me's activeStudy reflects
   * the binding without a separate call.
   */
  async function localLogin(username: string, password: string): Promise<void> {
    error.value = null
    if (!username || !password) {
      error.value = 'Username and password are required.'
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

      await bootstrap()
    } catch (e) {
      error.value = e instanceof Error ? `Login failed: ${e.message}` : 'Login failed.'
    } finally {
      isLoading.value = false
    }
  }

  /** Load the user's available studies for the picker. */
  async function loadStudies(): Promise<void> {
    try {
      availableStudies.value = await apiGet<StudyOption[]>('/pages/api/v1/studies')
    } catch (e) {
      error.value = e instanceof ApiError ? e.message
        : e instanceof ApiNetworkError ? 'Backend unreachable.'
        : 'Failed to load studies.'
      availableStudies.value = []
    }
  }

  /** Bind the active study on the server, then refresh the user. */
  async function pickStudy(oid: string): Promise<void> {
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
   * SSO bounce — redirects to the configured IdP entry URL when
   * `ssoConfig.enabled`. The post-bounce identity arrives in the
   * normal /me round-trip after the reverse-proxy proxies the
   * pre-authenticated request back.
   */
  function ssoBounce(): void {
    if (!ssoConfig.value.entryUrl) {
      error.value = 'SSO is not configured at this deployment.'
      return
    }
    window.location.assign(ssoConfig.value.entryUrl)
  }

  /**
   * First-login profile completion. Local-only update for now — the
   * backend persistence path (a new `PUT /pages/api/v1/me/profile`
   * endpoint per DR-014) is a follow-up. Until then a page reload
   * after completion will re-render the FirstLoginView; future
   * sessions persist their profile state via the IdP.
   */
  function completeProfile(patch: { displayName: string; locale: string; timezone: string }): void {
    if (!user.value) return
    user.value = { ...user.value, displayName: patch.displayName, profileComplete: true }
    state.value = 'authenticated'
  }

  async function logout(): Promise<void> {
    try {
      await fetch('/LibreClinica/Logout', { method: 'GET', credentials: 'include' })
    } catch { /* best-effort — clear local state regardless */ }
    user.value = null
    state.value = 'anonymous'
    availableStudies.value = []
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
    completeProfile,
    logout,
  }
})

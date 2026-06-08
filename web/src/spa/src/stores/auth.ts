import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  AuthState, AuthenticatedUser, PasswordChangeFieldError, PasswordChangeRequest,
  ProfileFieldError, ProfileUpdateRequest, SsoConfig, StudyOption,
} from '@/types/auth'
import { useSubjectsStore } from './subjects'
import { useEventsStore } from './events'
import { useNotesStore } from './notes'
import { useSdvStore } from './sdv'
import { useAuditLogStore } from './auditLog'
import { useRulesStore } from './rules'
import { useEventDefinitionsStore } from './eventDefinitions'
import { useCrfLibraryStore } from './crfLibrary'
import { useSitesStore } from './sites'
import { useUsersStore } from './users'
import { useStudyStore } from './study'
import { useDatasetsStore } from './datasets'
import { useImportCrfStore } from './importCrf'
import { usePatientsOverviewStore } from './patientsOverview'

/**
 * Thrown by {@link useAuthStore.completeProfile} on a 400 from
 * {@code PUT /pages/api/v1/me/profile}. Carries the per-field error
 * list so the FirstLoginView can render inline messages.
 */
export class ProfileValidationError extends Error {
  constructor(public readonly errors: ProfileFieldError[]) {
    super(`Profile validation failed (${errors.length} error${errors.length === 1 ? '' : 's'})`)
    this.name = 'ProfileValidationError'
  }
}

/**
 * Phase E.6 — thrown by {@link useAuthStore.changePassword} on a 400
 * from {@code POST /pages/api/v1/me/password}. Carries the per-field
 * error list (wrong current password, complexity rule failures, repeat
 * mismatch) so ChangePasswordView can render inline messages.
 */
export class PasswordChangeValidationError extends Error {
  constructor(public readonly errors: PasswordChangeFieldError[]) {
    super(`Password validation failed (${errors.length} error${errors.length === 1 ? '' : 's'})`)
    this.name = 'PasswordChangeValidationError'
  }
}

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
   * Phase E.6 — true when the backend tells us the user must change
   * their password before continuing (first login or rotation expired).
   * Source of truth is the {@code mustChangePassword} field on the
   * /me payload — refreshed by every {@link bootstrap} / {@link
   * pickStudy} / {@link completeProfile} response so the flag clears
   * the moment a successful {@link changePassword} returns the updated
   * MeDto.
   */
  const needsPasswordChange = computed(
    () => user.value?.mustChangePassword === true,
  )

  /**
   * Boot the store on app load. Calls /me; on 401 we know the user is
   * anonymous and the router will redirect to /login.
   *
   * <p>Phase E.6 — if bootstrap discovers a different activeStudy
   * than the one we held locally (e.g. SecureController-driven
   * server-side bind to the user's stored {@code active_study_id} on
   * a fresh session), the previously cached study-scoped store state
   * is wiped via {@link resetStudyScopedStores}. Rare path; covers
   * the gap between the user's last session and the new one without
   * leaving stale matrix rows visible.
   */
  async function bootstrap(): Promise<void> {
    isLoading.value = true
    error.value = null
    const previousActiveOid = user.value?.activeStudy?.oid ?? null
    try {
      const me = await apiGet<AuthenticatedUser>('/pages/api/v1/me')
      user.value = me
      state.value = me.profileComplete ? 'authenticated' : 'profile-incomplete'
      const nextActiveOid = me.activeStudy?.oid ?? null
      if (previousActiveOid !== null && previousActiveOid !== nextActiveOid) {
        resetStudyScopedStores()
      }
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

  /**
   * Bind the active study on the server, then refresh the user.
   *
   * <p>Phase E.6 — flips {@code activeStudy} server-side, then
   * synchronously clears every per-study Pinia store before the
   * refreshed user lands. Without this step the matrix continues
   * showing study-A subjects after a switch to study B until the
   * caller manually re-loads each view's store (none of them do).
   *
   * <p>The reset order is: server-mutation → store-wipe → local
   * user state. Store-wipe before local state means any reactive
   * watchers triggered by the user change see an empty store + a
   * pending {@code isLoading} flag rather than a transient stale
   * blend.
   */
  async function pickStudy(oid: string): Promise<void> {
    try {
      const refreshed = await apiPost<AuthenticatedUser>('/pages/api/v1/me/activeStudy', { oid })
      resetStudyScopedStores()
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
   * Phase E.6 — clear every per-study Pinia store's cached state.
   *
   * <p>The store creators are imported at module level, but the actual
   * instance lookup (`useXyzStore()`) happens inside this function so
   * Pinia's active-instance resolution is deferred to call time — the
   * standard pattern for cross-store action coordination (Pinia docs:
   * "use stores inside actions"). None of the resolved stores imports
   * the auth store, so there's no cycle.
   *
   * <p>Synchronous: each {@code reset()} mutates its store's refs in
   * place, so by the time this returns every list / selected / filter
   * piece of state has been cleared. The {@link pickStudy} caller can
   * then update {@code user.value} with the refreshed study binding
   * without any chance of a stale-row blend.
   */
  function resetStudyScopedStores(): void {
    useSubjectsStore().reset()
    useEventsStore().reset()
    useNotesStore().reset()
    useSdvStore().reset()
    useAuditLogStore().reset()
    useRulesStore().reset()
    useEventDefinitionsStore().reset()
    useCrfLibraryStore().reset()
    useSitesStore().reset()
    useUsersStore().reset()
    useStudyStore().reset()
    useDatasetsStore().reset()
    useImportCrfStore().reset()
    usePatientsOverviewStore().reset()
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
   * Phase E.5 B1 — first-login profile completion now hits
   * {@code PUT /pages/api/v1/me/profile}. The server returns the
   * refreshed MeDto on success; on 400 the per-field errors are
   * thrown as a {@link ProfileValidationError} so the FirstLoginView
   * can render inline messages without re-validating client-side.
   */
  async function completeProfile(patch: ProfileUpdateRequest): Promise<void> {
    if (!user.value) return
    isLoading.value = true
    error.value = null
    try {
      const refreshed = await apiPut<AuthenticatedUser>('/pages/api/v1/me/profile', patch)
      user.value = refreshed
      state.value = refreshed.profileComplete ? 'authenticated' : 'profile-incomplete'
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        const body = e.body as { errors?: ProfileFieldError[] } | null
        const fieldErrors = body?.errors ?? []
        throw new ProfileValidationError(fieldErrors)
      }
      error.value = e instanceof ApiError ? e.message
        : e instanceof ApiNetworkError ? 'Backend unreachable.'
        : 'Failed to save profile.'
      throw e
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Phase E.6 — POST {@code /pages/api/v1/me/password}.
   *
   * On success the backend returns the refreshed MeDto with
   * {@code mustChangePassword=false} + a fresh
   * {@code passwd_timestamp}, so the router guard immediately lets
   * the user leave the change-password view.
   *
   * On 400 the per-field errors are thrown as a {@link
   * PasswordChangeValidationError} so ChangePasswordView can render
   * inline messages without re-validating client-side.
   */
  async function changePassword(req: PasswordChangeRequest): Promise<void> {
    if (!user.value) return
    isLoading.value = true
    error.value = null
    try {
      const refreshed = await apiPost<AuthenticatedUser>('/pages/api/v1/me/password', req)
      user.value = refreshed
      state.value = refreshed.profileComplete ? 'authenticated' : 'profile-incomplete'
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        const body = e.body as { errors?: PasswordChangeFieldError[] } | null
        const fieldErrors = body?.errors ?? []
        throw new PasswordChangeValidationError(fieldErrors)
      }
      error.value = e instanceof ApiError ? e.message
        : e instanceof ApiNetworkError ? 'Backend unreachable.'
        : 'Failed to change password.'
      throw e
    } finally {
      isLoading.value = false
    }
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
    needsPasswordChange,
    bootstrap,
    localLogin,
    loadStudies,
    pickStudy,
    ssoBounce,
    completeProfile,
    changePassword,
    logout,
  }
})

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthState, AuthenticatedUser, SsoConfig, UserRole } from '@/types/auth'

/**
 * Phase E.8 — Auth store.
 *
 * Holds the current user + SSO config + auth state. Drives:
 *   - `TopBar` chip + breadcrumb (replaces the previous route-meta hack)
 *   - Router guards (anonymous routes → /login; profile-incomplete →
 *     /first-login; role-mismatched routes → home)
 *   - LoginView + FirstLoginView
 *
 * Mock-driven: `bootstrap()` reads `/pages/api/v1/me` in production
 * (TODO when E.4 adapter lands); for now it inspects sessionStorage
 * for a `libreclinica.mock_role` value, defaulting to anonymous when
 * absent so the LoginView renders on first visit.
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthenticatedUser | null>(null)
  const state = ref<AuthState>('anonymous')
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const ssoConfig = ref<SsoConfig>({
    enabled: true,
    buttonLabel: 'Sign in with MedUni Wien',
    entryUrl: 'http://localhost:8080/LibreClinica/saml2/authenticate/meduniwien',
    providerHint: 'You will be redirected to login.meduniwien.ac.at for single sign-on.',
  })

  const isAuthenticated = computed(() => state.value === 'authenticated')
  const needsProfile = computed(() => state.value === 'profile-incomplete')
  const isAnonymous = computed(() => state.value === 'anonymous')

  /**
   * Boot the store from any persisted dev-mode mock state. Called from
   * main.ts (or the router's first guard). No-ops in production once
   * the adapter swap lands; the planned `GET /pages/api/v1/me` is the
   * authoritative source.
   */
  async function bootstrap(): Promise<void> {
    isLoading.value = true
    try {
      // TODO(E.4): apiGet<AuthenticatedUser>('/pages/api/v1/me').
      // For now we look at sessionStorage to support dev-mode role
      // switching without redeploying.
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
    } finally {
      isLoading.value = false
    }
  }

  /** Local-account form submission. Mocks success for any non-empty pair. */
  async function localLogin(username: string, password: string): Promise<void> {
    error.value = null
    if (!username || !password) {
      error.value = 'Username and password are required.'
      return
    }
    // Pretend the network takes a tick.
    await new Promise((resolve) => setTimeout(resolve, 40))
    if (password === 'wrong') {
      error.value = 'Invalid username or password.'
      return
    }
    setMockUser({
      username,
      displayName: username,
      email: `${username}@example.org`,
      role: 'Investigator',
      siteLabel: 'München',
      source: 'local',
      mfaSatisfied: false,
      profileComplete: true,
    })
  }

  /**
   * SSO bounce — in production redirects to `ssoConfig.entryUrl`.
   * In dev (no real backend) we mock the post-bounce identity directly
   * so the rest of the SPA can be exercised without a live IdP.
   */
  function ssoBounce(): void {
    setMockUser({
      username: 'm.mueller',
      displayName: 'Dr. Maria Müller',
      email: 'm.mueller@meduniwien.ac.at',
      role: 'Investigator',
      siteLabel: 'München',
      source: 'sso',
      mfaSatisfied: true,
      profileComplete: false, // first login — kicks the wizard.
    })
  }

  /**
   * Dev-mode role-switcher invoked from the TopBar's "View as" chip.
   * Mints a fully-configured AuthenticatedUser for the requested role
   * so reviewers can preview the whole SPA without a real IdP.
   */
  function switchTo(role: UserRole): void {
    const presets: Record<UserRole, AuthenticatedUser> = {
      Investigator: {
        username: 'user_demo', displayName: 'Dr. user_demo', email: 'user_demo@meduniwien.ac.at',
        role: 'Investigator', siteLabel: 'München', source: 'sso', mfaSatisfied: true, profileComplete: true,
      },
      Monitor: {
        username: 'monitor_demo', displayName: 'Mona Demo', email: 'monitor_demo@example.org',
        role: 'Monitor', siteLabel: null, source: 'local', mfaSatisfied: false, profileComplete: true,
      },
      'Data Manager': {
        username: 'dm_demo', displayName: 'Dora Manager', email: 'dm_demo@meduniwien.ac.at',
        role: 'Data Manager', siteLabel: null, source: 'sso', mfaSatisfied: true, profileComplete: true,
      },
      Administrator: {
        username: 'admin', displayName: 'System Administrator', email: null,
        role: 'Administrator', siteLabel: null, source: 'local', mfaSatisfied: false, profileComplete: true,
      },
      CRC: {
        username: 'crc_demo', displayName: 'Lisa Koordinator', email: 'crc_demo@example.org',
        role: 'CRC', siteLabel: 'München', source: 'local', mfaSatisfied: false, profileComplete: true,
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

  function logout(): void {
    user.value = null
    state.value = 'anonymous'
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
    if (typeof window !== 'undefined') {
      window.sessionStorage.setItem('libreclinica.mock_user', JSON.stringify(u))
    }
  }

  return {
    user,
    state,
    isLoading,
    error,
    ssoConfig,
    isAuthenticated,
    needsProfile,
    isAnonymous,
    bootstrap,
    localLogin,
    ssoBounce,
    switchTo,
    completeProfile,
    logout,
  }
})

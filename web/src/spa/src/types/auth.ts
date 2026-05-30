/**
 * Phase E.8 — Auth types.
 *
 * Mirrors DR-014's institution-agnostic SSO architecture: the SPA
 * never sees the underlying SAML/OIDC/proprietary protocol — only
 * the reverse-proxy-injected identity headers exposed as a
 * normalised `AuthenticatedUser` shape. Until the E.4 adapter at
 * `GET /pages/api/v1/me` lands, the SPA mocks the same shape.
 */

export type UserRole =
  | 'Investigator'
  | 'Monitor'
  | 'Data Manager'
  | 'Administrator'
  | 'CRC'

export type AuthSource = 'sso' | 'local' | 'ldap'

export type AuthState =
  | 'anonymous'
  | 'profile-incomplete'
  | 'authenticated'

export interface AuthenticatedUser {
  username: string
  displayName: string
  email: string | null
  role: UserRole
  siteLabel: string | null
  source: AuthSource
  /** True when the auth method enforces MFA at the IdP (SSO via reverse-proxy). */
  mfaSatisfied: boolean
  /** First-login profile completion flag — drives the FirstLogin wizard. */
  profileComplete: boolean
}

/**
 * Institution-local SSO config returned by the planned adapter
 * `GET /pages/api/v1/sso/config`. Drives the button label + redirect
 * on the LoginView. Empty `entryUrl` ⇒ SSO is disabled at this
 * deployment; local auth is the only path.
 */
export interface SsoConfig {
  enabled: boolean
  buttonLabel: string
  entryUrl: string | null
  /** Human-readable provider hint shown under the button. */
  providerHint: string | null
}

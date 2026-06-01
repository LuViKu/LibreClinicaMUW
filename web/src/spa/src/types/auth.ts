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
  /**
   * BCP-47 language tag the user picked at first login. `null` for
   * never-set users; the SPA falls back to the browser-detected default.
   * Persisted via {@code PUT /pages/api/v1/me/profile} (Phase E.5 B1).
   */
  locale: string | null
  /**
   * IANA timezone id (e.g. {@code "Europe/Vienna"}). `null` for never-set;
   * SPA falls back to the browser-detected zone. Persisted via the same
   * profile PUT.
   */
  timezone: string | null
  /**
   * The study currently bound to the server-side session. `null` when
   * the user has authenticated but not yet picked a study (the SPA
   * routes them to the study-picker). Drives the role chip + scope
   * tells in the top bar.
   */
  activeStudy: ActiveStudySummary | null
}

/**
 * Phase E.5 B1 — body of {@code PUT /pages/api/v1/me/profile}.
 *
 * <p>Field names match the SPA's first-login wizard inputs; the
 * backend maps {@code displayName} to {@code user_account.first_name}.
 */
export interface ProfileUpdateRequest {
  displayName: string
  locale: string
  timezone: string
}

/** Per-field validation error returned by 400 responses on profile-edit endpoints. */
export interface ProfileFieldError {
  field: 'displayName' | 'locale' | 'timezone'
  message: string
}

/** Minimal study summary embedded in AuthenticatedUser. */
export interface ActiveStudySummary {
  oid: string
  name: string
  isSite: boolean
}

/**
 * Phase E.4 M1 — one row in the user's available-studies list,
 * returned by `GET /pages/api/v1/studies` and consumed by the
 * StudyPicker view.
 */
export interface StudyOption {
  oid: string
  name: string
  parentOid: string | null
  parentName: string | null
  role: UserRole
  isSite: boolean
  isActive: boolean
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

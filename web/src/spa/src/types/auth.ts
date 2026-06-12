/**
 * Phase E.8 — Auth types.
 *
 * Mirrors DR-014's institution-agnostic SSO architecture: the SPA
 * never sees the underlying SAML/OIDC/proprietary protocol — only
 * the reverse-proxy-injected identity headers exposed as a
 * normalised `AuthenticatedUser` shape. Until the E.4 adapter at
 * `GET /pages/api/v1/me` lands, the SPA mocks the same shape.
 */

import type { components } from './api'

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

/**
 * Phase E.5 follow-up (2026-06-02, TODO #7): hydrated from the
 * openapi-typescript-generated {@link components['schemas']['MeDto']}.
 * The generated type marks every field optional (records don't carry
 * required-vs-optional metadata); we lift the always-present fields
 * with {@link Required} and override the loosely-typed string fields
 * with the SPA's narrow unions ({@link UserRole}, {@link AuthSource}).
 * Nullable fields ({@code email}, {@code siteLabel}, {@code locale},
 * {@code timezone}) keep the SPA's {@code string | null} call-site
 * convention so the existing optional-chaining code keeps compiling.
 */
export type AuthenticatedUser =
  Omit<Required<components['schemas']['MeDto']>,
       'role' | 'source' | 'email' | 'siteLabel' | 'locale' | 'timezone'
       | 'passwordChangeReason' | 'activeStudy'>
  & {
    role: UserRole
    source: AuthSource
    email: string | null
    siteLabel: string | null
    locale: string | null
    timezone: string | null
    /**
     * Phase E.6 — null when {@code mustChangePassword} is false. When
     * true, drives the localised banner copy on ChangePasswordView
     * ({@code 'first-login'} → "Welcome — please set a password before
     * you continue"; {@code 'rotation'} → "Your password has expired
     * and must be changed before you continue").
     */
    passwordChangeReason: PasswordChangeReason | null
    activeStudy: ActiveStudySummary | null
  }

/** UX hint for the ChangePasswordView banner — see AuthenticatedUser. */
export type PasswordChangeReason = 'first-login' | 'rotation'

/**
 * Phase E.6 — body of {@code POST /pages/api/v1/me/password}.
 *
 * Field names match the SPA's ChangePasswordView inputs. The backend
 * verifies {@code currentPassword} against the bcrypt-stored hash,
 * runs {@code newPassword} through the admin-configured complexity
 * rules + reuse check, and confirms {@code newPasswordRepeat} matches.
 */
export interface PasswordChangeRequest {
  currentPassword: string
  newPassword: string
  newPasswordRepeat: string
}

/** Per-field validation error returned by 400 responses on /me/password. */
export interface PasswordChangeFieldError {
  field: 'currentPassword' | 'newPassword' | 'newPasswordRepeat'
  message: string
}

/**
 * Phase E.5 B1 — body of {@code PUT /pages/api/v1/me/profile}.
 *
 * <p>Phase E.5 follow-up (2026-06-02, TODO #7): derived from the
 * openapi-typescript-generated {@link components} schema so the SPA's
 * call sites stay aligned with the backend record shape. The previous
 * hand-typed declaration had {@code displayName / locale / timezone}
 * as required {@code string}s; the generated schema marks them
 * optional matching the Java record (every field defaults to {@code
 * null} if missing). Wrapped with {@link Required} to keep the
 * SPA's existing call-site invariant (first-login wizard rejects
 * blanks before submitting) without diverging from the spec.
 *
 * <p>Field names match the SPA's first-login wizard inputs; the
 * backend maps {@code displayName} to {@code user_account.first_name}.
 */
export type ProfileUpdateRequest =
  Required<components['schemas']['ProfileUpdateRequest']>

/** Per-field validation error returned by 400 responses on profile-edit endpoints. */
export interface ProfileFieldError {
  field: 'displayName' | 'locale' | 'timezone'
  message: string
}

/**
 * Minimal study summary embedded in AuthenticatedUser.
 *
 * Phase E.5 follow-up (TODO #7) — derived from
 * {@code components['schemas']['ActiveStudyDto']}.
 *
 * <p>Multi-role per (user, study) — M2 (2026-06-08): {@code roles}
 * carries the full set of role bindings the user holds in the active
 * study. The singular {@code role} field is preserved for backward
 * compatibility with callers that still read the highest-priority
 * projection. New consumers should prefer {@code roles}; the SPA
 * falls back to the singular value when {@code roles} is undefined.
 * The hand-written extension lives here because the api.ts generator
 * has not yet been re-run against the M1 backend changes.
 */
export type ActiveStudySummary =
  Required<components['schemas']['ActiveStudyDto']>
  & {
    /**
     * Single-role projection of the binding the user holds in this
     * study. Optional because the M1 wire shape does not yet emit it
     * — the top-level {@link AuthenticatedUser.role} carries the same
     * value during the M1 → M2 transition. Callers should prefer
     * {@link roles} and only fall back to this singular value when
     * the array is absent.
     */
    role?: UserRole
    roles?: UserRole[]
  }

/**
 * Phase E.4 M1 — one row in the user's available-studies list,
 * returned by `GET /pages/api/v1/studies` and consumed by the
 * StudyPicker view.
 *
 * Phase E.5 follow-up (TODO #7) — derived from
 * {@code components['schemas']['StudyOptionDto']}. Overrides the
 * loosely-typed {@code role} field with the SPA's {@link UserRole}
 * union, and keeps the {@code parentOid} / {@code parentName} pair
 * as {@code string | null} so the picker's "site or study" branching
 * keeps its null-narrowing semantics.
 */
export type StudyOption =
  Omit<Required<components['schemas']['StudyOptionDto']>, 'role' | 'parentOid' | 'parentName'>
  & {
    role: UserRole
    parentOid: string | null
    parentName: string | null
    /**
     * Multi-role per (user, study) — M2 (2026-06-08): full set of
     * role bindings the user holds in this study. Optional for
     * backward compatibility with the M1 backend wire shape, which
     * still emits {@code role} only. Picker rows fall back to the
     * singular value when undefined.
     */
    roles?: UserRole[]
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

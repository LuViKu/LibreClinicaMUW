/**
 * Phase E.7 — Study-user types.
 *
 * Shape follows the planned `GET /pages/api/v1/users?siteOid=…&role=…`
 * adapter response per api-surface.md row 12.
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): {@link StudyUser} is
 * derived from the openapi-typescript-generated
 * {@code components['schemas']['StudyUserDto']}. Narrow {@link UserRole}
 * / {@link UserAuth} literal unions stay hand-typed.
 */

import type { components } from './api'

export type UserRole =
  | 'Investigator'
  | 'Monitor'
  | 'Data Manager'
  | 'Administrator'
  | 'CRC' /* Clinical Research Coordinator */

export type UserAuth =
  | 'sso'              // institutional SSO via reverse-proxy pre-auth
  | 'local'            // local username/password (legacy + sponsor monitors)
  | 'ldap'             // legacy LDAP bind
  | 'pending-invite'   // user invited, not logged in yet

export type StudyUser =
  Omit<Required<components['schemas']['StudyUserDto']>, 'role' | 'auth' | 'email' | 'siteLabel' | 'lastLoginAt'>
  & {
    role: UserRole
    auth: UserAuth
    email: string | null
    /** Site label, or null for study-wide roles (e.g. Data Manager). */
    siteLabel: string | null
    /** ISO instant of last login, or null when never logged in. */
    lastLoginAt: string | null
  }

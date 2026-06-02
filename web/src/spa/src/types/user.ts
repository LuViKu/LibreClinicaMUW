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

/**
 * Phase E A7.1 — `POST /api/v1/users` request body.
 *
 * Mirrors the backend {@code CreateUserRequest} record. {@code studyId}
 * + {@code role} encode the initial role binding bundled with the
 * create; further bindings use the A7.5 endpoints. {@code sendEmail}
 * defaults to {@code true} but the welcome email is deferred until the
 * MailService extraction lands — until then a {@code false} value
 * makes the response carry the cleartext one-time password.
 */
export interface CreateUserInput {
  username: string
  firstName: string
  lastName: string
  email: string
  institutionalAffiliation: string
  phone?: string | null
  studyId: number
  role: UserRole
  userType?: 'USER' | 'SYSADMIN' | 'TECHADMIN'
  userSource?: 'local'
  authtype?: string | null
  runWebservices?: boolean
  sendEmail?: boolean
}

/**
 * Phase E A7.1 — `POST /api/v1/users` response body. Carries the
 * persisted {@link StudyUser} row plus the one-time
 * `generatedPassword` (only when {@code sendEmail === false}).
 */
export interface CreateUserResult {
  user: StudyUser
  generatedPassword: string | null
}

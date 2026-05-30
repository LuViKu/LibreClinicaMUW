/**
 * Phase E.7 — Study-user types.
 *
 * Shape follows the planned `GET /pages/api/v1/users?siteOid=…&role=…`
 * adapter response per api-surface.md row 12.
 */

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

export interface StudyUser {
  id: string
  username: string
  displayName: string
  email: string | null
  role: UserRole
  /** Site label, or null for study-wide roles (e.g. Data Manager). */
  siteLabel: string | null
  auth: UserAuth
  /** ISO instant of last login, or null when never logged in. */
  lastLoginAt: string | null
  active: boolean
}

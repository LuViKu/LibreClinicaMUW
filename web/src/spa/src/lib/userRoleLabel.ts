/**
 * How the SPA names a user's role on a study.
 *
 * The legacy data entry roles `ra` and `ra2` reach the SPA projected as
 * Investigator, which they are not: an investigator may sign. Wherever a
 * user's role is shown, they are named for what they are, as in the roles
 * dialog.
 */
import type { LegacyRole, UserRole } from '@/types/user'

/** The i18n key naming the role of a user or of a role binding. */
export function userRoleLabelKey(user: { role: UserRole; legacyRole?: LegacyRole | null }): string {
  return user.legacyRole
    ? `manageUsers.roles.legacyRole.${user.legacyRole}`
    : `manageUsers.role.${user.role}`
}

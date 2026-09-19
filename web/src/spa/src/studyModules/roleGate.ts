/**
 * Whether an injection entry is for any of the operator's roles.
 *
 * <p>An entry that names no roles is for everyone — the behaviour every
 * existing entry relied on. One that names roles is shown only to an
 * operator holding at least one of them, so a home card cannot lead to a
 * route its reader is not allowed to enter.
 */
import type { UserRole } from '@/types/auth'

export function entryAllowsRoles(
  entry: { allowedRoles?: UserRole[] },
  roles: UserRole[],
): boolean {
  if (!entry.allowedRoles || entry.allowedRoles.length === 0) return true
  return entry.allowedRoles.some((r) => roles.includes(r))
}

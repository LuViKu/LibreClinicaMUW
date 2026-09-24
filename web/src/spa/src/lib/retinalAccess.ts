/**
 * Who may open the retinal metrics pages — the one answer the router guard,
 * the results table's link and any future entry point share.
 *
 * The rule was written for nAMD: the standalone job view shows the AI's
 * reading (fluid volumes, layer thicknesses), and a treating physician who
 * sees it would be influenced in the very decision the trial measures. So
 * the Investigator and the CRC (who inherits Investigator) were excluded
 * outright, with server-side masking behind it.
 *
 * A screening study has no such decision — there the physician *is* the
 * reader of the AI output. Since 2026-09-24 the study says which it is
 * through the `ai.blinding.enabled` setting (default on), and this helper
 * reads it off the active study the way `/me` hands it over.
 */
import type { AuthenticatedUser, UserRole } from '@/types/auth'

/**
 * Every role the user holds in the active study — the array when the
 * binding carries one, else the singular projection, else the top-level
 * role. The same precedence the router guard applies.
 */
export function rolesOf(user: AuthenticatedUser | null | undefined): UserRole[] {
  const active = user?.activeStudy
  if (active?.roles && active.roles.length > 0) return [...active.roles]
  if (active?.role) return [active.role]
  return user?.role ? [user.role] : []
}

/** {@link canViewRetinalMetrics} for the signed-in user as the auth store holds them. */
export function userMayViewRetinalMetrics(user: AuthenticatedUser | null | undefined): boolean {
  return canViewRetinalMetrics(rolesOf(user), user?.activeStudy?.settings)
}

export const AI_BLINDING_SETTING = 'ai.blinding.enabled'

/** Roles the blinding never applied to. */
const UNBLINDED_ROLES: ReadonlySet<UserRole> = new Set(['Monitor', 'Data Manager', 'Administrator'])

/** Roles blinded by default — the treating clinicians. */
const TREATING_ROLES: ReadonlySet<UserRole> = new Set(['Investigator', 'CRC'])

/**
 * @param roles    every role the user holds in the active study
 * @param settings the active study's resolved settings, as `/me` reports them
 */
export function canViewRetinalMetrics(
  roles: readonly UserRole[],
  settings: Readonly<Record<string, string>> | null | undefined,
): boolean {
  if (roles.some((r) => UNBLINDED_ROLES.has(r))) return true
  if (!roles.some((r) => TREATING_ROLES.has(r))) return false
  return isBlindingOff(settings)
}

/** Only an explicit `false` switches the rule off; absent means blinded. */
export function isBlindingOff(settings: Readonly<Record<string, string>> | null | undefined): boolean {
  const v = settings?.[AI_BLINDING_SETTING]
  return typeof v === 'string' && v.trim().toLowerCase() === 'false'
}

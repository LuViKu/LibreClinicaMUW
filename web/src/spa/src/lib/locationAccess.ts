/**
 * Who may manage the uploaders of a location — the one answer the router
 * guard, the Home card and the page itself share.
 *
 * The right is not a study role. A system administrator has it for every
 * available location; a Standort-Admin has it for the locations a system
 * administrator granted it for; a study role does not include it. The
 * server works that out and tells the SPA in `GET /me` as
 * `manageableLocations`, the same way it reports the blinding setting the
 * retinal metrics routes read (`lib/retinalAccess`). An empty list means
 * there is nothing for this session to manage.
 *
 * Beside it, `/me` reports the deployment's location mode (`locationMode`),
 * which decides whether a second location can exist at all and whether
 * devices still upload without a token.
 */
import type { AuthenticatedUser, LocationMode, ManageableLocation } from '@/types/auth'

/** The locations the session may manage, as `/me` lists them; empty without a user or a list. */
export function manageableLocationsOf(user: AuthenticatedUser | null | undefined): ManageableLocation[] {
  const list = user?.manageableLocations
  return Array.isArray(list) ? list : []
}

/** {@link manageableLocationsOf} is not empty: the session may open the Uploaders page. */
export function userMayManageUploaders(user: AuthenticatedUser | null | undefined): boolean {
  return manageableLocationsOf(user).length > 0
}

/** A value `/me` may send as `locationMode`; anything else is not one. */
export function isLocationMode(value: unknown): value is LocationMode {
  return value === 'single' || value === 'multi'
}

/**
 * The deployment's location mode, as `/me` reports it. Anything but `multi`
 * reads as `single`, an absent field included: a server from before the field
 * existed knew no other mode, and `single` is the mode that offers less.
 */
export function locationModeOf(user: AuthenticatedUser | null | undefined): LocationMode {
  return user?.locationMode === 'multi' ? 'multi' : 'single'
}

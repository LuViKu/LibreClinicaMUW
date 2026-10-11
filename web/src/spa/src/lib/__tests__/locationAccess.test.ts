import { describe, expect, it } from 'vitest'

import { manageableLocationsOf, userMayManageUploaders } from '@/lib/locationAccess'
import type { AuthenticatedUser, ManageableLocation } from '@/types/auth'

const MUW: ManageableLocation = { id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' }
const GRAZ: ManageableLocation = { id: 2, code: 'GRAZ', name: 'Klinik Graz' }

/** Only the part of /me these helpers read. */
function user(extra: Partial<AuthenticatedUser>): AuthenticatedUser {
  return { username: 'u', role: 'Data Manager', ...extra } as AuthenticatedUser
}

describe('manageableLocationsOf', () => {
  it('lists the locations /me names, in its order', () => {
    expect(manageableLocationsOf(user({ manageableLocations: [GRAZ, MUW] }))).toEqual([GRAZ, MUW])
  })

  it('is empty for no user, for an answer without the field and for a null one', () => {
    expect(manageableLocationsOf(null)).toEqual([])
    expect(manageableLocationsOf(undefined)).toEqual([])
    expect(manageableLocationsOf(user({}))).toEqual([])
    expect(manageableLocationsOf(user({ manageableLocations: null as unknown as ManageableLocation[] }))).toEqual([])
  })
})

describe('userMayManageUploaders', () => {
  it('admits whoever may manage at least one location', () => {
    expect(userMayManageUploaders(user({ manageableLocations: [MUW] }))).toBe(true)
    expect(userMayManageUploaders(user({ manageableLocations: [MUW, GRAZ] }))).toBe(true)
  })

  it('refuses an empty list', () => {
    expect(userMayManageUploaders(user({ manageableLocations: [] }))).toBe(false)
  })

  it('refuses a session that has no list, and no session', () => {
    expect(userMayManageUploaders(user({}))).toBe(false)
    expect(userMayManageUploaders(null)).toBe(false)
    expect(userMayManageUploaders(undefined)).toBe(false)
  })

  it('does not look at the role or the account type: the list is the right', () => {
    expect(userMayManageUploaders(user({ role: 'Investigator', manageableLocations: [MUW] }))).toBe(true)
    // A system administrator with nothing to manage (every location disabled) has no page to open.
    expect(userMayManageUploaders(user({ userType: 'SYSADMIN', manageableLocations: [] }))).toBe(false)
  })
})

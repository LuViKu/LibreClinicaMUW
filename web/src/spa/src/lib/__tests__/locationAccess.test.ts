import { describe, expect, it } from 'vitest'

import { isLocationMode, locationModeOf, manageableLocationsOf, userMayManageUploaders } from '@/lib/locationAccess'
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

describe('locationModeOf', () => {
  it('reads the mode /me reports', () => {
    expect(locationModeOf(user({ locationMode: 'multi' }))).toBe('multi')
    expect(locationModeOf(user({ locationMode: 'single' }))).toBe('single')
  })

  it('reads an answer from before the field existed, and no session, as single', () => {
    expect(locationModeOf(user({}))).toBe('single')
    expect(locationModeOf(null)).toBe('single')
    expect(locationModeOf(undefined)).toBe('single')
  })

  it('reads a value it does not know as single, the mode that offers less', () => {
    expect(locationModeOf(user({ locationMode: 'MULTI' as unknown as 'multi' }))).toBe('single')
  })
})

describe('isLocationMode', () => {
  it('knows the two modes and nothing else', () => {
    expect(isLocationMode('single')).toBe(true)
    expect(isLocationMode('multi')).toBe(true)
    expect(isLocationMode(undefined)).toBe(false)
    expect(isLocationMode(null)).toBe(false)
    expect(isLocationMode('both')).toBe(false)
  })
})

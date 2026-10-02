import { describe, it, expect } from 'vitest'
import { userRoleLabelKey } from '@/lib/userRoleLabel'

describe('userRoleLabelKey', () => {
  it('names a legacy data entry role, not the Investigator it is projected as', () => {
    expect(userRoleLabelKey({ role: 'Investigator', legacyRole: 'ra' })).toBe('manageUsers.roles.legacyRole.ra')
    expect(userRoleLabelKey({ role: 'Investigator', legacyRole: 'ra2' })).toBe('manageUsers.roles.legacyRole.ra2')
  })

  it('names every other role by the role', () => {
    expect(userRoleLabelKey({ role: 'Investigator' })).toBe('manageUsers.role.Investigator')
    expect(userRoleLabelKey({ role: 'Data Manager', legacyRole: null })).toBe('manageUsers.role.Data Manager')
  })
})

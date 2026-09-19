import { describe, it, expect } from 'vitest'

import { entryAllowsRoles } from '@/studyModules/roleGate'

describe('entryAllowsRoles', () => {
  it('an entry naming no roles is for everyone — the pre-existing behaviour', () => {
    expect(entryAllowsRoles({}, ['Monitor'])).toBe(true)
    expect(entryAllowsRoles({ allowedRoles: [] }, ['Monitor'])).toBe(true)
  })

  it('an entry naming roles shows only to an operator holding one of them', () => {
    const entry = { allowedRoles: ['Investigator', 'CRC'] as const }
    expect(entryAllowsRoles({ allowedRoles: [...entry.allowedRoles] }, ['CRC'])).toBe(true)
    expect(entryAllowsRoles({ allowedRoles: [...entry.allowedRoles] }, ['Monitor'])).toBe(false)
    expect(entryAllowsRoles({ allowedRoles: [...entry.allowedRoles] }, ['Monitor', 'Investigator'])).toBe(true)
  })
})

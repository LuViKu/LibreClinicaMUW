import { describe, it, expect } from 'vitest'

import { mayBuildStudy, mayEditExports, maySysadminOnly } from '@/lib/studyBuildAccess'

/**
 * Each predicate mirrors a backend gate (decision D5): the write controls show
 * exactly where the API will not answer 403.
 */
describe('studyBuildAccess', () => {
  it('lets director, coordinator and administrator build a study, nobody else', () => {
    expect(mayBuildStudy('Data Manager')).toBe(true)
    expect(mayBuildStudy('CRC')).toBe(true)
    expect(mayBuildStudy('Administrator')).toBe(true)
    expect(mayBuildStudy('Monitor')).toBe(false)
    expect(mayBuildStudy('Investigator')).toBe(false)
    expect(mayBuildStudy(undefined)).toBe(false)
  })

  it('keeps the sysadmin-only actions for the administrator', () => {
    expect(maySysadminOnly('Administrator')).toBe(true)
    expect(maySysadminOnly('Data Manager')).toBe(false)
    expect(maySysadminOnly('CRC')).toBe(false)
    expect(maySysadminOnly('Monitor')).toBe(false)
  })

  it('lets a monitor edit exports as well as the builders', () => {
    expect(mayEditExports('CRC')).toBe(true)
    expect(mayEditExports('Data Manager')).toBe(true)
    expect(mayEditExports('Monitor')).toBe(true)
    expect(mayEditExports('Administrator')).toBe(true)
    expect(mayEditExports('Investigator')).toBe(false)
  })
})

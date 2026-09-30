import { describe, it, expect } from 'vitest'
import { canDownloadStudyMetadata, userMayDownloadStudyMetadata } from '@/lib/studyMetadataAccess'
import type { AuthenticatedUser } from '@/types/auth'

describe('canDownloadStudyMetadata', () => {
  it('admits every role that may view the study data', () => {
    for (const role of ['Investigator', 'CRC', 'Monitor', 'Data Manager'] as const) {
      expect(canDownloadStudyMetadata(false, [role])).toBe(true)
    }
  })

  it('does not admit the study-level Administrator role on its own', () => {
    expect(canDownloadStudyMetadata(false, ['Administrator'])).toBe(false)
    expect(canDownloadStudyMetadata(false, [])).toBe(false)
  })

  it('admits a system administrator whatever the roles', () => {
    expect(canDownloadStudyMetadata(true, ['Administrator'])).toBe(true)
    expect(canDownloadStudyMetadata(true, [])).toBe(true)
  })
})

describe('userMayDownloadStudyMetadata', () => {
  const user = (extra: Partial<AuthenticatedUser>) => ({ username: 'u', role: 'Monitor', ...extra }) as AuthenticatedUser

  it('needs an active study', () => {
    expect(userMayDownloadStudyMetadata(user({ activeStudy: null }))).toBe(false)
    expect(userMayDownloadStudyMetadata(null)).toBe(false)
  })

  it('reads the roles of the active study and the account type', () => {
    const study = { id: 1, oid: 'S1', name: 'S', isSite: false, role: 'Administrator', roles: ['Administrator'] }
    expect(userMayDownloadStudyMetadata(user({ activeStudy: study } as Partial<AuthenticatedUser>))).toBe(false)
    expect(userMayDownloadStudyMetadata(
      user({ activeStudy: study, userType: 'TECHADMIN' } as Partial<AuthenticatedUser>),
    )).toBe(true)
    expect(userMayDownloadStudyMetadata(
      user({ activeStudy: { ...study, role: 'Investigator', roles: ['Investigator'] } } as Partial<AuthenticatedUser>),
    )).toBe(true)
  })
})

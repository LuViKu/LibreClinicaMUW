/**
 * A Monitor reaches the subject, read-only. The subject page used to admit
 * Investigator and Administrator only, so every subject link a Monitor saw
 * bounced them home.
 */
import { describe, expect, it } from 'vitest'

import router, { guard } from '../index'
import type { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/types/auth'

type Auth = ReturnType<typeof useAuthStore>

function authFor(...roles: UserRole[]): Auth {
  return {
    isAnonymous: false,
    isAuthenticated: true,
    needsProfile: false,
    needsStudyPick: false,
    needsPasswordChange: false,
    user: {
      role: roles[0],
      activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study', role: roles[0], roles, enabledModules: [] },
    },
  } as unknown as Auth
}

function opens(auth: Auth, path: string): boolean {
  return guard(auth, router.resolve(path)) === true
}

describe('the Monitor on subject routes', () => {
  it('opens a subject', () => {
    expect(opens(authFor('Monitor'), '/subjects/M-001')).toBe(true)
  })

  it('opens the read-only CRF', () => {
    expect(opens(authFor('Monitor'), '/event-crfs/9/readonly')).toBe(true)
  })

  it('still cannot open data entry, sign a subject or add one', () => {
    const monitor = authFor('Monitor')
    expect(opens(monitor, '/event-crfs/9')).toBe(false)
    expect(opens(monitor, '/subjects/M-001/sign')).toBe(false)
    expect(opens(monitor, '/subjects/new')).toBe(false)
  })
})

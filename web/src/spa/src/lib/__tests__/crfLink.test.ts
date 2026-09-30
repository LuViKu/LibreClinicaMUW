/**
 * Where a CRF link goes: data entry for a role that enters data, the
 * read-only CRF for a Monitor. Every link must open for the role it is
 * shown to, so each target is checked against the router's own guard.
 */
import { describe, expect, it } from 'vitest'

import router, { guard } from '@/router'
import type { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/types/auth'
import { eventCrfLink, viewsCrfReadOnly } from '@/lib/crfLink'

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

describe('eventCrfLink', () => {
  it('sends a Monitor to the read-only CRF, at the item', () => {
    expect(eventCrfLink(['Monitor'], '9', 'I_HEIGHT_CM')).toBe('/event-crfs/9/readonly?item=I_HEIGHT_CM')
    expect(eventCrfLink(['Monitor'], '9')).toBe('/event-crfs/9/readonly')
  })

  it('sends the roles that enter data to data entry', () => {
    for (const role of ['Investigator', 'CRC', 'Administrator'] as const) {
      expect(eventCrfLink([role], '9'), role).toBe('/event-crfs/9')
    }
  })

  it('sends a Monitor who also enters data in the study to data entry', () => {
    expect(viewsCrfReadOnly(['Monitor', 'Investigator'])).toBe(false)
    expect(eventCrfLink(['Monitor', 'Investigator'], '9')).toBe('/event-crfs/9')
  })

  it('never links a role to a CRF route it cannot open', () => {
    for (const role of ['Investigator', 'CRC', 'Monitor', 'Administrator'] as const) {
      const target = router.resolve(eventCrfLink([role], '9', 'I_HEIGHT_CM'))
      expect(guard(authFor(role), target), role).toBe(true)
    }
  })
})

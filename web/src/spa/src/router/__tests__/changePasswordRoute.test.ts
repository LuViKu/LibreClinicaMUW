/**
 * /change-password is where the guard forces a first-login or expired
 * password, and where a local account goes by choice from the profile
 * menu. An SSO or LDAP account, whose password belongs to the identity
 * provider or directory, has nothing to change here and is sent home.
 */
import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'

import router, { guard } from '../index'
import type { useAuthStore } from '@/stores/auth'

type Auth = ReturnType<typeof useAuthStore>

function authFor(extra: {
  isAnonymous?: boolean
  needsProfile?: boolean
  needsPasswordChange?: boolean
  canChangeOwnPassword?: boolean
}): Auth {
  return {
    isAnonymous: extra.isAnonymous ?? false,
    isAuthenticated: !(extra.isAnonymous ?? false),
    needsProfile: extra.needsProfile ?? false,
    needsStudyPick: false,
    needsPasswordChange: extra.needsPasswordChange ?? false,
    canChangeOwnPassword: extra.canChangeOwnPassword ?? false,
    user: { role: 'Investigator', activeStudy: { oid: 'S_X', name: 'Study', role: 'Investigator', roles: ['Investigator'] } },
  } as unknown as Auth
}

function changePassword(): RouteLocationNormalized {
  const r = router.getRoutes().find((x) => x.name === 'change-password')
  if (!r) throw new Error('route change-password not registered')
  return {
    name: 'change-password', meta: r.meta, fullPath: r.path, path: r.path, params: {}, query: {}, hash: '',
    matched: [], redirectedFrom: undefined,
  } as unknown as RouteLocationNormalized
}

describe('the change-password route', () => {
  it('opens for a local account by choice', () => {
    expect(guard(authFor({ canChangeOwnPassword: true }), changePassword())).toBe(true)
  })

  it('opens when the password change is forced', () => {
    expect(guard(authFor({ needsPasswordChange: true, canChangeOwnPassword: true }), changePassword())).toBe(true)
  })

  it('sends an SSO or LDAP account home', () => {
    expect(guard(authFor({ canChangeOwnPassword: false }), changePassword())).toEqual({ name: 'home' })
  })

  it('sends an anonymous visitor to the login and an incomplete profile to the first login', () => {
    expect(guard(authFor({ isAnonymous: true }), changePassword())).toEqual({ name: 'login' })
    expect(guard(authFor({ needsProfile: true, canChangeOwnPassword: true }), changePassword())).toEqual({ name: 'first-login' })
  })
})

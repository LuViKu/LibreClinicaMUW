/**
 * The Uploaders page (/uploaders) is for whoever may manage the uploaders
 * of at least one location: a system administrator (every location) or a
 * Standort-Admin (their own). That is no study role, so the route carries
 * no `role` meta; it carries `manageUploaders`, and the guard asks /me's
 * `manageableLocations` the way the retinal metrics routes ask the study's
 * blinding setting.
 *
 * It is also the one route a session may open without a bound study: a
 * Standort-Admin is typically a site's IT contact who holds no study role at
 * all and so has no study to pick. Nobody else gets that exception.
 */
import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'

import router, { guard } from '../index'
import type { useAuthStore } from '@/stores/auth'

type Auth = ReturnType<typeof useAuthStore>

const MUW = { id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' }
const GRAZ = { id: 2, code: 'GRAZ', name: 'Klinik Graz' }

function authFor(
  role: string,
  manageableLocations: unknown,
  extra: {
    userType?: string
    activeStudy?: unknown
    needsPasswordChange?: boolean
    needsStudyPick?: boolean
    needsProfile?: boolean
  } = {},
): Auth {
  const activeStudy = 'activeStudy' in extra
    ? extra.activeStudy
    : { oid: 'S_X', name: 'Study', role, roles: [role], enabledModules: [] }
  return {
    isAnonymous: false,
    isAuthenticated: true,
    needsProfile: extra.needsProfile ?? false,
    needsStudyPick: extra.needsStudyPick ?? false,
    needsPasswordChange: extra.needsPasswordChange ?? false,
    user: { role, userType: extra.userType, manageableLocations, activeStudy },
  } as unknown as Auth
}

function routeNamed(name: string): RouteLocationNormalized {
  const r = router.getRoutes().find((x) => x.name === name)
  if (!r) throw new Error(`route ${name} not registered`)
  return {
    name, meta: r.meta, fullPath: r.path, path: r.path, params: {}, query: {}, hash: '',
    matched: [], redirectedFrom: undefined,
  } as unknown as RouteLocationNormalized
}

describe('the uploaders route', () => {
  it('answers /uploaders as uploaders, behind a login, with a title', () => {
    const r = router.resolve('/uploaders')
    expect(r.name).toBe('uploaders')
    expect(r.meta.public).toBeUndefined()
    expect(r.meta.title).toBe('Uploader und Tokens')
  })

  it('is gated by the right to manage uploaders, not by a role', () => {
    const meta = routeNamed('uploaders').meta
    expect(meta.manageUploaders).toBe(true)
    expect(meta.role).toBeUndefined()
  })

  it('lets a system administrator in', () => {
    expect(guard(authFor('Administrator', [MUW, GRAZ], { userType: 'SYSADMIN' }), routeNamed('uploaders'))).toBe(true)
  })

  it.each(['Data Manager', 'CRC', 'Investigator', 'Monitor', 'Administrator'])(
    'lets a Standort-Admin in whatever their study role is (%s)',
    (role) => {
      expect(guard(authFor(role, [MUW]), routeNamed('uploaders'))).toBe(true)
    },
  )

  it.each(['Data Manager', 'CRC', 'Investigator', 'Monitor', 'Administrator'])(
    'sends a %s who manages no location home, like the other admin routes',
    (role) => {
      expect(guard(authFor(role, []), routeNamed('uploaders'))).toEqual({ name: 'home' })
    },
  )

  it('sends a session whose /me has no list home', () => {
    expect(guard(authFor('Data Manager', undefined), routeNamed('uploaders'))).toEqual({ name: 'home' })
    expect(guard(authFor('Data Manager', null), routeNamed('uploaders'))).toEqual({ name: 'home' })
  })

  it('does not take a system administrator’s account type for the right: the list decides', () => {
    expect(guard(authFor('Administrator', [], { userType: 'TECHADMIN' }), routeNamed('uploaders')))
      .toEqual({ name: 'home' })
  })

  it('sends someone who is not signed in to the login page, with the way back', () => {
    const anonymous = { isAnonymous: true, isAuthenticated: false, user: null } as unknown as Auth
    const to = { ...routeNamed('uploaders'), fullPath: '/uploaders' }
    expect(guard(anonymous, to)).toEqual({ name: 'login', query: { returnTo: '/uploaders' } })
  })

  it('lets a Standort-Admin without a bound study in: the picker has nothing for them', () => {
    const noStudy = authFor('Data Manager', [MUW], { activeStudy: null, needsStudyPick: true })
    expect(guard(noStudy, routeNamed('uploaders'))).toBe(true)
  })

  it('still makes a stale password change first', () => {
    const stale = authFor('Data Manager', [MUW], { needsPasswordChange: true })
    expect(guard(stale, routeNamed('uploaders'))).toEqual({ name: 'change-password' })
  })

  it('does not open any other route that asks for a role', () => {
    // The right to manage uploaders is not a role: it opens its own page and nothing else.
    expect(guard(authFor('Investigator', [MUW]), routeNamed('admin-locations'))).toEqual({ name: 'home' })
    expect(guard(authFor('Investigator', [MUW]), routeNamed('build-study'))).toEqual({ name: 'home' })
  })
})

/**
 * Reaching the Uploaders page without a bound study.
 *
 * The plan names a site's IT contact as the typical Standort-Admin, and such a
 * person holds no study role at all: they have no study to pick, and until
 * now the global guard would have kept them on an empty study picker. The one
 * exception is therefore narrow: a session whose `/me` lists at least one
 * manageable location may open the route marked `manageUploaders` without a
 * bound study. Pinned here are both halves: they get in, and nobody else
 * does, nor does anything else open to them.
 *
 * What the backend sends for such a person (read from MeApiController): the
 * sentinel role "Investigator" and no active study, so the sessions below
 * carry exactly that.
 */
import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'

import router, { guard } from '../index'
import type { useAuthStore } from '@/stores/auth'

type Auth = ReturnType<typeof useAuthStore>

const MUW = { id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' }

interface Session {
  /** The top-level role /me projects. */
  role: string
  userType?: string
  manageableLocations: unknown
  /** True when nothing is bound: the state after a fresh login of someone with no stored study. */
  noStudy?: boolean
  needsPasswordChange?: boolean
  needsProfile?: boolean
}

function session(s: Session): Auth {
  const noStudy = s.noStudy ?? true
  return {
    isAnonymous: false,
    isAuthenticated: true,
    needsProfile: s.needsProfile ?? false,
    needsStudyPick: noStudy,
    needsPasswordChange: s.needsPasswordChange ?? false,
    user: {
      role: s.role,
      userType: s.userType ?? 'USER',
      manageableLocations: s.manageableLocations,
      activeStudy: noStudy ? null : { oid: 'S_X', name: 'Study', role: s.role, roles: [s.role], enabledModules: [] },
    },
  } as unknown as Auth
}

/** A site's IT contact: no study role, so /me says "Investigator" (a sentinel) and binds nothing. */
const roleLessStandortAdmin = () => session({ role: 'Investigator', manageableLocations: [MUW] })
/** The same person without the right: an account with no study role and nothing else. */
const roleLessPlainUser = () => session({ role: 'Investigator', manageableLocations: [] })

function routeNamed(name: string): RouteLocationNormalized {
  const r = router.getRoutes().find((x) => x.name === name)
  if (!r) throw new Error(`route ${name} not registered`)
  return {
    name, meta: r.meta, fullPath: r.path, path: r.path, params: {}, query: {}, hash: '',
    matched: [], redirectedFrom: undefined,
  } as unknown as RouteLocationNormalized
}

/** Routes the guard treats on their own terms (login, first login, picker, password, public, catch-all). */
const SPECIAL = new Set(['login', 'first-login', 'pick-study', 'change-password', 'not-found'])

/** Every protected route a session could be sent away from: all but the special ones and the Uploaders page. */
function protectedRoutes(): string[] {
  return router
    .getRoutes()
    .filter((r) => typeof r.name === 'string' && r.meta.public !== true && !SPECIAL.has(r.name as string))
    .map((r) => r.name as string)
    .filter((name) => name !== 'uploaders')
}

describe('opening the Uploaders page without a bound study', () => {
  it('lets a Standort-Admin who holds no study role in', () => {
    expect(guard(roleLessStandortAdmin(), routeNamed('uploaders'))).toBe(true)
  })

  it('lets a system administrator in, and a Standort-Admin who does have studies but has not picked one', () => {
    expect(guard(session({ role: 'Administrator', userType: 'SYSADMIN', manageableLocations: [MUW] }), routeNamed('uploaders')))
      .toBe(true)
    expect(guard(session({ role: 'Data Manager', manageableLocations: [MUW] }), routeNamed('uploaders'))).toBe(true)
  })

  it('does not let a user without the right in: the study picker stays in the way, as today', () => {
    // An ordinary user with studies who has not picked one yet.
    expect(guard(session({ role: 'Data Manager', manageableLocations: [] }), routeNamed('uploaders')))
      .toEqual({ name: 'pick-study' })
    // A user with no study role and no right: stuck on the picker, exactly as before.
    expect(guard(roleLessPlainUser(), routeNamed('uploaders'))).toEqual({ name: 'pick-study' })
    // A session whose /me has no list at all (an older server) counts as no right.
    expect(guard(session({ role: 'Investigator', manageableLocations: undefined }), routeNamed('uploaders')))
      .toEqual({ name: 'pick-study' })
    expect(guard(session({ role: 'Investigator', manageableLocations: null }), routeNamed('uploaders')))
      .toEqual({ name: 'pick-study' })
  })

  it('does not open a single other route to a Standort-Admin without a bound study', () => {
    const names = protectedRoutes()
    // Guard against the loop silently checking nothing.
    expect(names.length).toBeGreaterThan(30)
    expect(names).toEqual(expect.arrayContaining(['home', 'subject-matrix', 'admin-locations', 'ingest-inbox', 'build-study']))
    for (const name of names) {
      expect(guard(roleLessStandortAdmin(), routeNamed(name)), name).toEqual({ name: 'pick-study' })
    }
  })

  it('keeps every other session where it was: nothing but the Uploaders page changed', () => {
    const sessions: Array<[string, () => Auth]> = [
      ['system administrator', () => session({ role: 'Administrator', userType: 'SYSADMIN', manageableLocations: [MUW] })],
      ['ordinary user with studies', () => session({ role: 'Data Manager', manageableLocations: [] })],
      ['user with no study role', roleLessPlainUser],
    ]
    for (const [label, make] of sessions) {
      for (const name of protectedRoutes()) {
        expect(guard(make(), routeNamed(name)), `${label} -> ${name}`).toEqual({ name: 'pick-study' })
      }
    }
  })

  it('still lets every session reach the study picker itself, where the Standort-Admin is sent on', () => {
    expect(guard(roleLessStandortAdmin(), routeNamed('pick-study'))).toBe(true)
    expect(guard(roleLessPlainUser(), routeNamed('pick-study'))).toBe(true)
  })

  it('sends someone who is not signed in to the login page, with the way back', () => {
    const anonymous = { isAnonymous: true, isAuthenticated: false, user: null } as unknown as Auth
    const to = { ...routeNamed('uploaders'), fullPath: '/uploaders' }
    expect(guard(anonymous, to)).toEqual({ name: 'login', query: { returnTo: '/uploaders' } })
  })

  it('still asks for the profile and for a stale password first', () => {
    const profile = session({ role: 'Investigator', manageableLocations: [MUW], needsProfile: true })
    expect(guard(profile, routeNamed('uploaders'))).toEqual({ name: 'first-login' })
    const stale = session({ role: 'Investigator', manageableLocations: [MUW], needsPasswordChange: true })
    expect(guard(stale, routeNamed('uploaders'))).toEqual({ name: 'change-password' })
  })

  it('treats a Standort-Admin with a bound study as before', () => {
    const bound = session({ role: 'Data Manager', manageableLocations: [MUW], noStudy: false })
    expect(guard(bound, routeNamed('uploaders'))).toBe(true)
    expect(guard(bound, routeNamed('build-study'))).toBe(true)
    expect(guard(bound, routeNamed('admin-locations'))).toEqual({ name: 'home' })
  })
})

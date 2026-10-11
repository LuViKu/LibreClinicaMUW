/**
 * System → Standorte is a page of the System section: registered under
 * /admin like its neighbours, behind the same Administrator gate. The API
 * admits system administrators only; the route keeps every other role
 * from landing on a page that could only show them a refusal.
 */
import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'

import router, { guard } from '../index'
import type { useAuthStore } from '@/stores/auth'

type Auth = ReturnType<typeof useAuthStore>

function authFor(role: string): Auth {
  return {
    isAnonymous: false,
    isAuthenticated: true,
    needsProfile: false,
    needsStudyPick: false,
    needsPasswordChange: false,
    user: {
      role,
      activeStudy: { oid: 'S_X', name: 'Study', role, roles: [role], enabledModules: [] },
    },
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

const SYSTEM_PAGES = [
  'admin-system-status',
  'admin-studies',
  'admin-locations',
  'admin-login-history',
  'admin-password-policy',
  'admin-config',
  'admin-jobs',
] as const

describe('the admin locations route', () => {
  it('answers /admin/locations as admin-locations, behind a login', () => {
    const r = router.resolve('/admin/locations')
    expect(r.name).toBe('admin-locations')
    expect(r.meta.public).toBeUndefined()
    expect(r.meta.title).toBe('Standorte')
  })

  it.each(SYSTEM_PAGES)('%s admits the Administrator only', (name) => {
    expect(routeNamed(name).meta.role).toBe('Administrator')
  })

  it('lets an Administrator in', () => {
    expect(guard(authFor('Administrator'), routeNamed('admin-locations'))).toBe(true)
  })

  it.each(['Data Manager', 'CRC', 'Investigator', 'Monitor'])('sends a %s home', (role) => {
    expect(guard(authFor(role), routeNamed('admin-locations'))).toEqual({ name: 'home' })
  })

  it('sends someone who is not signed in to the login page, with the way back', () => {
    const anonymous = { isAnonymous: true, isAuthenticated: false, user: null } as unknown as Auth
    const to = { ...routeNamed('admin-locations'), fullPath: '/admin/locations' }
    expect(guard(anonymous, to)).toEqual({ name: 'login', query: { returnTo: '/admin/locations' } })
  })
})

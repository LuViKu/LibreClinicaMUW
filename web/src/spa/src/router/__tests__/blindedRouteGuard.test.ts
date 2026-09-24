/**
 * 2026-09-24 — trial blinding, per study.
 *
 * The two retinal metrics routes exclude the treating roles by their role
 * list. A study that has switched blinding off (ai.blinding.enabled =
 * false, reported by /me in activeStudy.settings) lifts that for those
 * routes only; nothing else about the guard changes.
 */
import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'

import router, { guard } from '../index'
import type { useAuthStore } from '@/stores/auth'

type Auth = ReturnType<typeof useAuthStore>

function authFor(role: string, settings: Record<string, string> | undefined): Auth {
  return {
    isAnonymous: false,
    isAuthenticated: true,
    needsProfile: false,
    needsStudyPick: false,
    needsPasswordChange: false,
    user: {
      role,
      activeStudy: { oid: 'S_HAE', name: 'HealthAEye', role, roles: [role], settings, enabledModules: [] },
    },
  } as unknown as Auth
}

function routeNamed(name: string): RouteLocationNormalized {
  const r = router.getRoutes().find((x) => x.name === name)
  if (!r) throw new Error(`route ${name} not registered`)
  return { name, meta: r.meta, fullPath: r.path, path: r.path, params: {}, query: {}, hash: '', matched: [], redirectedFrom: undefined } as unknown as RouteLocationNormalized
}

describe('blinded routes honour the study setting', () => {
  it.each(['retinal-job', 'retinal-job-by-subject'])('%s is marked blinded', (name) => {
    expect(routeNamed(name).meta.blinded).toBe(true)
  })

  it('bounces an Investigator home while blinding is on (or unset)', () => {
    expect(guard(authFor('Investigator', undefined), routeNamed('retinal-job'))).toEqual({ name: 'home' })
    expect(guard(authFor('Investigator', { 'ai.blinding.enabled': 'true' }), routeNamed('retinal-job')))
      .toEqual({ name: 'home' })
  })

  it('lets an Investigator through when the study switched blinding off', () => {
    expect(guard(authFor('Investigator', { 'ai.blinding.enabled': 'false' }), routeNamed('retinal-job')))
      .toBe(true)
    expect(guard(authFor('CRC', { 'ai.blinding.enabled': 'false' }), routeNamed('retinal-job-by-subject')))
      .toBe(true)
  })

  it('does not lift any other role gate', () => {
    // build-study is not a blinded route; the setting must not open it.
    expect(guard(authFor('Investigator', { 'ai.blinding.enabled': 'false' }), routeNamed('build-study')))
      .toEqual({ name: 'home' })
  })

  it('leaves the unblinded roles exactly as before', () => {
    expect(guard(authFor('Data Manager', undefined), routeNamed('retinal-job'))).toBe(true)
    expect(guard(authFor('Monitor', { 'ai.blinding.enabled': 'true' }), routeNamed('retinal-job'))).toBe(true)
  })
})

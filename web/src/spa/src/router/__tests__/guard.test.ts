/**
 * Phase E.6 — router-guard role-hierarchy spec.
 *
 * <p>Pins the guard's role-matching contract so a future tweak to the
 * legacy↔SPA RoleMapper or the {@code roleSatisfies} helper can't
 * silently regress role-gated visibility.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn().mockResolvedValue({}),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {},
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

import { guard } from '@/router/index'
import { useAuthStore } from '@/stores/auth'

function makeRoute(name: string, role?: string): RouteLocationNormalized {
  return {
    name,
    fullPath: `/${name}`,
    path: `/${name}`,
    hash: '',
    query: {},
    params: {},
    matched: [],
    meta: role ? { role } : {},
    redirectedFrom: undefined,
  } as unknown as RouteLocationNormalized
}

function authAs(role: string | null) {
  const auth = useAuthStore()
  if (role) {
    auth.user = {
      username: 'demo',
      displayName: 'Demo',
      email: null,
      role: role as 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC',
      siteLabel: null,
      source: 'local',
      mfaSatisfied: true,
      profileComplete: true,
      locale: null,
      timezone: null,
      activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
    }
    auth.state = 'authenticated'
  } else {
    auth.user = null
    auth.state = 'anonymous'
  }
  return auth
}

beforeEach(() => setActivePinia(createPinia()))

describe('router guard — role hierarchy', () => {
  it('lets Administrator into Data Manager routes', () => {
    const auth = authAs('Administrator')
    expect(guard(auth, makeRoute('manage-users', 'Data Manager'))).toBe(true)
  })

  it('lets Administrator into Investigator routes', () => {
    const auth = authAs('Administrator')
    expect(guard(auth, makeRoute('subject-matrix', 'Investigator'))).toBe(true)
  })

  it('lets Administrator into Monitor routes', () => {
    const auth = authAs('Administrator')
    expect(guard(auth, makeRoute('sdv', 'Monitor'))).toBe(true)
  })

  it('blocks Data Manager from Administrator-only routes', () => {
    const auth = authAs('Data Manager')
    expect(guard(auth, makeRoute('study-create', 'Administrator'))).toEqual({ name: 'home' })
  })

  it('blocks Investigator from Data Manager routes', () => {
    const auth = authAs('Investigator')
    expect(guard(auth, makeRoute('manage-users', 'Data Manager'))).toEqual({ name: 'home' })
  })

  it('lets CRC into Investigator routes', () => {
    const auth = authAs('CRC')
    expect(guard(auth, makeRoute('subject-matrix', 'Investigator'))).toBe(true)
  })

  it('blocks CRC from Data Manager routes', () => {
    const auth = authAs('CRC')
    expect(guard(auth, makeRoute('manage-users', 'Data Manager'))).toEqual({ name: 'home' })
  })

  it('redirects anonymous to login on protected routes', () => {
    const auth = authAs(null)
    expect(guard(auth, makeRoute('manage-users', 'Data Manager'))).toEqual({ name: 'login' })
  })
})

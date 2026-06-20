import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'

import { guard } from '../index'
import { useAuthStore } from '@/stores/auth'
import type { AuthenticatedUser } from '@/types/auth'

/**
 * Pluggable study-module SPI — router-guard contract.
 *
 * The framework stamps every module-supplied route with
 * {@code meta.studyModule = manifest.protocolType}. The shared
 * {@link guard} helper must reject navigations whose active study's
 * protocol_type doesn't match (redirect to /), pass when it matches,
 * and pass cleanly when the meta key is absent (so the rest of the
 * SPA's route table — none of which carries this meta — keeps working).
 *
 * We exercise {@code guard()} directly rather than driving the live
 * router so we can stub the auth store without booting the full
 * navigation pipeline.
 */

function makeTo(opts: {
  name?: string
  meta?: Record<string, unknown>
  fullPath?: string
  path?: string
}): RouteLocationNormalized {
  return {
    name: opts.name ?? 'mock-route',
    fullPath: opts.fullPath ?? opts.path ?? '/mock',
    path: opts.path ?? '/mock',
    query: {},
    params: {},
    hash: '',
    matched: [],
    redirectedFrom: undefined,
    meta: opts.meta ?? {},
  } as unknown as RouteLocationNormalized
}

function userBoundTo(protocolType: string | null | undefined): AuthenticatedUser {
  return {
    username: 'root',
    displayName: 'Root',
    email: null,
    role: 'Investigator',
    roles: ['Investigator'],
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: 'de-AT',
    timezone: 'Europe/Vienna',
    mustChangePassword: false,
    passwordChangeReason: null,
    activeStudy: protocolType === undefined
      ? null
      : {
          id: 1,
          oid: 'S_DEFAULTS1',
          name: 'Default Study',
          uniqueIdentifier: 'D',
          isSite: false,
          roles: ['Investigator'],
          protocolType,
        },
  }
}

describe('router guard — meta.studyModule', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('passes through when meta.studyModule is absent', () => {
    const auth = useAuthStore()
    auth.user = userBoundTo(null)
    auth.state = 'authenticated'

    const to = makeTo({ name: 'home', path: '/' })
    expect(guard(auth, to)).toBe(true)
  })

  it('redirects to home when active study protocolType does not match', () => {
    const auth = useAuthStore()
    auth.user = userBoundTo('GA')
    auth.state = 'authenticated'

    const to = makeTo({
      name: 'namd-workspace',
      path: '/studies/S_DEFAULTS1/modules/namd',
      meta: { studyModule: 'NAMD' },
    })
    expect(guard(auth, to)).toEqual({ name: 'home' })
  })

  it('redirects when there is no active study at all', () => {
    const auth = useAuthStore()
    auth.user = userBoundTo(undefined) // null activeStudy
    auth.state = 'authenticated'

    const to = makeTo({
      name: 'namd-workspace',
      path: '/studies/S_DEFAULTS1/modules/namd',
      meta: { studyModule: 'NAMD' },
    })
    // The needsStudyPick branch fires before our module check because
    // a user with no active study can't navigate anywhere protected.
    // Either redirection is acceptable — we just confirm the
    // navigation does NOT pass.
    expect(guard(auth, to)).not.toBe(true)
  })

  it('passes when active study protocolType matches (case-insensitive)', () => {
    const auth = useAuthStore()
    auth.user = userBoundTo('nAMD')
    auth.state = 'authenticated'

    const to = makeTo({
      name: 'namd-workspace',
      path: '/studies/S_DEFAULTS1/modules/namd',
      meta: { studyModule: 'NAMD' },
    })
    expect(guard(auth, to)).toBe(true)
  })

  it('passes when both meta and study agree on lowercase', () => {
    const auth = useAuthStore()
    auth.user = userBoundTo('namd')
    auth.state = 'authenticated'

    const to = makeTo({
      name: 'namd-workspace',
      path: '/studies/S_DEFAULTS1/modules/namd',
      meta: { studyModule: 'namd' },
    })
    expect(guard(auth, to)).toBe(true)
  })
})

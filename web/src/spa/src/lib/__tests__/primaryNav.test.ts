import { describe, it, expect } from 'vitest'

import { primaryNavFor, primaryNavForSession, PRIMARY_NAV, PRIMARY_NAV_MAX } from '@/lib/primaryNav'
import type { UserRole } from '@/types/auth'

/**
 * Every link in the primary navigation must open for the role it is shown
 * to; a link that bounces off the route guard is worse than no link. The
 * gate here is the router's own role meta.
 */
const ROUTE_ROLES: Record<string, UserRole[]> = {
  '/': ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
  '/subjects': ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
  '/notes': ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
  '/due-visits': ['Data Manager', 'Investigator', 'Monitor', 'Administrator'],
  '/ingest-inbox': ['Data Manager', 'Investigator', 'Administrator'],
  '/sdv': ['Monitor', 'Data Manager', 'Administrator'],
  '/audit-log': ['Monitor', 'Data Manager', 'CRC', 'Administrator'],
  '/build-study': ['Data Manager', 'CRC', 'Administrator'],
  '/export': ['Data Manager', 'CRC', 'Administrator', 'Monitor'],
  '/manage-users': ['Administrator'],
  '/sites': ['Data Manager', 'CRC', 'Administrator'],
}

describe('primaryNavFor', () => {
  it('never links a role to a route it cannot enter', () => {
    for (const role of Object.keys(PRIMARY_NAV) as UserRole[]) {
      for (const item of primaryNavFor([role])) {
        expect(ROUTE_ROLES[item.to], `${role} → ${item.to}`).toContain(role)
      }
    }
  })

  it('starts with home and stays short', () => {
    const inv = primaryNavFor(['Investigator'])
    expect(inv[0].to).toBe('/')
    expect(inv.length).toBeLessThanOrEqual(PRIMARY_NAV_MAX)
    expect(inv.map((i) => i.to)).toEqual(['/', '/subjects', '/notes', '/due-visits', '/ingest-inbox'])
  })

  it('gives a CRC neither the inbox nor due visits link — their routes list no CRC role of their own', () => {
    const paths = primaryNavFor(['CRC']).map((i) => i.to)
    expect(paths).not.toContain('/ingest-inbox')
    expect(paths).not.toContain('/due-visits')
  })

  it('links a CRC to the study build and the export, which the backend lets a coordinator do', () => {
    const paths = primaryNavFor(['CRC']).map((i) => i.to)
    expect(paths).toContain('/build-study')
    expect(paths).toContain('/export')
  })

  it('unions a multi-role operator’s destinations, strongest role first, without repeats, capped', () => {
    const paths = primaryNavFor(['Investigator', 'Data Manager']).map((i) => i.to)
    expect(paths[1]).toBe('/build-study')
    expect(new Set(paths).size).toBe(paths.length)
    expect(paths.length).toBeLessThanOrEqual(PRIMARY_NAV_MAX)
  })

  it('is empty while the roles are unknown', () => {
    expect(primaryNavFor([])).toEqual([])
  })
})

/**
 * Before a study is bound every role-based link leads back to the study
 * picker. A Standort-Admin who holds no study role (a site's IT contact) has
 * no study to bind at all, and the backend gives them the sentinel role
 * "Investigator", whose links would be four dead ends; the Uploaders page is
 * the one place they can go.
 */
describe('primaryNavForSession', () => {
  const MANAGES = { hasActiveStudy: false, canManageUploaders: true }

  it('is the role-based navigation for anyone with a study bound, Standort-Admin or not', () => {
    for (const canManageUploaders of [false, true]) {
      expect(primaryNavForSession(['Data Manager'], { hasActiveStudy: true, canManageUploaders }))
        .toEqual(primaryNavFor(['Data Manager']))
    }
  })

  it('offers a Standort-Admin who has no study bound the Uploaders page alone', () => {
    const nav = primaryNavForSession(['Investigator'], MANAGES)
    expect(nav).toEqual([{ id: 'uploaders', to: '/uploaders', labelKey: 'uploaders.title' }])
  })

  it('does so whatever role the fallback projects, and while the roles are still unknown', () => {
    expect(primaryNavForSession(['Administrator'], MANAGES).map((i) => i.to)).toEqual(['/uploaders'])
    expect(primaryNavForSession([], MANAGES).map((i) => i.to)).toEqual(['/uploaders'])
  })

  it('leaves everyone else exactly as it was before a study is bound', () => {
    const none = { hasActiveStudy: false, canManageUploaders: false }
    expect(primaryNavForSession(['Investigator'], none)).toEqual(primaryNavFor(['Investigator']))
    expect(primaryNavForSession([], none)).toEqual([])
  })
})

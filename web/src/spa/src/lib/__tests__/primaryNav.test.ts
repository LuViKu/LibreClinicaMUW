import { describe, it, expect } from 'vitest'

import { primaryNavFor, PRIMARY_NAV, PRIMARY_NAV_MAX } from '@/lib/primaryNav'
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

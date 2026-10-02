/**
 * Phase E.6 follow-up (2026-06-10) — verify the Administrator role
 * is included on every route that was previously gated on a single
 * non-Administrator role. The original PR #100 added an implicit
 * "Administrator is a superset" clause to roleSatisfies(); the
 * codebase later chose the opposite architecture — explicit per-route
 * role arrays — but missed twelve routes during the migration. This
 * matrix locks in the correct config so future routes can't regress.
 *
 * If a future route legitimately should NOT grant Administrator
 * access, delete that entry from the matrix here AND leave the route
 * meta.role as 'Foo' as const (or a Foo-only array). The test will
 * still pass: the matrix only asserts inclusion for the routes it
 * names.
 */
import { describe, expect, it } from 'vitest'

import router, { roleSatisfies } from '../index'

const ROUTES_THAT_MUST_INCLUDE_ADMINISTRATOR = [
  'subject-new',
  'crf-entry',
  'sdv',
  'crf-readonly',
  'build-study',
  'event-definitions',
  'crf-library',
  'crf-detail',
  'crf-migration',
  'group-classes',
  'rules',
  'import-crf-data',
  'sign-subject',
  'subject-detail',
] as const

describe('router role gates — Administrator inclusion matrix', () => {
  it.each(ROUTES_THAT_MUST_INCLUDE_ADMINISTRATOR)(
    'route %s grants Administrator',
    (routeName) => {
      const route = router.getRoutes().find((r) => r.name === routeName)
      expect(route, `route '${routeName}' is not registered`).toBeDefined()

      const role = route!.meta.role
      const roles = Array.isArray(role) ? role : [role]
      expect(
        roles,
        `route '${routeName}' meta.role does not include 'Administrator' — pure sysadmin would be bounced to /home. ` +
          `Current value: ${JSON.stringify(role)}`,
      ).toContain('Administrator')
    },
  )

  it('every route with a meta.role is either a string or a readonly array', () => {
    for (const route of router.getRoutes()) {
      if (route.meta?.role === undefined) continue
      const role = route.meta.role
      const isStringOrArray =
        typeof role === 'string' || Array.isArray(role)
      expect(
        isStringOrArray,
        `route '${String(route.name)}' has an invalid meta.role shape: ${JSON.stringify(role)}`,
      ).toBe(true)
    }
  })
})

/**
 * Decision D5 (JSP-retirement plan): the SPA gates follow the backend. The
 * backend's study-build gate (StudyAdminAuthorization.userMayEditStudy /
 * userMayManageCrfLibrary, ExportDatasetServlet / StudyAuditLogServlet /
 * ImportCRFDataServlet parity) admits a coordinator, shown as CRC, so these
 * routes must too.
 */
const ROUTES_THAT_MUST_ADMIT_CRC = [
  'build-study',
  'event-definitions',
  'crf-library',
  'crf-detail',
  'crf-migration',
  'crfAuthoringCanvas',
  'sites',
  'group-classes',
  'rules',
  'data-export',
  'datasets',
  'dataset-new',
  'dataset-edit',
  'audit-log',
  'import-crf-data',
] as const

/**
 * The backend refuses a coordinator here (sysadmin-only writes), so the route
 * must keep refusing the role; widening it is a backend decision, not an SPA one.
 */
const ROUTES_THAT_MUST_REFUSE_CRC = [
  'study-create',
  'study-edit',
  'study-parameters',
  'manage-users',
] as const

function admits(routeName: string, role: 'CRC'): boolean {
  const route = router.getRoutes().find((r) => r.name === routeName)
  expect(route, `route '${routeName}' is not registered`).toBeDefined()
  const required = route!.meta.role
  const list = Array.isArray(required) ? required : [required]
  return list.some((r) => r !== undefined && roleSatisfies(role, r as Parameters<typeof roleSatisfies>[1]))
}

describe('router role gates — CRC (coordinator) follows the backend, D5', () => {
  it.each(ROUTES_THAT_MUST_ADMIT_CRC)('route %s admits CRC', (routeName) => {
    expect(admits(routeName, 'CRC'), `route '${routeName}' bounces a CRC although the backend admits a coordinator`).toBe(true)
  })

  it.each(ROUTES_THAT_MUST_REFUSE_CRC)('route %s still refuses CRC', (routeName) => {
    expect(admits(routeName, 'CRC'), `route '${routeName}' admits a CRC but the backend refuses a coordinator`).toBe(false)
  })
})

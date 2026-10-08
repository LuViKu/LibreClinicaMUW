/**
 * Who sees the write controls of the study-build pages. Each predicate
 * mirrors a backend gate, so a control is shown exactly where the API will
 * not answer 403 (decision D5: the SPA follows the backend).
 */
import type { UserRole } from '@/types/auth'

type Role = UserRole | undefined

/**
 * Create, edit and remove study-build objects (event definitions, sites,
 * group classes, rules, CRFs): `StudyAdminAuthorization.userMayEditStudy` and
 * `userMayManageCrfLibrary` — a system administrator, or a director (Data
 * Manager) or coordinator (CRC) bound to the study.
 */
export function mayBuildStudy(role: Role): boolean {
  return role === 'Administrator' || role === 'Data Manager' || role === 'CRC'
}

/**
 * Site disable and restore, event-definition lock and unlock, CRF version
 * removal: the backend allows a system administrator only
 * (`StudyAdminAuthorization.roleMayLifecycleStudy`, `me.isSysAdmin()`).
 */
export function maySysadminOnly(role: Role): boolean {
  return role === 'Administrator'
}

/**
 * Create, edit, remove and restore export datasets:
 * `DatasetsApiController.roleMayEditExports` — director, coordinator or
 * monitor, and a system administrator.
 */
export function mayEditExports(role: Role): boolean {
  return mayBuildStudy(role) || role === 'Monitor'
}

/**
 * Who may download the active study's metadata: its design as CDISC ODM,
 * the document the legacy Download Study Metadata page gives.
 *
 * The same gate as the endpoint (`StudyAdminAuthorization.userMayViewStudyDesign`),
 * which is the servlet's: a system administrator, or a role that may view
 * the study's data. The SPA sees those roles projected: study director
 * (Data Manager), coordinator (CRC), investigator and both data entry
 * roles (Investigator), monitor. The study-level `admin` role (Administrator)
 * is not one of them; an administrator account passes on its account type.
 */
import type { AuthenticatedUser, UserRole } from '@/types/auth'
import { rolesOf } from './retinalAccess'

const STUDY_DATA_VIEWERS: ReadonlySet<UserRole> = new Set(['Data Manager', 'CRC', 'Investigator', 'Monitor'])

/**
 * @param isSysAdmin whether the account is a system administrator
 * @param roles      every role the user holds in the active study
 */
export function canDownloadStudyMetadata(isSysAdmin: boolean, roles: readonly UserRole[]): boolean {
  return isSysAdmin || roles.some((r) => STUDY_DATA_VIEWERS.has(r))
}

/** {@link canDownloadStudyMetadata} for the signed-in user and their active study. */
export function userMayDownloadStudyMetadata(user: AuthenticatedUser | null | undefined): boolean {
  if (!user?.activeStudy?.oid) return false
  const isSysAdmin = user.userType === 'SYSADMIN' || user.userType === 'TECHADMIN'
  return canDownloadStudyMetadata(isSysAdmin, rolesOf(user))
}

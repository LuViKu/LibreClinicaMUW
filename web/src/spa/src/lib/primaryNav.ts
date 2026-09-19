/**
 * The primary navigation: each role's main destinations, for the top bar.
 *
 * <p>Until this existed the home page was the only way between workflows —
 * the top bar carried brand, breadcrumb and profile, the side rail is mounted
 * per view with two or three local links, so every cross-workflow move was
 * home → card → view → home. A page that should be an overview was doing the
 * work of a menu.
 *
 * <p>Short by design: at most six links, and only destinations the role can
 * actually enter — every path here is checked against the router's role meta
 * by the unit test, because a link that bounces off the route guard is worse
 * than no link. The full catalogue stays on the home page.
 */
import type { UserRole } from '@/types/auth'

export interface PrimaryNavItem {
  id: string
  to: string
  /** i18n key; the caller resolves it so this module stays free of vue-i18n. */
  labelKey: string
}

const HOME: PrimaryNavItem = { id: 'home', to: '/', labelKey: 'nav.home' }
const SUBJECTS: PrimaryNavItem = { id: 'subjects', to: '/subjects', labelKey: 'nav.subjectMatrix' }
const NOTES: PrimaryNavItem = { id: 'notes', to: '/notes', labelKey: 'nav.notes' }
const DUE_VISITS: PrimaryNavItem = { id: 'due-visits', to: '/due-visits', labelKey: 'nav.dueVisits' }
const INBOX: PrimaryNavItem = { id: 'ingest-inbox', to: '/ingest-inbox', labelKey: 'nav.ingestInbox' }
const SDV: PrimaryNavItem = { id: 'sdv', to: '/sdv', labelKey: 'nav.sdv' }
const AUDIT: PrimaryNavItem = { id: 'audit-log', to: '/audit-log', labelKey: 'nav.auditLog' }
const BUILD: PrimaryNavItem = { id: 'build-study', to: '/build-study', labelKey: 'nav.buildStudy' }
const EXPORT: PrimaryNavItem = { id: 'data-export', to: '/export', labelKey: 'nav.dataExport' }
const USERS: PrimaryNavItem = { id: 'manage-users', to: '/manage-users', labelKey: 'nav.manageUsers' }
const SITES: PrimaryNavItem = { id: 'sites', to: '/sites', labelKey: 'nav.sites' }

/**
 * Per role, in the order the role reaches for them. Mirrors the router's
 * role meta: CRC has no due-visits or inbox route, so it gets neither link.
 */
export const PRIMARY_NAV: Record<UserRole, PrimaryNavItem[]> = {
  Investigator: [SUBJECTS, NOTES, DUE_VISITS, INBOX],
  CRC: [SUBJECTS, NOTES],
  Monitor: [SUBJECTS, SDV, NOTES, AUDIT],
  'Data Manager': [BUILD, INBOX, EXPORT, NOTES],
  Administrator: [USERS, SITES, EXPORT, AUDIT],
}

/** Highest first, so a multi-role operator's strongest binding leads. */
const ROLE_PRIORITY: Record<UserRole, number> = {
  Administrator: 5,
  'Data Manager': 4,
  Monitor: 3,
  Investigator: 2,
  CRC: 1,
}

export const PRIMARY_NAV_MAX = 6

/**
 * The union of the roles' destinations, de-duplicated, home first, capped.
 *
 * <p>An operator with two roles gets the strongest role's links first and
 * the other's appended, minus repeats — so an Investigator who is also the
 * Data Manager still sees the subject matrix within the first few links.
 */
export function primaryNavFor(roles: UserRole[], max: number = PRIMARY_NAV_MAX): PrimaryNavItem[] {
  if (roles.length === 0) return []
  const ordered = [...new Set(roles)].sort((a, b) => ROLE_PRIORITY[b] - ROLE_PRIORITY[a])
  const out: PrimaryNavItem[] = [HOME]
  const seen = new Set<string>([HOME.to])
  for (const role of ordered) {
    for (const item of PRIMARY_NAV[role] ?? []) {
      if (seen.has(item.to)) continue
      seen.add(item.to)
      out.push(item)
      if (out.length >= max) return out
    }
  }
  return out
}

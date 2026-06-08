import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * Phase E.1 (2026-05-30): minimal vue-router scaffold.
 * Phase E.8 (2026-05-30 evening): auth-aware global guard added.
 *
 * Guard rules:
 *  - `meta.public = true` routes (login, first-login) bypass auth.
 *  - `state === 'anonymous'` → redirect to /login.
 *  - `state === 'profile-incomplete'` → redirect to /first-login.
 *  - `meta.role` mismatched → redirect to home (the role chip on the
 *    target page would have lied, and routes that demand a specific
 *    role often surface PHI-sensitive data — fail closed).
 *
 * Routes are added per-sub-phase as workflows ship.
 */
const router = createRouter({
  history: createWebHistory('/LibreClinica/app/'),
  // Phase E.6 polish — honour the in-route hash so navigations from
  // CrfEntryView post-completion (#events) land on the correct panel
  // of subject-detail instead of the page top.
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: 'Home' },
    },
    {
      path: '/subjects',
      name: 'subject-matrix',
      component: () => import('@/views/SubjectMatrixView.vue'),
      meta: { title: 'Subject Matrix', role: ['Investigator', 'Monitor', 'Data Manager', 'Administrator'] as const },
    },
    {
      path: '/subjects/new',
      name: 'subject-new',
      component: () => import('@/views/AddSubjectView.vue'),
      meta: { title: 'Add Subject', role: 'Investigator' as const },
    },
    {
      path: '/event-crfs/:eventCrfOid',
      name: 'crf-entry',
      component: () => import('@/views/CrfEntryView.vue'),
      meta: { title: 'CRF Entry', role: 'Investigator' as const },
    },
    {
      // Phase E.6 dde — reconcile view; DM / Admin / Investigator only.
      // Backend guards (403 when role.id is not in {1, 3, 4}) are the
      // authoritative gate; the meta.role here gives the SPA's nav
      // guard a chance to early-redirect for clarity.
      path: '/event-crfs/:eventCrfOid/dde-reconcile',
      name: 'dde-reconcile',
      component: () => import('@/views/DdeReconcileView.vue'),
      meta: { title: 'DDE Reconciliation',
              role: ['Data Manager', 'Administrator', 'Investigator'] as const },
    },
    {
      path: '/sdv',
      name: 'sdv',
      component: () => import('@/views/SdvView.vue'),
      meta: { title: 'Source Data Verification', role: 'Monitor' as const },
    },
    {
      path: '/notes',
      name: 'notes',
      component: () => import('@/views/NotesDiscrepanciesView.vue'),
      meta: { title: 'Notes & Discrepancies', role: ['Monitor', 'Data Manager', 'Administrator'] as const },
    },
    {
      path: '/audit-log',
      name: 'audit-log',
      component: () => import('@/views/StudyAuditLogView.vue'),
      meta: { title: 'Study Audit Log', role: ['Monitor', 'Data Manager', 'Administrator'] as const },
    },
    /* Phase E.6 carry-over — Read-only CRF (Monitor's "View Within Record" path). */
    {
      path: '/event-crfs/:eventCrfOid/readonly',
      name: 'crf-readonly',
      component: () => import('@/views/CrfEntryView.vue'),
      meta: { title: 'Read-only CRF', role: 'Monitor' as const, readOnly: true },
    },
    /* Phase E.7 — Data Manager workflow. */
    {
      path: '/build-study',
      name: 'build-study',
      component: () => import('@/views/BuildStudyView.vue'),
      meta: { title: 'Build Study', role: 'Data Manager' as const },
    },
    /* Phase E A8.1 — Study identity create/edit (sysadmin only). */
    {
      path: '/studies/new',
      name: 'study-create',
      component: () => import('@/views/CreateStudyView.vue'),
      meta: { title: 'Create Study', role: 'Administrator' as const },
    },
    {
      path: '/studies/:oid/edit',
      name: 'study-edit',
      component: () => import('@/views/StudyIdentityEditView.vue'),
      meta: { title: 'Edit Study', role: 'Administrator' as const },
    },
    /* Phase E.6 study-params — per-study parameter settings panel. */
    {
      path: '/studies/:oid/parameters',
      name: 'study-parameters',
      component: () => import('@/views/StudyParametersEditView.vue'),
      meta: { title: 'Study Parameters', role: 'Administrator' as const },
    },
    /* Phase E A8.2 — Event-definition CRUD for the active study. */
    {
      path: '/event-definitions',
      name: 'event-definitions',
      component: () => import('@/views/EventDefinitionsView.vue'),
      meta: { title: 'Event definitions', role: 'Data Manager' as const },
    },
    /* Phase E A8.3 — CRF library + version upload. */
    {
      path: '/crf-library',
      name: 'crf-library',
      component: () => import('@/views/CrfLibraryView.vue'),
      meta: { title: 'CRF Library', role: 'Data Manager' as const },
    },
    /* Phase E A8.4 — sites / multi-center setup. */
    {
      path: '/sites',
      name: 'sites',
      component: () => import('@/views/SitesView.vue'),
      meta: { title: 'Sites', role: 'Administrator' as const },
    },
    /* Phase E A8.6 — subject group classes (Arms, families, etc.). */
    {
      path: '/group-classes',
      name: 'group-classes',
      component: () => import('@/views/GroupClassesView.vue'),
      meta: { title: 'Group classes', role: 'Data Manager' as const },
    },
    /* Phase E RX.1 — read-only rules viewer. */
    {
      path: '/rules',
      name: 'rules',
      component: () => import('@/views/RulesView.vue'),
      meta: { title: 'Rules', role: 'Data Manager' as const },
    },
    {
      path: '/manage-users',
      name: 'manage-users',
      component: () => import('@/views/ManageUsersView.vue'),
      meta: { title: 'Manage Users', role: 'Administrator' as const },
    },
    /* Phase E.6 — Data Export MVP. */
    {
      path: '/export',
      name: 'data-export',
      component: () => import('@/views/DatasetListView.vue'),
      meta: { title: 'Data Export', role: ['Data Manager', 'Administrator', 'Monitor'] as const },
    },
    /* Phase E.6 P2 — Create-dataset wizard (revival of PR #114). */
    {
      path: '/datasets/new',
      name: 'dataset-new',
      component: () => import('@/views/CreateDatasetView.vue'),
      meta: {
        title: 'Create dataset',
        role: ['Monitor', 'Data Manager', 'Administrator'] as const,
      },
    },
    {
      path: '/datasets/:datasetId/edit',
      name: 'dataset-edit',
      component: () => import('@/views/CreateDatasetView.vue'),
      meta: {
        title: 'Edit dataset',
        role: ['Monitor', 'Data Manager', 'Administrator'] as const,
      },
    },
    {
      path: '/import-crf-data',
      name: 'import-crf-data',
      component: () => import('@/views/ImportCrfDataView.vue'),
      meta: { title: 'Import CRF Data', role: 'Data Manager' as const },
    },
    {
      path: '/subjects/:subjectId/sign',
      name: 'sign-subject',
      component: () => import('@/views/SignSubjectView.vue'),
      meta: { title: 'Sign Subject', role: 'Investigator' as const },
    },
    {
      path: '/subjects/:subjectId',
      name: 'subject-detail',
      component: () => import('@/views/SubjectDetailView.vue'),
      meta: { title: 'Subject', role: 'Investigator' as const },
    },
    /* Phase E.6 — standalone Event Detail (replaces the legacy
       /pages/EnterDataForStudyEvent JSP that SubjectDetailView used
       to bridge into). CRC inherits Investigator (see roleSatisfies). */
    {
      path: '/events/:eventId',
      name: 'event-detail',
      component: () => import('@/views/EventDetailView.vue'),
      meta: {
        title: 'Event',
        role: ['Investigator', 'Monitor', 'Data Manager', 'Administrator'] as const,
      },
    },
    /* Phase E.8 — Auth. */
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: 'Sign in', public: true },
    },
    {
      path: '/first-login',
      name: 'first-login',
      component: () => import('@/views/FirstLoginView.vue'),
      meta: { title: 'First-login profile', public: true },
    },
    /* Phase E.4 M1 — Study picker (post-login, when no study bound). */
    {
      path: '/pick-study',
      name: 'pick-study',
      component: () => import('@/views/StudyPickerView.vue'),
      meta: { title: 'Pick a study' },
    },
    /* Phase E.6 — Forced password change (first login or rotation). */
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('@/views/ChangePasswordView.vue'),
      meta: { title: 'Change password' },
    },
    /* Phase E.6 — Data Export Phase 2 — create-dataset wizard. */
    {
      path: '/datasets',
      name: 'datasets',
      component: () => import('@/views/DatasetListView.vue'),
      meta: {
        title: 'Datasets',
        role: ['Monitor', 'Data Manager', 'Administrator'] as const,
      },
    },
    {
      path: '/datasets/new',
      name: 'dataset-new',
      component: () => import('@/views/CreateDatasetView.vue'),
      meta: {
        title: 'Create dataset',
        role: ['Monitor', 'Data Manager', 'Administrator'] as const,
      },
    },
    {
      path: '/datasets/:datasetId/edit',
      name: 'dataset-edit',
      component: () => import('@/views/CreateDatasetView.vue'),
      meta: {
        title: 'Edit dataset',
        role: ['Monitor', 'Data Manager', 'Administrator'] as const,
      },
    },
  ],
})

router.afterEach((to) => {
  if (typeof document !== 'undefined') {
    const title = to.meta.title as string | undefined
    document.title = title ? `${title} · LibreClinica MUW` : 'LibreClinica MUW'
  }
})

router.beforeEach((to, _from) => {
  const auth = useAuthStore()
  return guard(auth, to)
})

/** Pure helper — separated so unit tests can drive it without the router. */
export function guard(
  auth: ReturnType<typeof useAuthStore>,
  to: RouteLocationNormalized,
): boolean | { name: string } {
  const isPublic = to.meta.public === true

  // First-login wizard requires an authenticated-but-incomplete identity.
  if (to.name === 'first-login') {
    return auth.needsProfile ? true : { name: auth.isAuthenticated ? 'home' : 'login' }
  }

  // Login route is public; redirect already-authenticated users home.
  if (to.name === 'login') {
    return auth.isAnonymous ? true : { name: auth.needsProfile ? 'first-login' : 'home' }
  }

  // Study picker requires an authenticated identity. Phase E.6:
  // previously hard-redirected away whenever the user already had an
  // active study — we let multi-study operators re-enter the picker
  // to switch instead.
  if (to.name === 'pick-study') {
    if (auth.isAnonymous) return { name: 'login' }
    if (auth.needsProfile) return { name: 'first-login' }
    // Forced password change wins over study switching (Phase E.6 / #102).
    if (auth.needsPasswordChange) return { name: 'change-password' }
    // Phase E.6 / #101: allow authenticated multi-study operators to
    // re-enter the picker to switch — do NOT bounce home when a study
    // is already bound.
    return true
  }

  // Phase E.6 — Forced password change view.
  // Must be reachable while needsPasswordChange === true (the legacy
  // SecureController.passwdTimeOut() forwards every request to the
  // ResetPassword JSP in that state; we mirror by routing every
  // navigation here). Anonymous users go to login; authenticated
  // users whose password is already up to date are bounced home.
  if (to.name === 'change-password') {
    if (auth.isAnonymous) return { name: 'login' }
    if (auth.needsProfile) return { name: 'first-login' }
    return auth.needsPasswordChange ? true : { name: 'home' }
  }

  if (isPublic) return true

  if (auth.isAnonymous) return { name: 'login' }
  if (auth.needsProfile) return { name: 'first-login' }
  // Phase E.4 M1: a bound study is required for every protected route.
  // If the user lacks one, send them through the picker first.
  if (auth.needsStudyPick) return { name: 'pick-study' }
  // Phase E.6: forced password change wins over role checks — the
  // legacy SecureController bounces every request (except the
  // ResetPassword target itself) to the change-password page until
  // the user updates their credential. Mirror that here so role-gated
  // views can't render PHI to a stale credential.
  if (auth.needsPasswordChange) return { name: 'change-password' }

  // Phase E.6 (2026-06-03): meta.role accepts a single role OR an
  // array of roles. Audit Trail, for example, opens to Monitor +
  // Data Manager + Administrator — all three have legitimate
  // compliance-review reasons to consult it. The array form is an
  // ordered "any of" — actual role passes if it satisfies any entry
  // (via roleSatisfies, so CRC→Investigator inheritance still
  // applies inside the OR).
  const roleMeta = to.meta.role as
    | 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC'
    | Array<'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC'>
    | undefined
  if (roleMeta) {
    const required = Array.isArray(roleMeta) ? roleMeta : [roleMeta]
    // Multi-role per (user, study) — M2 (2026-06-08): collect every
    // role the active binding carries (array → singular projection →
    // top-level fallback) and accept the navigation if any of them
    // satisfies any required entry. The CRC → Investigator
    // inheritance survives unchanged.
    const actualRoles = userRolesFromAuth(auth)
    const ok = required.some((r) =>
      actualRoles.some((actual) => roleSatisfies(actual, r)),
    )
    if (!ok) return { name: 'home' }
  }

  return true
}

/**
 * Multi-role per (user, study) — M2 (2026-06-08).
 *
 * Reads the active study binding's role set with the same precedence
 * the rest of the SPA uses ({@code activeStudy.roles} → {@code
 * activeStudy.role} → top-level {@code user.role}). Returns an array
 * so the guard can intersect against the route's required-role list
 * without re-implementing the precedence logic at every call site.
 */
function userRolesFromAuth(
  auth: ReturnType<typeof useAuthStore>,
): Array<'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC'> {
  const u = auth.user
  if (!u) return []
  const active = u.activeStudy
  if (active?.roles && active.roles.length > 0) return [...active.roles]
  if (active?.role) return [active.role]
  return u.role ? [u.role] : []
}

/**
 * Phase E.6 — role matching for route guards. Strict by default: a
 * route demanding `Data Manager` accepts only `Data Manager`. The
 * single exception is CRC → Investigator: CRC is a thin coordinator
 * variant of Investigator in MUW's deployment and inherits its
 * workflows in v1.
 *
 * Administrator does NOT inherit Data Manager (or any other role).
 * Per the 2026-06-03 operator note: the Administrator landing only
 * surfaces Admin-relevant paths; users with both responsibilities
 * sign in under the role they need (e.g. the `datamanager` demo
 * account, or any user with a Data Manager binding).
 */
export function roleSatisfies(
  actual: 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC' | undefined,
  required: 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC',
): boolean {
  if (actual === undefined) return false
  if (actual === required) return true
  if (actual === 'CRC' && required === 'Investigator') return true
  return false
}

export default router

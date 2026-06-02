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
      meta: { title: 'Subject Matrix', role: 'Investigator' as const },
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
      path: '/sdv',
      name: 'sdv',
      component: () => import('@/views/SdvView.vue'),
      meta: { title: 'Source Data Verification', role: 'Monitor' as const },
    },
    {
      path: '/notes',
      name: 'notes',
      component: () => import('@/views/NotesDiscrepanciesView.vue'),
      meta: { title: 'Notes & Discrepancies', role: 'Monitor' as const },
    },
    {
      path: '/audit-log',
      name: 'audit-log',
      component: () => import('@/views/StudyAuditLogView.vue'),
      meta: { title: 'Study Audit Log', role: 'Monitor' as const },
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
    /* Phase E A8.2 — Event-definition CRUD for the active study. */
    {
      path: '/event-definitions',
      name: 'event-definitions',
      component: () => import('@/views/EventDefinitionsView.vue'),
      meta: { title: 'Event definitions', role: 'Data Manager' as const },
    },
    {
      path: '/manage-users',
      name: 'manage-users',
      component: () => import('@/views/ManageUsersView.vue'),
      meta: { title: 'Manage Users', role: 'Data Manager' as const },
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

  // Study picker requires an authenticated identity without an active study.
  if (to.name === 'pick-study') {
    if (auth.isAnonymous) return { name: 'login' }
    if (auth.needsProfile) return { name: 'first-login' }
    return auth.needsStudyPick ? true : { name: 'home' }
  }

  if (isPublic) return true

  if (auth.isAnonymous) return { name: 'login' }
  if (auth.needsProfile) return { name: 'first-login' }
  // Phase E.4 M1: a bound study is required for every protected route.
  // If the user lacks one, send them through the picker first.
  if (auth.needsStudyPick) return { name: 'pick-study' }

  const requiredRole = to.meta.role as
    | 'Investigator' | 'Monitor' | 'Data Manager' | 'Administrator' | 'CRC'
    | undefined
  if (requiredRole && auth.user?.role !== requiredRole) {
    return { name: 'home' }
  }

  return true
}

export default router

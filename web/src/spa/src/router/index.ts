import { createRouter, createWebHistory } from 'vue-router'

/**
 * Phase E.1 (2026-05-30): minimal vue-router scaffold.
 *
 * Routes are added per-sub-phase as workflows ship. The history base
 * matches Vite's `base` config so dev and prod URLs are stable.
 *
 * Route ordering follows the Phase E execution playbook:
 * - E.5 Investigator: subject-matrix, add-subject, crf-entry, sign-subject
 * - E.6 Monitor:     sdv, crf-readonly, add-query, audit-log
 * - E.7 Data Manager: build-study, manage-users, create-crf, import-crf-data
 * - E.8 Auth:        login, profile-complete, sso-bounce
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
  ],
})

router.afterEach((to) => {
  if (typeof document !== 'undefined') {
    const title = to.meta.title as string | undefined
    document.title = title ? `${title} · LibreClinica MUW` : 'LibreClinica MUW'
  }
})

export default router

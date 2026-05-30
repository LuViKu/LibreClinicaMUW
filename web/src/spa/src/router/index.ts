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
  ],
})

router.afterEach((to) => {
  if (typeof document !== 'undefined') {
    const title = to.meta.title as string | undefined
    document.title = title ? `${title} · LibreClinica MUW` : 'LibreClinica MUW'
  }
})

export default router

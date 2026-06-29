<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import GlobalErrorToast from '@/components/GlobalErrorToast.vue'
import ConnectionBanner from '@/components/ConnectionBanner.vue'
import BugReportDialog from '@/components/BugReportDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useInactivityStore } from '@/stores/inactivity'
import { useBreadcrumbStore } from '@/stores/breadcrumb'
import type { UserRole } from '@/types/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const inactivity = useInactivityStore()
const breadcrumbs = useBreadcrumbStore()
const { t } = useI18n()

async function logout() {
  // Await the store action so state flips to 'anonymous' BEFORE the
  // router navigation fires — otherwise the global guard's
  // /login branch (`return auth.isAnonymous ? true : { name: 'home' }`)
  // still sees state='authenticated' and refuses the redirect.
  // Symptom before this fix: clicking the top-bar logout button
  // appeared to do nothing until the user manually refreshed.
  inactivity.stop()
  await auth.logout()
  router.push({ name: 'login' })
}

/**
 * Phase E.6 inactivity-timeout (2026-06-12): when the watcher fires,
 * log the operator out + bounce them to /login carrying the current
 * route as the returnTo query param. The LoginView resolves
 * returnTo to navigate the operator back to where they were after
 * successful re-auth.
 */
async function onInactivityTimeout(): Promise<void> {
  const fullPath = route.fullPath
  inactivity.stop()
  await auth.logout()
  const safePath = fullPath && fullPath !== '/' && !fullPath.startsWith('/login')
    ? fullPath
    : null
  router.push({
    name: 'login',
    query: safePath ? { returnTo: safePath } : undefined,
  })
}

/**
 * Start/stop the inactivity watcher in lockstep with auth state.
 * When the operator is authenticated the watcher arms; when they
 * sign out (manually OR via the watcher) it disarms so the
 * /login mount doesn't churn the watcher needlessly.
 *
 * <p>{@code flush: 'post'} defers the callback until AFTER the DOM
 * patch that pushed {@code auth.isAuthenticated} → true settles.
 * Without it, the synchronous callback mutates the inactivity store
 * refs during the same render pass that's already mounting HomeView
 * (which reads those refs to render the inactivity ribbon), and Vue's
 * scheduler trips "Maximum recursive updates exceeded in component
 * &lt;HomeView&gt;". Symptom before this fix: an admin login lands on /
 * and a popover error fires immediately. The watcher still arms /
 * disarms on every auth flip, just one microtask later.
 */
watch(
  () => auth.isAuthenticated,
  (authed) => {
    if (authed) {
      inactivity.start(onInactivityTimeout)
      inactivity.touch()
    } else {
      inactivity.stop()
    }
  },
  { immediate: false, flush: 'post' },
)

onMounted(() => {
  if (auth.isAuthenticated) inactivity.start(onInactivityTimeout)
})
onUnmounted(() => inactivity.stop())

interface Crumb { label: string; to?: string }

/**
 * 2026-06-23 user-feedback round — nested breadcrumb trail.
 *
 * <p>The session-bound study (or study + site) is always the leading
 * crumb. After that:
 *   1. If the active view has published a trail via the breadcrumb
 *      store, append it. Per-view trails carry their own parent
 *      chain ("Subjects > EIAMD150 > V03") so the surfaced trail
 *      mirrors the navigation path that brought the operator here.
 *   2. Otherwise fall back to the legacy single-crumb behaviour
 *      (route.meta.title), which still serves every view that hasn't
 *      registered yet.
 */
const breadcrumb = computed<Crumb[]>(() => {
  const crumbs: Crumb[] = []
  const active = auth.user?.activeStudy
  if (active) {
    if (active.isSite) {
      // The active study is a site row. The SPA doesn't carry the
      // parent study's name in /me's wire shape — fall back to the
      // siteLabel and a generic "Studie" parent. When the
      // parent-study display name lands in the /me adapter, drop
      // the placeholder.
      crumbs.push({ label: t('app.crumb.studyFallback') })
      crumbs.push({ label: active.name })
    } else {
      // Active study links back to /home (the catalogue / dashboard).
      crumbs.push({ label: active.name, to: '/' })
    }
  }
  // View-published trail wins when present — that's the per-route
  // nested chain (Subjects > EIAMD150 > V03 …).
  const viewTrail = breadcrumbs.items
  if (viewTrail && viewTrail.length > 0) {
    for (const item of viewTrail) {
      crumbs.push({ label: item.label, to: item.to ?? undefined })
    }
    return crumbs
  }
  // Fallback: the route's static title for views that haven't migrated
  // to the per-view trail yet. Drop the link so the legacy single crumb
  // still reads as "active leaf".
  const routeTitle = route.meta?.title as string | undefined
  if (routeTitle && route.name !== 'home' && route.name !== 'login' && route.name !== 'first-login') {
    crumbs.push({ label: routeTitle })
  }
  return crumbs
})

const displayUserName = computed(() => auth.user?.username ?? '')

/**
 * Full per-study role set the user holds on the bound study. Prefer
 * the multi-role `activeStudy.roles` projection (M2 wire shape);
 * fall back to the single-role legacy chain when the per-study array
 * isn't populated yet. Drives both the inline chip / dots on the
 * topbar trigger and the colour-coded role list inside the popover.
 */
const userRoles = computed<UserRole[]>(() => {
  const active = auth.user?.activeStudy
  if (active?.roles && active.roles.length > 0) return [...active.roles]
  if (active?.role) return [active.role]
  if (auth.user?.role) return [auth.user.role]
  return []
})

// Hide the global TopBar / unauthenticated-fallback header on public
// routes that provide their own chrome (login, first-login, the OCT
// upload portal). Without this, anonymous public routes would stack a
// second "LibreClinica MUW · Anmelden" bar above the view's own
// header — see the 2026-06-18 OCT-portal bug report.
const showTopBar = computed(
  () =>
    route.name !== 'login' &&
    route.name !== 'first-login' &&
    route.name !== 'oct-upload-portal' &&
    // Phase E.8 Slice L4 — the printable-CRF view is meant to feed
    // the browser's "Print to PDF" cleanly. The TopBar would show up
    // in the captured PDF and the printable view has its own minimal
    // header instead.
    route.name !== 'printable-crf',
)

/**
 * Bug-report dialog open state. The dialog itself is always mounted
 * (cheap, no DOM in body until {@code open=true}) so the topbar's
 * popover handler can flip it open without an additional v-if dance.
 * Auth-gated alongside the TopBar — anonymous users have no way to
 * trigger it.
 */
const bugReportOpen = ref(false)
function openBugReport() {
  bugReportOpen.value = true
}
</script>

<template>
  <div class="min-h-screen bg-white text-slate-900 text-sm">
    <!-- WCAG 2.4.1 — bypass blocks. Visible only when focused. -->
    <a href="#main-content" class="skip-link">{{ t('a11y.skipToMain') }}</a>

    <TopBar
      v-if="showTopBar && auth.isAuthenticated"
      :breadcrumb="breadcrumb"
      :user-name="displayUserName"
      :user-roles="userRoles"
      :on-logout="logout"
      :on-report-bug="openBugReport"
    />
    <!-- Minimal "Sign in" affordance for anonymous routes that still want chrome. -->
    <header
      v-else-if="showTopBar"
      class="border-b border-slate-200 sticky top-0 z-30 bg-white/95 backdrop-blur"
    >
      <div class="max-w-7xl mx-auto px-4 h-14 flex items-center justify-between">
        <RouterLink to="/" class="flex items-center gap-2.5">
          <svg class="w-7 h-7 text-muw-blue" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
            <path d="M2.6 16 C9 8.2 23 8.2 29.4 16 C23 23.8 9 23.8 2.6 16 Z" stroke-width="2.2" />
            <circle cx="16" cy="16" r="5" stroke-width="2.2" />
            <circle cx="16" cy="16" r="1.9" fill="#d96849" stroke="none" />
          </svg>
          <span class="muw-display font-semibold text-muw-blue tracking-tight whitespace-nowrap">
            LibreClinica<em class="not-italic font-medium text-muw-coral-700 text-[0.7em] uppercase tracking-[0.08em] ml-1.5 align-middle">MUW</em>
          </span>
        </RouterLink>
        <RouterLink to="/login" class="text-xs text-muw-blue hover:underline">{{ t('a11y.signInLink') }}</RouterLink>
      </div>
    </header>

    <!-- Phase E hardening — B4: offline banner mounted ABOVE <main> so
         it takes layout space when visible. Hidden when connection is
         healthy. -->
    <ConnectionBanner />

    <main id="main-content" tabindex="-1" class="outline-none">
      <RouterView />
    </main>

    <!-- Phase E hardening — A5: singleton global error toast. Mounted
         once, outside any conditional, so uncaught errors from any
         view (including login / first-login / NotFound) surface. -->
    <GlobalErrorToast />

    <!-- Phase E in-app bug-report. Dialog lives at the app shell so
         every authenticated route can open it via the TopBar
         user-menu entry; rendering is gated on auth state so the
         splash / login chrome stays minimal. -->
    <BugReportDialog
      v-if="auth.isAuthenticated"
      v-model:open="bugReportOpen"
    />
  </div>
</template>

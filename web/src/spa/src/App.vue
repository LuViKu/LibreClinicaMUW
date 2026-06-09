<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/types/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { t } = useI18n()

async function logout() {
  // Await the store action so state flips to 'anonymous' BEFORE the
  // router navigation fires — otherwise the global guard's
  // /login branch (`return auth.isAnonymous ? true : { name: 'home' }`)
  // still sees state='authenticated' and refuses the redirect.
  // Symptom before this fix: clicking the top-bar logout button
  // appeared to do nothing until the user manually refreshed.
  await auth.logout()
  router.push({ name: 'login' })
}

interface Crumb { label: string; to?: string }

const breadcrumb = computed<Crumb[]>(() => {
  // Phase E.5 follow-up (2026-06-03): replace the hardcoded
  // "LCDemo · München" leftover from the Phase E.4 mock-data era
  // with the actual session-bound study / site.
  //   - bound to a site (activeStudy.isSite=true)     → [study, site]
  //     where "study" is resolved heuristically below
  //     and "site" is the activeStudy.name
  //   - bound to a top-level study                    → [study]
  //   - no active study (login / first-login / picker) → []
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
      crumbs.push({ label: active.name })
    }
  }
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

const showTopBar = computed(() => route.name !== 'login' && route.name !== 'first-login')
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
    />
    <!-- Minimal "Sign in" affordance for anonymous routes that still want chrome. -->
    <header
      v-else-if="showTopBar"
      class="border-b border-slate-200 sticky top-0 z-30 bg-white/95 backdrop-blur"
    >
      <div class="max-w-7xl mx-auto px-4 h-14 flex items-center justify-between">
        <RouterLink to="/" class="flex items-center gap-2.5">
          <svg class="w-7 h-7 text-muw-blue" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="16" cy="16" r="14" stroke-width="1.4" />
            <path d="M12 8v16M20 8v16M8 12h16M8 20h16" stroke-width="1.75" />
          </svg>
          <span class="muw-display font-semibold text-muw-blue tracking-tight whitespace-nowrap">
            LibreClinica<em class="not-italic font-medium text-muw-coral-700 text-[0.7em] uppercase tracking-[0.08em] ml-1.5 align-middle">MUW</em>
          </span>
        </RouterLink>
        <RouterLink to="/login" class="text-xs text-muw-blue hover:underline">{{ t('a11y.signInLink') }}</RouterLink>
      </div>
    </header>
    <main id="main-content" tabindex="-1" class="outline-none">
      <RouterView />
    </main>
  </div>
</template>

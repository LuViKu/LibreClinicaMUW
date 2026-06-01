<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { t } = useI18n()

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}

interface Crumb { label: string; to?: string }

const breadcrumb = computed<Crumb[]>(() => {
  const crumbs: Crumb[] = [{ label: 'LCDemo' }, { label: 'München' }]
  const routeTitle = route.meta?.title as string | undefined
  if (routeTitle && route.name !== 'home' && route.name !== 'login' && route.name !== 'first-login') {
    crumbs.push({ label: routeTitle })
  }
  return crumbs
})

const displayUserName = computed(() => auth.user?.username ?? '')

const userRole = computed<'Investigator' | 'Monitor' | 'Data Manager' | null>(() => {
  if (!auth.user) return null
  if (auth.user.role === 'Administrator' || auth.user.role === 'CRC') return null
  return auth.user.role
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
      :user-role="userRole"
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

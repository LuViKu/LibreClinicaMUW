<script setup lang="ts">
import { computed } from 'vue'

interface BreadcrumbItem {
  label: string
  to?: string
}

interface Props {
  /** Breadcrumb trail rendered after the brand lockup. */
  breadcrumb?: BreadcrumbItem[]
  /** Active user's display name. */
  userName?: string
  /** Active user's role label (Investigator | Monitor | Data Manager). */
  userRole?: 'Investigator' | 'Monitor' | 'Data Manager' | null
  /** Optional logout handler — when set, a sign-out icon button renders. */
  onLogout?: () => void
}

const props = withDefaults(defineProps<Props>(), {
  breadcrumb: () => [],
  userName: '',
  userRole: null,
  onLogout: undefined,
})

/**
 * Per-role MUW palette tells. Mirrors the role legend on the mockup
 * index page. Investigator = teal, Monitor = sky, DM = coral, anon = slate.
 */
const roleClasses = computed(() => {
  switch (props.userRole) {
    case 'Investigator':
      return { chip: 'bg-muw-teal-50 text-muw-teal-700', avatar: 'bg-muw-teal-100 text-muw-teal-700' }
    case 'Monitor':
      return { chip: 'bg-muw-sky-50 text-muw-sky-700', avatar: 'bg-muw-sky-100 text-muw-sky-700' }
    case 'Data Manager':
      return { chip: 'bg-muw-coral-50 text-muw-coral-700', avatar: 'bg-muw-coral-100 text-muw-coral-700' }
    default:
      return { chip: 'bg-slate-100 text-slate-600', avatar: 'bg-slate-100 text-slate-600' }
  }
})

const userInitials = computed(() => {
  if (!props.userName) return ''
  const parts = props.userName.split(/[\s_-]+/).filter(Boolean)
  return (parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')
})
</script>

<template>
  <header class="border-b border-slate-200 sticky top-0 z-30 bg-white/95 backdrop-blur">
    <div class="max-w-7xl mx-auto px-4 h-14 flex items-center">
      <RouterLink to="/" class="flex items-center gap-2.5 mr-6">
        <svg class="w-7 h-7 text-muw-blue" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="16" cy="16" r="14" stroke-width="1.4" />
          <path d="M12 8v16M20 8v16M8 12h16M8 20h16" stroke-width="1.75" />
        </svg>
        <span class="muw-display font-semibold text-muw-blue tracking-tight whitespace-nowrap">
          LibreClinica<em class="not-italic font-medium text-muw-coral-700 text-[0.7em] uppercase tracking-[0.08em] ml-1.5 align-middle">MUW</em>
        </span>
      </RouterLink>

      <nav v-if="breadcrumb.length" class="flex items-center gap-1.5 text-xs text-slate-500">
        <template v-for="(item, idx) in breadcrumb" :key="idx">
          <RouterLink v-if="item.to" :to="item.to" class="hover:text-slate-900 font-medium text-slate-700">
            {{ item.label }}
          </RouterLink>
          <span v-else class="text-slate-900 font-medium">{{ item.label }}</span>
          <svg v-if="idx < breadcrumb.length - 1" class="w-3.5 h-3.5 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </template>
      </nav>

      <div v-if="userName" class="ml-auto flex items-center gap-2">
        <button class="flex items-center gap-2 pl-2 pr-3 py-1 hover:bg-slate-100 rounded-md text-xs">
          <span class="w-6 h-6 rounded-full inline-flex items-center justify-center text-[10px] font-semibold" :class="roleClasses.avatar">
            {{ userInitials }}
          </span>
          <span class="text-slate-700">{{ userName }}</span>
          <span v-if="userRole" class="rounded-full text-[10px] px-1.5 py-0.5 font-medium" :class="roleClasses.chip">
            {{ userRole }}
          </span>
        </button>
        <button
          v-if="onLogout"
          type="button"
          class="text-xs text-slate-500 hover:text-slate-900 px-2 py-1 rounded-md hover:bg-slate-100"
          :title="'Sign out'"
          @click="onLogout"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" x2="9" y1="12" y2="12" />
          </svg>
        </button>
      </div>
    </div>
  </header>
</template>

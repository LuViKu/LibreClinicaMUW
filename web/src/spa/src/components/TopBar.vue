<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import RoleDots from '@/components/RoleDots.vue'
import type { UserRole } from '@/types/auth'

interface BreadcrumbItem {
  label: string
  to?: string
}

interface Props {
  /** Breadcrumb trail rendered after the brand lockup. */
  breadcrumb?: BreadcrumbItem[]
  /** Active user's display name. */
  userName?: string
  /**
   * Full role set the user holds on the active study. Drives the
   * RoleDots indicator on the trigger button and the colour-coded
   * list in the popover. Empty array suppresses the chip / dots.
   * The legacy single-role {@link userRole} prop is folded into this
   * array when supplied so the new TopBar stays drop-in compatible
   * with callers that haven't migrated yet.
   */
  userRoles?: UserRole[]
  /** Single-role projection — kept for back-compat with legacy callers. */
  userRole?: UserRole | null
  /** Optional logout handler — surfaced inside the popover. */
  onLogout?: () => void
  /**
   * Optional handler for the "Report a bug" popover entry. When
   * supplied, the entry appears for every authenticated user
   * regardless of role. The parent owns the dialog mount + the store
   * wiring so the TopBar stays a pure presenter.
   */
  onReportBug?: () => void
}

const props = withDefaults(defineProps<Props>(), {
  breadcrumb: () => [],
  userName: '',
  userRoles: () => [],
  userRole: null,
  onLogout: undefined,
  onReportBug: undefined,
})

const { t } = useI18n()

/**
 * Coalesced role set — prefer the explicit multi-role list; fall
 * back to the legacy single-role prop so legacy call sites keep
 * working through the transition.
 */
const roles = computed<UserRole[]>(() => {
  if (props.userRoles.length > 0) return props.userRoles
  if (props.userRole) return [props.userRole]
  return []
})

/**
 * Highest-priority role drives the trigger's avatar tint + the
 * inline chip. Order mirrors the project's ROLE_PRIORITY map: Admin
 * > DM > Monitor > Investigator > CRC.
 */
const ROLE_PRIORITY: Record<UserRole, number> = {
  Administrator: 5,
  'Data Manager': 4,
  Monitor: 3,
  Investigator: 2,
  CRC: 1,
}
const primaryRole = computed<UserRole | null>(() => {
  if (roles.value.length === 0) return null
  return [...roles.value].sort(
    (a, b) => ROLE_PRIORITY[b] - ROLE_PRIORITY[a],
  )[0]
})

const ROLE_PALETTE: Record<UserRole, { chip: string; avatar: string; swatch: string }> = {
  Investigator: {
    chip: 'bg-muw-teal-50 text-muw-teal-700',
    avatar: 'bg-muw-teal-100 text-muw-teal-700',
    swatch: 'bg-muw-teal-500',
  },
  CRC: {
    chip: 'bg-muw-teal-50 text-muw-teal-700',
    avatar: 'bg-muw-teal-100 text-muw-teal-700',
    swatch: 'bg-muw-teal-500',
  },
  Monitor: {
    chip: 'bg-muw-sky-50 text-muw-sky-700',
    avatar: 'bg-muw-sky-100 text-muw-sky-700',
    swatch: 'bg-muw-sky-500',
  },
  'Data Manager': {
    chip: 'bg-muw-coral-50 text-muw-coral-700',
    avatar: 'bg-muw-coral-100 text-muw-coral-700',
    swatch: 'bg-muw-coral-500',
  },
  Administrator: {
    chip: 'bg-muw-blue-50 text-muw-blue',
    avatar: 'bg-muw-blue-50 text-muw-blue',
    swatch: 'bg-muw-blue',
  },
}

const NEUTRAL_PALETTE = {
  chip: 'bg-slate-100 text-slate-600',
  avatar: 'bg-slate-100 text-slate-600',
  swatch: 'bg-slate-400',
}

const triggerPalette = computed(() =>
  primaryRole.value ? ROLE_PALETTE[primaryRole.value] : NEUTRAL_PALETTE,
)

function paletteFor(r: UserRole) {
  return ROLE_PALETTE[r] ?? NEUTRAL_PALETTE
}

const userInitials = computed(() => {
  if (!props.userName) return ''
  const parts = props.userName.split(/[\s_-]+/).filter(Boolean)
  return (parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')
})

/* ---------- popover state ---------- */

const popoverOpen = ref(false)
const triggerEl = ref<HTMLElement | null>(null)
const popoverEl = ref<HTMLElement | null>(null)

function togglePopover() {
  popoverOpen.value = !popoverOpen.value
}

function closePopover() {
  popoverOpen.value = false
}

function onDocumentMouseDown(e: MouseEvent) {
  if (!popoverOpen.value) return
  const target = e.target as Node | null
  if (!target) return
  if (triggerEl.value?.contains(target)) return
  if (popoverEl.value?.contains(target)) return
  closePopover()
}

function onDocumentKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && popoverOpen.value) {
    e.preventDefault()
    closePopover()
    triggerEl.value?.focus()
  }
}

watch(
  popoverOpen,
  (isOpen) => {
    if (typeof document === 'undefined') return
    if (isOpen) {
      document.addEventListener('mousedown', onDocumentMouseDown)
      document.addEventListener('keydown', onDocumentKeydown)
    } else {
      document.removeEventListener('mousedown', onDocumentMouseDown)
      document.removeEventListener('keydown', onDocumentKeydown)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (typeof document === 'undefined') return
  document.removeEventListener('mousedown', onDocumentMouseDown)
  document.removeEventListener('keydown', onDocumentKeydown)
})

function onLogoutClick() {
  closePopover()
  props.onLogout?.()
}

/**
 * Render the logout row only when the parent actually supplied a
 * handler. `withDefaults` sets the prop's default to `undefined` which
 * vue-tsc rejects as a truthy template check on a function signature;
 * the computed flips it into a plain boolean.
 */
const hasLogout = computed(() => typeof props.onLogout === 'function')

/**
 * Same shape as {@link hasLogout} — only render the popover row when
 * the parent supplied a handler, so the TopBar stays usable on
 * surfaces that have no bug-report integration wired (storybook,
 * splash, etc.).
 */
const hasReportBug = computed(() => typeof props.onReportBug === 'function')

function onReportBugClick() {
  closePopover()
  props.onReportBug?.()
}
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

      <!-- Phase E hardening B — sysadmin-only entry-point to the
           system-wide audit trail. Gated on Administrator role
           (sysadmin / techadmin both project to Administrator in
           UsersApiController.list); the same role gate the
           backend endpoint enforces. -->
      <RouterLink
        v-if="primaryRole === 'Administrator'"
        to="/system/audit-log"
        class="ml-auto mr-2 px-2 py-1 rounded-md text-xs text-slate-700 hover:bg-slate-100"
        data-testid="topbar-system-audit-link"
      >
        {{ t('topBar.systemAuditLog') }}
      </RouterLink>

      <div v-if="userName" class="relative" :class="primaryRole === 'Administrator' ? '' : 'ml-auto'">
        <button
          ref="triggerEl"
          type="button"
          class="flex items-center gap-2 pl-2 pr-3 py-1 hover:bg-slate-100 rounded-md text-xs"
          :aria-expanded="popoverOpen"
          aria-haspopup="true"
          aria-controls="topbar-profile-popover"
          data-testid="topbar-profile-trigger"
          @click="togglePopover"
        >
          <span class="w-6 h-6 rounded-full inline-flex items-center justify-center text-[10px] font-semibold" :class="triggerPalette.avatar">
            {{ userInitials }}
          </span>
          <span class="text-slate-700">{{ userName }}</span>
          <template v-if="roles.length > 1">
            <RoleDots :roles="roles" />
          </template>
          <span
            v-else-if="primaryRole"
            class="rounded-full text-[10px] px-1.5 py-0.5 font-medium"
            :class="triggerPalette.chip"
          >
            {{ primaryRole }}
          </span>
          <svg
            class="w-3 h-3 text-slate-400 transition-transform"
            :class="popoverOpen ? 'rotate-180' : ''"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            aria-hidden="true"
          >
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>

        <div
          v-if="popoverOpen"
          id="topbar-profile-popover"
          ref="popoverEl"
          role="menu"
          class="absolute right-0 mt-1.5 w-64 bg-white rounded-md shadow-lg border border-slate-200 py-2 text-xs z-40"
          data-testid="topbar-profile-popover"
        >
          <div class="px-3 pt-1 pb-2 flex items-center gap-2">
            <span class="w-8 h-8 rounded-full inline-flex items-center justify-center text-xs font-semibold" :class="triggerPalette.avatar">
              {{ userInitials }}
            </span>
            <div class="min-w-0 flex-1">
              <div class="font-medium text-slate-800 truncate">{{ userName }}</div>
              <div class="text-[10px] uppercase tracking-[0.08em] text-slate-500">
                {{ t('topBar.profile.rolesLabel') }}
              </div>
            </div>
          </div>

          <ul
            v-if="roles.length > 0"
            class="px-3 pb-2 space-y-1"
            :aria-label="t('topBar.profile.rolesAriaLabel')"
          >
            <li
              v-for="(r, i) in roles"
              :key="`${r}-${i}`"
              class="flex items-center gap-2 py-0.5"
            >
              <span class="w-2.5 h-2.5 rounded-full ring-1 ring-white inline-block" :class="paletteFor(r).swatch" />
              <span class="text-slate-700">{{ r }}</span>
            </li>
          </ul>

          <div
            v-if="hasReportBug"
            class="border-t border-slate-200 mt-1 pt-1.5 px-2"
          >
            <button
              type="button"
              class="w-full text-left px-2 py-1.5 rounded-md hover:bg-slate-100 text-slate-700 flex items-center gap-2"
              data-testid="topbar-report-bug"
              @click="onReportBugClick"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <rect x="8" y="6" width="8" height="14" rx="4" />
                <path d="M12 6V3" />
                <path d="M5 11h3M16 11h3M5 17h3M16 17h3" />
              </svg>
              {{ t('bugReport.title') }}
            </button>
          </div>

          <div v-if="hasLogout" class="border-t border-slate-200 mt-1 pt-1.5 px-2">
            <button
              type="button"
              class="w-full text-left px-2 py-1.5 rounded-md hover:bg-slate-100 text-slate-700 flex items-center gap-2"
              data-testid="topbar-profile-logout"
              @click="onLogoutClick"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                <polyline points="16 17 21 12 16 7" />
                <line x1="21" x2="9" y1="12" y2="12" />
              </svg>
              {{ t('topBar.profile.signOut') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </header>
</template>

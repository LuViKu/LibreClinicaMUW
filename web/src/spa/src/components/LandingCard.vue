<script setup lang="ts">
/**
 * Phase E.6 (2026-06-03) — landing-page card primitive.
 *
 * Extracted from HomeView.vue's previously-duplicated `RouterLink` +
 * `rounded-muw border …` template. Used by the de-duplicated home
 * catalogue to keep the card pattern consistent.
 *
 * <p>Multi-role per (user, study) — M2 (2026-06-08): {@code
 * roleVariant} → {@code roleVariants[]} and the single header dot →
 * {@link RoleDots}. The first variant drives the hover-accent so a
 * card the user holds via "Investigator + Data Manager" still wears
 * its Investigator teal identity.
 *
 * <p>Props
 * - {@code to}: vue-router route name or path; passed through to RouterLink.
 * - {@code roleVariants}: non-empty list driving the role dots and the
 *   first-entry-keyed accent. The catalogue's {@code grantingRoles}
 *   maps directly to this prop.
 * - {@code roleLabel}: optional label rendered alongside the dots.
 * - {@code title} / {@code description}: i18n strings; the component
 *   is purely presentational and does not call {@code t(...)} itself.
 * - {@code badge}: optional count overlay. Hidden when undefined or 0
 *   to avoid noisy "0 pending" decorations.
 *
 * <p>The card is a single `<RouterLink>` so keyboard navigation +
 * screen-reader semantics come for free. An optional badge has
 * {@code aria-label} so its meaning is announced.
 */
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import type { UserRole } from '@/types/auth'
import RoleDots from './RoleDots.vue'

export type RoleVariant = 'investigator' | 'monitor' | 'data-manager' | 'administrator'

const props = withDefaults(defineProps<{
  to: RouteLocationRaw
  roleVariants: RoleVariant[]
  roleLabel?: string
  title: string
  description: string
  badge?: number | string | null
  badgeAriaLabel?: string
}>(), {
  roleLabel: '',
  badge: null,
  badgeAriaLabel: undefined,
})

const VARIANT_TO_ROLE: Record<RoleVariant, UserRole> = {
  investigator: 'Investigator',
  monitor: 'Monitor',
  'data-manager': 'Data Manager',
  administrator: 'Administrator',
}

const dotRoles = computed<UserRole[]>(() =>
  props.roleVariants.map((v) => VARIANT_TO_ROLE[v]),
)

// First variant drives the border-accent on hover — the catalogue
// orders grantingRoles by priority so the highest-priority role
// governs the visual identity of the card.
const primaryVariant = computed<RoleVariant | null>(() =>
  props.roleVariants.length > 0 ? props.roleVariants[0] : null,
)

// Hide "0" badges — eager-loaded stores often report empty arrays
// before the actual data arrives, and a sticky "0 pending" reads as
// "nothing to do" which can be misleading mid-fetch.
function shouldShowBadge(b: number | string | null | undefined): boolean {
  if (b == null) return false
  if (typeof b === 'number') return b > 0
  return b.length > 0 && b !== '0'
}
</script>

<template>
  <RouterLink
    :to="props.to"
    :data-testid="primaryVariant ? `landing-card-${primaryVariant}` : 'landing-card'"
    class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group relative"
  >
    <div class="flex items-center gap-2 mb-2">
      <RoleDots :roles="dotRoles" />
      <span v-if="props.roleLabel" class="font-medium text-muw-blue">{{ props.roleLabel }}</span>
      <span
        v-if="shouldShowBadge(props.badge)"
        class="ml-auto inline-flex items-center justify-center min-w-[1.5rem] px-1.5 py-0.5 text-[10px] font-semibold rounded-full bg-muw-blue text-white"
        :aria-label="props.badgeAriaLabel"
      >
        {{ props.badge }}
      </span>
    </div>
    <div class="font-semibold text-slate-900 group-hover:underline mb-1">
      {{ props.title }}
    </div>
    <p class="text-slate-500 text-xs leading-relaxed">
      {{ props.description }}
    </p>
  </RouterLink>
</template>

<script setup lang="ts">
/**
 * Phase E.6 (2026-06-03) — landing-page card primitive.
 *
 * Extracted from HomeView.vue's previously-duplicated `RouterLink` +
 * `rounded-muw border …` template. Used across all four per-role
 * landings to keep the card pattern consistent without 10× duplication.
 *
 * <p>Props
 * - {@code to}: vue-router route name or path; passed through to RouterLink.
 * - {@code roleVariant}: drives the role-coloured dot in the header.
 * - {@code title} / {@code description}: i18n strings; the component is
 *   purely presentational and does not call {@code t(...)} itself.
 * - {@code badge}: optional count overlay (e.g. "5"). Hidden when
 *   undefined or 0 to avoid noisy "0 pending" decorations.
 *
 * <p>The card is a single `<RouterLink>` so keyboard navigation +
 * screen-reader semantics come for free. An optional badge has
 * {@code aria-label} so its meaning is announced.
 */
import type { RouteLocationRaw } from 'vue-router'

const props = withDefaults(defineProps<{
  to: RouteLocationRaw
  roleVariant: 'investigator' | 'monitor' | 'data-manager' | 'administrator'
  roleLabel: string
  title: string
  description: string
  badge?: number | string | null
  badgeAriaLabel?: string
}>(), {
  badge: null,
  badgeAriaLabel: undefined,
})

const dotClass = {
  investigator: 'bg-muw-teal-500',
  monitor: 'bg-muw-sky-500',
  'data-manager': 'bg-muw-coral-500',
  administrator: 'bg-muw-blue',
}[props.roleVariant]

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
    class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group relative"
  >
    <div class="flex items-center gap-2 mb-2">
      <span class="w-1.5 h-1.5 rounded-full" :class="dotClass" aria-hidden="true"></span>
      <span class="font-medium text-muw-blue">{{ props.roleLabel }}</span>
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

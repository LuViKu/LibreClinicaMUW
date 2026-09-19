<script setup lang="ts">
/**
 * A work queue on the home page: how many things are waiting, and where.
 *
 * <p>Differs from {@link LandingCard} in one deliberate way — the count is
 * the point, so it is always shown. A landing badge hides zero because "0
 * pending" on a card that is merely a destination reads as noise; on a
 * queue, zero is the answer the operator came for. While the count is still
 * loading the card shows a dash rather than a number, because a "0" that
 * flips to "8" a moment later is a lie the operator may already have acted
 * on.
 */
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import type { UserRole } from '@/types/auth'
import RoleDots from './RoleDots.vue'
import type { RoleVariant } from './LandingCard.vue'

const props = withDefaults(defineProps<{
  to: RouteLocationRaw
  roleVariants: RoleVariant[]
  title: string
  description: string
  /** null while loading; a number once known, zero included. */
  count: number | null
  /** Spoken form of the count, e.g. "8 open queries". */
  countAriaLabel?: string
  /** Spoken form of the loading state. */
  loadingLabel?: string
}>(), {
  countAriaLabel: undefined,
  loadingLabel: undefined,
})

const VARIANT_TO_ROLE: Record<RoleVariant, UserRole> = {
  investigator: 'Investigator',
  monitor: 'Monitor',
  'data-manager': 'Data Manager',
  administrator: 'Administrator',
}

const dotRoles = computed<UserRole[]>(() => props.roleVariants.map((v) => VARIANT_TO_ROLE[v]))

const primaryVariant = computed<RoleVariant | null>(() =>
  props.roleVariants.length > 0 ? props.roleVariants[0] : null,
)
</script>

<template>
  <RouterLink
    :to="props.to"
    :data-testid="primaryVariant ? `queue-card-${primaryVariant}` : 'queue-card'"
    class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group relative flex flex-col"
  >
    <div class="flex items-start justify-between gap-3 mb-3">
      <RoleDots :roles="dotRoles" />
      <span
        v-if="props.count === null"
        class="text-3xl font-semibold leading-none text-slate-300"
        :aria-label="props.loadingLabel"
        data-testid="queue-count-loading"
      >—</span>
      <span
        v-else
        class="text-3xl font-semibold leading-none tabular-nums"
        :class="props.count > 0 ? 'text-muw-blue' : 'text-slate-500'"
        :aria-label="props.countAriaLabel"
        data-testid="queue-count"
      >{{ props.count }}</span>
    </div>
    <div class="font-semibold text-slate-900 group-hover:underline mb-1">
      {{ props.title }}
    </div>
    <p class="text-slate-500 text-xs leading-relaxed">
      {{ props.description }}
    </p>
  </RouterLink>
</template>

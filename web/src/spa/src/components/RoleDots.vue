<script setup lang="ts">
/**
 * Multi-role per (user, study) — M2 (2026-06-08).
 *
 * Renders a stacked sequence of role-coloured dots — one per role the
 * user holds in the active study. Used by {@link LandingCard} to
 * communicate "this card is yours because you carry these N
 * bindings", and (eventually) by the TopBar / breadcrumb chip.
 *
 * <p>Layout: dots overlap with a -6px margin and a 1px white ring so
 * the colour boundary is visible; z-index decreases left-to-right so
 * the first (highest-priority) dot sits on top. Empty arrays render
 * nothing — the parent decides whether to hide the wrapper.
 *
 * <p>Colour map follows the project's MUW palette
 * (style.css `--color-muw-*` design tokens): Investigator + CRC share
 * teal (CRC is a thin variant of Investigator and inherits its
 * visual language in v1), Monitor uses sky, Data Manager uses coral,
 * Administrator uses the deep MUW blue.
 */
import { computed } from 'vue'
import type { UserRole } from '@/types/auth'

const props = defineProps<{
  roles: UserRole[]
}>()

const ROLE_COLOR: Record<UserRole, string> = {
  Investigator: 'bg-muw-teal-500',
  CRC: 'bg-muw-teal-500',
  Monitor: 'bg-muw-sky-500',
  'Data Manager': 'bg-muw-coral-500',
  Administrator: 'bg-muw-blue',
}

const ariaLabel = computed(() => props.roles.join(' + '))
</script>

<template>
  <span
    v-if="props.roles.length > 0"
    class="inline-flex items-center"
    :aria-label="ariaLabel"
    role="img"
  >
    <span
      v-for="(r, i) in props.roles"
      :key="`${r}-${i}`"
      class="w-2.5 h-2.5 rounded-full ring-1 ring-white inline-block"
      :class="ROLE_COLOR[r]"
      :style="{
        marginLeft: i === 0 ? '0' : '-6px',
        zIndex: props.roles.length - i,
      }"
      aria-hidden="true"
    ></span>
  </span>
</template>

<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — OCT-portal study chip.
 *
 * Mirrors the mockup's `Study` primitive (oct-portal.jsx ~ line 40).
 * Used inside `Assignment` to label which cross-study cohort the
 * resolved candidate belongs to.
 */
interface Props {
  name: string
}

const props = defineProps<Props>()
</script>

<template>
  <span
    class="inline-flex items-center gap-1 rounded-md bg-muw-blue-50 text-muw-blue-700 text-[11px] font-semibold px-2 py-0.5"
    data-testid="study-chip"
  >
    <span class="opacity-60">
      <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
        <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
      </svg>
    </span>
    {{ props.name }}
  </span>
</template>

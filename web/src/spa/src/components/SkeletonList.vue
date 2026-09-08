<script setup lang="ts">
/**
 * UX sweep (#20, 2026-08-12) — reusable loading skeleton for list/card
 * surfaces. Replaces the bare italic "Wird geladen…" text with shimmer
 * placeholder rows so the layout doesn't jump when data arrives.
 *
 * <p>Card variant (default) renders {@link rows} stacked placeholder
 * cards. For a real `<table>` body, use {@link SkeletonRow} instead so
 * the cells align with the header. Decorative — {@code aria-hidden} — and
 * paired with an {@code aria-live} status the caller already owns.
 */
interface Props {
  rows?: number
}
withDefaults(defineProps<Props>(), { rows: 4 })
</script>

<template>
  <div class="space-y-2" aria-hidden="true" data-testid="skeleton-list">
    <div
      v-for="i in rows"
      :key="i"
      class="rounded-md border border-slate-200 bg-white p-3"
    >
      <div class="h-3.5 w-1/3 rounded bg-slate-100 animate-pulse"></div>
      <div class="mt-2 h-3 w-2/3 rounded bg-slate-100 animate-pulse"></div>
    </div>
  </div>
</template>

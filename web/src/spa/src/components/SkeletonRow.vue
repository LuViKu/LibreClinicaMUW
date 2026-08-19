<script setup lang="ts">
/**
 * UX sweep (#20, 2026-08-12) — shimmer placeholder rows for a real
 * {@code <table>} body. Renders {@link rows} × a single {@code <td>}
 * spanning {@link columns} so cells stay aligned with the header while
 * loading. Decorative ({@code aria-hidden}); the caller owns the
 * {@code aria-live} status announcement.
 */
interface Props {
  columns: number
  rows?: number
}
withDefaults(defineProps<Props>(), { rows: 5 })
</script>

<template>
  <tr
    v-for="i in rows"
    :key="`skeleton-${i}`"
    aria-hidden="true"
    data-testid="skeleton-row"
  >
    <td :colspan="columns" class="px-4 py-3">
      <div class="h-3 rounded bg-slate-100 animate-pulse" :style="{ width: `${90 - (i % 3) * 15}%` }"></div>
    </td>
  </tr>
</template>

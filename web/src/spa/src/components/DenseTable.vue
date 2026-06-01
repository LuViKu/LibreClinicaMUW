<script setup lang="ts">
/**
 * Phase E.3 primitive — Dense table.
 *
 * The data-density workhorse of every clinical workflow. Used by:
 *  - Subject Matrix (Investigator)
 *  - SDV table (Monitor)
 *  - Notes & Discrepancies, Study Audit Log (Inv/Mon/DM)
 *  - View Events, View Subject, Manage Users
 *  - Import CRF Data wizard preview step
 *
 * Slot-based to allow heterogeneous columns across workflows. The
 * primitive owns: sticky header positioning, border + radius shell,
 * row + cell padding rhythm, optional caption + footer.
 *
 * Why not a fully-driven `<DataGrid columns={…} rows={…}>` API?
 * The 18 mockups have heterogeneous column types (status pills, diff
 * cells, action button stacks, inline forms). A row-and-column-driven
 * API would balloon into per-cell-renderer props that are harder to
 * audit for a clinical-data UI than plain slot-based composition.
 *
 * Sticky-header rendering (2026-05-31): sticky is applied to each
 * `<th>` cell rather than the `<thead>` wrapper. Two reasons:
 *  1. Backgrounds on `<thead>` don't paint reliably between cells in
 *     HTML tables — the row underneath bleeds through, producing the
 *     overlapping artefact seen in the early E.6 Subject Matrix
 *     screenshots.
 *  2. The bordered shell uses `overflow-clip` (NOT `overflow-hidden`)
 *     so it can clip the table's rounded corners without creating a
 *     scrolling context that would scope the sticky element away from
 *     the viewport. `overflow: clip` ships in every browser ≥ Chrome
 *     90 / Firefox 81 / Safari 16.
 */

interface Props {
  /**
   * Sticky-header offset (px) — usually the top-bar height. When set,
   * each `<th>` becomes sticky at this offset.
   */
  stickyHeaderOffset?: number
  /** Add a hover surface to body rows. Default true. */
  hoverable?: boolean
  /** Optional bordered-shell vs flush-on-page. Default true (bordered). */
  bordered?: boolean
}

withDefaults(defineProps<Props>(), {
  stickyHeaderOffset: undefined,
  hoverable: true,
  bordered: true,
})
</script>

<template>
  <div
    :class="[
      bordered
        ? 'border border-slate-200 rounded-muw bg-white dense-table-shell'
        : 'bg-white',
    ]"
  >
    <table class="w-full text-left text-[13px]">
      <thead
        v-if="$slots.header"
        class="text-xs text-slate-600"
        :class="[
          stickyHeaderOffset !== undefined ? 'dense-thead-sticky' : 'bg-slate-50',
        ]"
        :style="stickyHeaderOffset !== undefined ? { '--dense-sticky-top': `${stickyHeaderOffset}px` } : undefined"
      >
        <slot name="header" />
      </thead>

      <tbody class="divide-y divide-slate-100" :class="[hoverable ? '[&_tr]:hover:bg-slate-50' : '']">
        <slot />
      </tbody>

      <tfoot v-if="$slots.footer">
        <slot name="footer" />
      </tfoot>
    </table>

    <div
      v-if="$slots.statusBar"
      class="border-t border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500 flex items-center justify-between"
    >
      <slot name="statusBar" />
    </div>
  </div>
</template>

<style scoped>
/* The bordered shell clips its rounded corners without becoming a
 * scrolling context — `overflow: clip` does NOT scope sticky
 * descendants the way `overflow: hidden` does. */
.dense-table-shell {
  overflow: clip;
}

/* Per-th sticky positioning + opaque background. The `<th>` cells
 * come from the slot, so the rule descends via `:deep()`. The CSS
 * variable carries the offset from the template's inline style. */
.dense-thead-sticky :deep(th) {
  position: sticky;
  top: var(--dense-sticky-top, 0);
  background-color: rgb(248 250 252); /* slate-50 — #f8fafc */
  z-index: 10;
}
</style>

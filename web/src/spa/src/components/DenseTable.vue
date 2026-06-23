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
  /**
   * 2026-06-23 — opt-in horizontal-scroll wrapper. When true, the
   * <table> is wrapped in a div with overflow-x: auto and the table
   * uses min-w-max instead of w-full, so the consumer can lay out
   * sticky-left + sticky-right columns with a scrolling middle. The
   * default (false) preserves the original w-full + clip-only
   * behaviour for the dense tables that don't need horizontal scroll.
   */
  scrollableX?: boolean
}

withDefaults(defineProps<Props>(), {
  stickyHeaderOffset: undefined,
  hoverable: true,
  bordered: true,
  scrollableX: false,
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
    <!--
      2026-06-23 — when scrollableX is true, wrap the table in a
      horizontal-scroll container so the consumer can mix sticky-left
      / sticky-right columns with a horizontally-scrolling middle
      (e.g. Subject Matrix's visit columns). The wrapper is the scroll
      ancestor for any inner position:sticky cells. Sticky thead still
      tracks the viewport via the page's vertical scroll — that one is
      orthogonal to this X-scroll wrapper.
    -->
    <div :class="scrollableX ? 'dense-table-xscroll' : ''">
      <table
        :class="[
          'text-left text-[13px]',
          scrollableX ? 'min-w-full' : 'w-full',
        ]"
      >
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
    </div>

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
 * descendants the way `overflow: hidden` does, so the sticky thead
 * can attach to the viewport via its stickyHeaderOffset. */
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

/* 2026-06-23 — opt-in horizontal-scroll wrapper. The inner table
 * uses min-w-max so columns size to their content (rather than
 * compressing to fit the wrapper), and the wrapper scrolls when the
 * total width exceeds the shell. */
.dense-table-xscroll {
  overflow-x: auto;
}
</style>

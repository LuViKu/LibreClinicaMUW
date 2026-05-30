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
 */

interface Props {
  /**
   * Sticky-header offset (px) — usually the top-bar height. When set,
   * `<thead>` becomes sticky at this offset.
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
      bordered ? 'border border-slate-200 rounded-muw overflow-hidden bg-white' : 'bg-white',
    ]"
  >
    <table class="w-full text-left text-[13px]">
      <thead
        v-if="$slots.header"
        class="bg-slate-50 text-xs text-slate-600"
        :class="[stickyHeaderOffset !== undefined ? 'sticky z-10' : '']"
        :style="stickyHeaderOffset !== undefined ? { top: `${stickyHeaderOffset}px` } : undefined"
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

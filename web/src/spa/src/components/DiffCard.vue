<script setup lang="ts">
/**
 * Phase E.3 primitive — Before/after diff card.
 *
 * The Audit Log shows every Reason-for-Change edit as a side-by-side
 * before/after card; the Import-CRF-Data wizard shows the same shape
 * per-row in the preview-before-commit step. Same primitive serves
 * both surfaces.
 *
 * Inline cell variant — when the diff sits inside a table cell (Import
 * wizard preview), the consumer renders this with `compact="true"`
 * which collapses the labels into a single-row arrow layout.
 */
interface Props {
  /** Before/after labels — defaults are "Before"/"After". */
  beforeLabel?: string
  afterLabel?: string
  /** Render as inline arrow row (table-cell shape) instead of stacked cards. */
  compact?: boolean
}

withDefaults(defineProps<Props>(), {
  beforeLabel: 'Before',
  afterLabel: 'After',
  compact: false,
})
</script>

<template>
  <template v-if="compact">
    <div class="inline-flex items-center gap-2 text-xs">
      <span class="font-mono px-1.5 py-0.5 rounded bg-rose-50 text-rose-900 border border-rose-200">
        <slot name="before" />
      </span>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="text-slate-400" aria-hidden="true">
        <line x1="5" x2="19" y1="12" y2="12" />
        <polyline points="12 5 19 12 12 19" />
      </svg>
      <span class="font-mono px-1.5 py-0.5 rounded bg-muw-teal-50 text-muw-teal-700 border border-muw-teal-200">
        <slot name="after" />
      </span>
    </div>
  </template>

  <template v-else>
    <div class="grid grid-cols-2 gap-3 text-xs">
      <div class="bg-rose-50 border border-rose-200 rounded p-2">
        <div class="text-[10px] uppercase tracking-wider text-rose-700 font-semibold">{{ beforeLabel }}</div>
        <div class="font-mono text-rose-900 mt-0.5">
          <slot name="before" />
        </div>
      </div>
      <div class="bg-muw-teal-50 border border-muw-teal-200 rounded p-2">
        <div class="text-[10px] uppercase tracking-wider text-muw-teal-700 font-semibold">{{ afterLabel }}</div>
        <div class="font-mono text-muw-teal-900 mt-0.5">
          <slot name="after" />
        </div>
      </div>
    </div>
  </template>
</template>

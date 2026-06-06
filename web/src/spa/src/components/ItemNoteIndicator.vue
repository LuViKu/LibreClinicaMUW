<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ItemNoteSummary } from '@/types/crf'

/**
 * Phase E.6 crf-entry-advanced — inline indicator beside an item
 * label when at least one parent discrepancy note is attached.
 *
 * <p>The popover wiring (full thread view + new-query form) lands
 * in the next slice; for now this is a click-able button that emits
 * an {@code open} event the parent CrfEntryView can wire to its
 * existing routing/dialog stack. Keeps this PR scoped to the chip
 * UX + the rollup API surface.
 */

interface Props {
  summary: ItemNoteSummary
}

const props = defineProps<Props>()
defineEmits<{
  open: [noteIds: string[]]
}>()
const { t } = useI18n()

const tone = computed(() => props.summary.status === 'open'
  ? 'bg-amber-100 text-amber-800 hover:bg-amber-200'
  : 'bg-slate-100 text-slate-600 hover:bg-slate-200')

const label = computed(() => props.summary.status === 'open'
  ? t('crfEntry.itemNote.statusNew', { count: props.summary.openCount })
  : t('crfEntry.itemNote.statusResolved', { count: props.summary.totalCount }))
</script>

<template>
  <button
    type="button"
    class="inline-flex items-center gap-1 ml-1 px-1.5 py-0.5 rounded text-[10px] font-medium"
    :class="tone"
    :aria-label="t('crfEntry.itemNote.openThread')"
    :title="label"
    @click="$emit('open', summary.noteIds)"
  >
    <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
    </svg>
    {{ label }}
  </button>
</template>

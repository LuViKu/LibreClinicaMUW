<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ItemNoteSummary } from '@/types/crf'

/**
 * Phase E.6 crf-entry-advanced — inline indicator beside an item
 * label.
 *
 * <p>When at least one parent discrepancy note is attached
 * ({@code summary != null}) the indicator chips the current
 * open/resolved status and emits {@code open} with the note ids
 * to drive the popover. When no notes exist yet
 * ({@code summary == null}) the indicator becomes a ghost
 * "+ Frage" button that emits {@code create} so the parent can
 * launch the new-query form. Same affordance lives in both
 * states to keep operator scanning consistent.
 */

interface Props {
  summary: ItemNoteSummary | null
}

const props = defineProps<Props>()
defineEmits<{
  open: [noteIds: string[]]
  create: []
}>()
const { t } = useI18n()

const tone = computed(() => props.summary?.status === 'open'
  ? 'bg-amber-100 text-amber-800 hover:bg-amber-200'
  : 'bg-slate-100 text-slate-600 hover:bg-slate-200')

const label = computed(() => props.summary?.status === 'open'
  ? t('crfEntry.itemNote.statusNew', { count: props.summary.openCount })
  : t('crfEntry.itemNote.statusResolved', { count: props.summary?.totalCount ?? 0 }))
</script>

<template>
  <button
    v-if="summary"
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
  <button
    v-else
    type="button"
    class="inline-flex items-center gap-1 ml-1 px-1.5 py-0.5 rounded text-[10px] font-medium text-slate-500 hover:text-slate-700 hover:bg-slate-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-slate-400"
    :aria-label="t('crfEntry.itemNote.createNew')"
    :title="t('crfEntry.itemNote.createNew')"
    @click="$emit('create')"
  >
    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
    {{ t('crfEntry.itemNote.createNew') }}
  </button>
</template>

<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — review-queue summary bar.
 *
 * Mirrors the mockup's `SummaryBar` (oct-portal.jsx ~ line 271).
 * Renders a single-line count summary on the left + the five status
 * pills + the batch "X Vorschläge bestätigen" CTA on the right.
 *
 * The CTA is hidden when there are zero suggestions — clicking a
 * disabled button is more frustrating than seeing it disappear.
 */
import { computed } from 'vue'

import PortalStatusPill from './PortalStatusPill.vue'
import type { ReviewRow } from '@/stores/octPortal'

interface Props {
  rows: ReviewRow[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'confirm-all': []
}>()

const totalCount = computed(() => props.rows.length)
const recognisedCount = computed(() => props.rows.filter((r) => r.state !== 'error').length)

/** Unique studies across all rows that have a selected candidate. */
const distinctStudyCount = computed(() => {
  const studies = new Set<number>()
  for (const r of props.rows) {
    if (r.selectedCandidate) studies.add(r.selectedCandidate.studyId)
  }
  return studies.size
})

const counts = computed(() => ({
  suggested: props.rows.filter((r) => r.state === 'suggested').length,
  confirmed: props.rows.filter((r) => r.state === 'confirmed' || r.state === 'committed').length,
  novisit: props.rows.filter((r) => r.state === 'novisit').length,
  nopatient: props.rows.filter((r) => r.state === 'nopatient').length,
  error: props.rows.filter((r) => r.state === 'error').length,
  ambiguous: props.rows.filter((r) => r.state === 'ambiguous').length,
}))

const showSuggestPill = computed(() => counts.value.suggested > 0)
const showConfirmedPill = computed(() => counts.value.confirmed > 0)
const showNovisitPill = computed(() => counts.value.novisit > 0)
const showNopatientPill = computed(() => counts.value.nopatient > 0)
const showAmbiguousPill = computed(() => counts.value.ambiguous > 0)
const showErrorPill = computed(() => counts.value.error > 0)

const showConfirmAllButton = computed(() => counts.value.suggested > 0)
</script>

<template>
  <div class="flex items-center gap-3 mb-4 flex-wrap" data-testid="summary-bar">
    <div class="text-[13px] text-slate-500">
      {{ totalCount }} {{ totalCount === 1 ? 'Datei' : 'Dateien' }} ·
      <span class="text-slate-700 font-medium">{{ recognisedCount }} erkannt</span>
      <template v-if="distinctStudyCount > 0">
        · {{ distinctStudyCount }} {{ distinctStudyCount === 1 ? 'Studie' : 'Studien' }}
      </template>
    </div>
    <div class="flex items-center gap-1.5">
      <PortalStatusPill v-if="showSuggestPill" tone="suggest">{{ counts.suggested }} {{ counts.suggested === 1 ? 'Vorschlag' : 'Vorschläge' }}</PortalStatusPill>
      <PortalStatusPill v-if="showConfirmedPill" tone="ok">{{ counts.confirmed }} bestätigt</PortalStatusPill>
      <PortalStatusPill v-if="showNovisitPill" tone="sky">{{ counts.novisit }} ohne Termin</PortalStatusPill>
      <PortalStatusPill v-if="showNopatientPill" tone="bad">{{ counts.nopatient }} ohne Patient</PortalStatusPill>
      <PortalStatusPill v-if="showAmbiguousPill" tone="suggest">{{ counts.ambiguous }} mehrdeutig</PortalStatusPill>
      <PortalStatusPill v-if="showErrorPill" tone="mute">{{ counts.error }} {{ counts.error === 1 ? 'Problem' : 'Probleme' }}</PortalStatusPill>
    </div>
    <div class="ml-auto">
      <button
        v-if="showConfirmAllButton"
        type="button"
        class="px-3.5 py-2 text-[13px] font-semibold bg-muw-blue text-white rounded-lg hover:bg-muw-blue-700 inline-flex items-center gap-2 shadow-[0_1px_2px_rgba(17,29,78,0.18)] whitespace-nowrap"
        data-testid="confirm-all-button"
        @click="emit('confirm-all')"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
        {{ counts.suggested }} {{ counts.suggested === 1 ? 'Vorschlag' : 'Vorschläge' }} bestätigen
      </button>
    </div>
  </div>
</template>

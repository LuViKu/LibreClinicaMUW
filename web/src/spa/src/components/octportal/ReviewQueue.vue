<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — review-queue card.
 *
 * Mirrors the mockup's review-card composition (oct-portal.jsx ~ line
 * 325): a header strip with column labels + the per-row {@link
 * ReviewRow}s. Emits are pass-throughs so the parent view holds the
 * single source of truth for store wiring.
 */
import ReviewRow from './ReviewRow.vue'
import type { ReviewRow as ReviewRowData } from '@/stores/octPortal'

interface Props {
  rows: ReviewRowData[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  confirm: [rowId: string]
  undo: [rowId: string]
  'pick-visit': [rowId: string]
  park: [rowId: string]
  'search-patient': [rowId: string]
  dismiss: [rowId: string]
}>()
</script>

<template>
  <div
    class="border border-slate-200 rounded-xl overflow-hidden bg-white shadow-[0_1px_2px_rgba(17,29,78,0.04)]"
    data-testid="review-queue"
  >
    <!-- column header strip -->
    <div class="flex items-center gap-4 px-5 py-2.5 bg-slate-50 text-[11px] font-semibold uppercase tracking-[0.08em] text-slate-400">
      <div class="w-[224px] shrink-0">Datei</div>
      <div class="w-[96px] shrink-0">PatientId</div>
      <div class="w-[150px] shrink-0">Scan-Datum</div>
      <div class="w-[68px] shrink-0">Auge</div>
      <div class="flex-1">Studie · Visite</div>
    </div>

    <ReviewRow
      v-for="row in props.rows"
      :key="row.rowId"
      :row="row"
      @confirm="(id) => emit('confirm', id)"
      @undo="(id) => emit('undo', id)"
      @pick-visit="(id) => emit('pick-visit', id)"
      @park="(id) => emit('park', id)"
      @search-patient="(id) => emit('search-patient', id)"
      @dismiss="(id) => emit('dismiss', id)"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — review-queue row.
 *
 * Mirrors the mockup's `ReviewRow` (oct-portal.jsx ~ line 204). Lays
 * out the row meta (file/pid/date/eye) + the {@link Assignment}
 * sub-component which renders the five sub-states + action buttons.
 *
 * Emits surface the operator's pick to the parent
 * OctUploadPortalView (which routes them to the store).
 */
import { computed } from 'vue'

import Assignment from './Assignment.vue'
import EyeBadge from './EyeBadge.vue'
import type { ReviewRow as ReviewRowData } from '@/stores/octPortal'

interface Props {
  row: ReviewRowData
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

const dim = computed(() => props.row.state === 'error')

const sizeLabel = computed(() => {
  const n = props.row.file.size
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
})

const scanDateTimeLabel = computed(() => {
  const d = props.row.scan?.scanDate
  if (!d) return ''
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  const dd = String(d.getDate()).padStart(2, '0')
  const mm = months[d.getMonth()]
  const yyyy = d.getFullYear()
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${dd}-${mm}-${yyyy} · ${hh}:${mi}`
})
</script>

<template>
  <div
    class="flex items-center gap-4 px-5 py-3.5 border-t border-slate-100"
    :class="dim ? 'bg-slate-50/40' : 'hover:bg-slate-50/60'"
    :data-testid="`review-row-${props.row.rowId}`"
    :data-row-state="props.row.state"
  >
    <!-- file / size column -->
    <div class="w-[224px] flex items-center gap-3 shrink-0">
      <span
        class="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
        :class="dim ? 'bg-slate-100 text-slate-400' : 'bg-muw-blue-50 text-muw-blue'"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
        </svg>
      </span>
      <div class="min-w-0">
        <div
          class="text-[13px] font-semibold truncate"
          :class="dim ? 'text-slate-500' : 'text-slate-900'"
          :title="props.row.file.name"
        >{{ props.row.file.name }}</div>
        <div class="text-[11px] text-slate-400">{{ sizeLabel }}</div>
      </div>
    </div>

    <!-- pid -->
    <div class="w-[96px] shrink-0">
      <span
        v-if="props.row.scan?.patientId"
        class="font-mono text-[12px] font-medium text-slate-700"
      >{{ props.row.scan.patientId }}</span>
      <span v-else class="text-slate-300">—</span>
    </div>

    <!-- scan date -->
    <div class="w-[150px] shrink-0 text-[12px] text-slate-500">
      <span v-if="scanDateTimeLabel">{{ scanDateTimeLabel }}</span>
      <span v-else class="text-slate-300">—</span>
    </div>

    <!-- eye -->
    <div class="w-[68px] shrink-0">
      <EyeBadge :laterality="props.row.scan?.laterality ?? null" />
    </div>

    <!-- assignment + action -->
    <Assignment
      :row="props.row"
      @confirm="(id) => emit('confirm', id)"
      @undo="(id) => emit('undo', id)"
      @pick-visit="(id) => emit('pick-visit', id)"
      @park="(id) => emit('park', id)"
      @search-patient="(id) => emit('search-patient', id)"
      @dismiss="(id) => emit('dismiss', id)"
    />
  </div>
</template>

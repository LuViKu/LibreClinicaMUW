<script setup lang="ts">
/**
 * DR-029 — one row of the combined uploader's review list.
 *
 * File, what it is, whose it is, when, which eye, and the assignment. The
 * eye cell is what varies by kind: an OCT scan and a DICOM export say it
 * themselves, a photo cannot, so an image row (or a DICOM row whose header
 * was silent) carries a select the operator sets before confirming.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import EyeBadge from '@/components/octportal/EyeBadge.vue'
import UploadAssignment from './UploadAssignment.vue'
import { useUploadWorkbenchStore, type Laterality, type UploadRow as UploadRowData } from '@/stores/uploadWorkbench'
import { formatDate } from '@/lib/dateFormat'

interface Props {
  row: UploadRowData
}

const props = defineProps<Props>()
const store = useUploadWorkbenchStore()
const { t } = useI18n()

const emit = defineEmits<{
  confirm: [rowId: string]
  undo: [rowId: string]
  'pick-visit': [rowId: string]
  'pick-study': [rowId: string]
  park: [rowId: string]
  'search-patient': [rowId: string]
  dismiss: [rowId: string]
}>()

const showFill = computed(() => props.row.state === 'committing')
const uploadPct = computed<number>(() => store.uploadPct.get(props.row.rowId) ?? 0)
const fillStyle = computed(() => ({ '--muw-portal-fill-pct': `${uploadPct.value}%` }))
const dim = computed(() => props.row.state === 'error')

const sizeLabel = computed(() => {
  const n = props.row.file.size
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
})

const kindLabel = computed(() => (props.row.format ? t(`uploadPortal.kind.${props.row.format}`) : '—'))

const kindTone = computed(() => {
  switch (props.row.kind) {
    case 'e2e': return 'bg-muw-blue-50 text-muw-blue ring-muw-blue-100'
    case 'dicom': return 'bg-violet-50 text-violet-700 ring-violet-100'
    case 'image': return 'bg-muw-teal-50 text-muw-teal-700 ring-muw-teal-100'
    default: return 'bg-slate-100 text-slate-500 ring-slate-200'
  }
})

const dateLabel = computed(() => (props.row.date ? formatDate(props.row.date) : ''))

/** The eye can be set on the row when the file did not say — and only before it is sent. */
const lateralityEditable = computed(() =>
  props.row.kind !== 'e2e'
  && !(props.row.kind === 'dicom' && props.row.hints?.laterality)
  && ['suggested', 'novisit', 'nopatient', 'ambiguous'].includes(props.row.state),
)

function onLateralityChange(e: Event): void {
  const v = (e.target as HTMLSelectElement).value
  store.setRowLaterality(props.row.rowId, (v || null) as Laterality | null)
}
</script>

<template>
  <div
    class="relative overflow-hidden flex flex-col md:flex-row md:items-center gap-3 md:gap-4 px-4 md:px-5 py-3.5 border-t border-slate-100"
    :class="dim ? 'bg-slate-50/40' : 'hover:bg-slate-50/60'"
    :data-testid="`upload-row-${props.row.rowId}`"
    :data-row-state="props.row.state"
    :data-row-kind="props.row.kind ?? 'unknown'"
    :style="showFill ? fillStyle : undefined"
  >
    <div v-if="showFill" class="muw-portal-fill" aria-hidden="true"></div>
    <div v-if="showFill" class="muw-portal-fill-edge" aria-hidden="true"></div>

    <div class="md:w-[260px] flex items-center gap-3 shrink-0 relative z-10 min-w-0">
      <span
        class="inline-flex items-center justify-center h-7 min-w-[52px] px-2 rounded-md text-[11px] font-semibold ring-1 shrink-0"
        :class="kindTone"
        data-testid="row-kind"
      >{{ kindLabel }}</span>
      <div class="min-w-0">
        <div class="text-[13px] font-semibold truncate" :class="dim ? 'text-slate-500' : 'text-slate-900'" :title="props.row.file.name">{{ props.row.file.name }}</div>
        <div class="text-[11px] text-slate-400">{{ sizeLabel }}<template v-if="props.row.hints?.modelName"> · {{ props.row.hints.modelName }}</template></div>
      </div>
    </div>

    <div class="md:w-[110px] shrink-0 relative z-10">
      <span v-if="props.row.patientId" class="font-mono text-[12px] font-medium text-slate-700">{{ props.row.patientId }}</span>
      <span v-else class="text-slate-500">—</span>
    </div>

    <div class="md:w-[110px] shrink-0 text-[12px] text-slate-500 relative z-10">
      <span v-if="dateLabel">{{ dateLabel }}</span>
      <span v-else class="text-slate-500">—</span>
    </div>

    <div class="md:w-[92px] shrink-0 relative z-10">
      <select
        v-if="lateralityEditable"
        class="w-full px-2 py-1.5 border border-slate-300 rounded-lg bg-white text-[12px] min-h-8 focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
        :value="props.row.laterality ?? ''"
        :aria-label="t('uploadPortal.row.lateralityAria', { file: props.row.file.name })"
        :data-testid="`row-laterality-${props.row.rowId}`"
        @change="onLateralityChange"
      >
        <option value="">—</option>
        <option value="OD">OD</option>
        <option value="OS">OS</option>
        <option value="OU">OU</option>
      </select>
      <template v-else>
        <EyeBadge v-if="props.row.laterality !== 'OU'" :laterality="props.row.laterality" />
        <span v-else class="inline-flex items-center px-2 py-0.5 rounded-md bg-slate-100 text-slate-700 text-[11px] font-semibold">OU</span>
      </template>
    </div>

    <div class="relative z-10 flex-1 flex items-center gap-4 min-w-0">
      <UploadAssignment
        :row="props.row"
        @confirm="(id) => emit('confirm', id)"
        @undo="(id) => emit('undo', id)"
        @pick-visit="(id) => emit('pick-visit', id)"
        @pick-study="(id) => emit('pick-study', id)"
        @park="(id) => emit('park', id)"
        @search-patient="(id) => emit('search-patient', id)"
        @dismiss="(id) => emit('dismiss', id)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * DR-029 — the review list's summary line and the batch confirm button.
 *
 * Counts by state and kind on the left, the "confirm N" call to action on
 * the right — hidden when there is nothing to confirm, because a disabled
 * button asks a question the operator cannot answer.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import PortalStatusPill from '@/components/octportal/PortalStatusPill.vue'
import type { UploadRow } from '@/stores/uploadWorkbench'

const { t } = useI18n()

interface Props {
  rows: UploadRow[]
}

const props = defineProps<Props>()
const emit = defineEmits<{ 'confirm-all': [] }>()

const totalCount = computed(() => props.rows.length)
const byKind = computed(() => ({
  e2e: props.rows.filter((r) => r.kind === 'e2e').length,
  dicom: props.rows.filter((r) => r.kind === 'dicom').length,
  image: props.rows.filter((r) => r.kind === 'image').length,
}))
const counts = computed(() => ({
  suggested: props.rows.filter((r) => r.state === 'suggested').length,
  committed: props.rows.filter((r) => r.state === 'committed' || r.state === 'confirmed').length,
  novisit: props.rows.filter((r) => r.state === 'novisit').length,
  nopatient: props.rows.filter((r) => r.state === 'nopatient').length,
  ambiguous: props.rows.filter((r) => r.state === 'ambiguous').length,
  duplicate: props.rows.filter((r) => r.state === 'duplicate').length,
  error: props.rows.filter((r) => r.state === 'error').length,
}))
</script>

<template>
  <div
    class="flex flex-wrap items-center justify-between gap-3 px-4 md:px-5 py-3 bg-white rounded-t-2xl ring-1 ring-slate-200"
    data-testid="upload-summary"
  >
    <div class="text-[13px] text-slate-500 flex flex-wrap items-center gap-x-2 gap-y-1">
      <span>
        {{ totalCount }} {{ totalCount === 1 ? t('uploadPortal.summary.fileSingular') : t('uploadPortal.summary.filePlural') }}
      </span>
      <span v-if="byKind.e2e" class="text-slate-400">· {{ byKind.e2e }} {{ t('uploadPortal.kind.e2e') }}</span>
      <span v-if="byKind.dicom" class="text-slate-400">· {{ byKind.dicom }} {{ t('uploadPortal.kind.dicom') }}</span>
      <span v-if="byKind.image" class="text-slate-400">· {{ byKind.image }} {{ t('uploadPortal.kind.imageShort') }}</span>
    </div>
    <div class="flex flex-wrap items-center gap-2">
      <PortalStatusPill v-if="counts.suggested" tone="suggest">{{ counts.suggested }} {{ t('uploadPortal.summary.ready') }}</PortalStatusPill>
      <PortalStatusPill v-if="counts.committed" tone="ok">{{ counts.committed }} {{ t('uploadPortal.summary.sent') }}</PortalStatusPill>
      <PortalStatusPill v-if="counts.novisit" tone="sky">{{ counts.novisit }} {{ t('octPortal.summary.noVisitSuffix') }}</PortalStatusPill>
      <PortalStatusPill v-if="counts.nopatient" tone="bad">{{ counts.nopatient }} {{ t('octPortal.summary.noPatientSuffix') }}</PortalStatusPill>
      <PortalStatusPill v-if="counts.ambiguous" tone="suggest">{{ counts.ambiguous }} {{ t('octPortal.summary.ambiguousSuffix') }}</PortalStatusPill>
      <PortalStatusPill v-if="counts.duplicate" tone="ok">{{ counts.duplicate }} {{ t('uploadPortal.summary.duplicate') }}</PortalStatusPill>
      <PortalStatusPill v-if="counts.error" tone="bad">{{ counts.error }} {{ t('uploadPortal.summary.refused') }}</PortalStatusPill>
      <button
        v-if="counts.suggested > 0"
        type="button"
        class="ml-1 px-3.5 py-2 text-[13px] font-semibold bg-muw-blue text-white rounded-lg hover:bg-muw-blue-700 inline-flex items-center gap-2 shadow-[0_1px_2px_rgba(17,29,78,0.18)] whitespace-nowrap"
        data-testid="confirm-all"
        @click="emit('confirm-all')"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
        {{ t('uploadPortal.summary.confirmAll', { n: counts.suggested }) }}
      </button>
    </div>
  </div>
</template>

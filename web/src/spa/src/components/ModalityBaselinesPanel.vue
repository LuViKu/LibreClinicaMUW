<script setup lang="ts">
/**
 * Phase E.6 — Per-eye modality baselines panel.
 *
 * Renders the global-vs-per-study baseline table for one eye on the
 * Subject Detail view. The Subject Detail view mounts one instance
 * per in-scope eye (and per transitioned-away eye that still has
 * historic baselines).
 *
 * Layout: a dense table with one row per modality. Two date+value
 * column pairs side by side — global baseline on the left, per-study
 * baseline on the right — plus the observation count.
 *
 * Empty cells (`global.date === null` / `perStudy.date === null`) show
 * an em-dash. Numeric values are suffixed with their unit when present
 * (e.g. `14 mmHg`, `0.6 logMAR`); categorical values render bare.
 *
 * Loading and error states match the SPA's convention: a small set of
 * skeleton rows while the request is in flight; an inline ErrorText
 * with a retry button on failure.
 */

import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'

import DenseTable from '@/components/DenseTable.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useModalityBaselinesStore, baselinesKey, type EyeScope } from '@/stores/modalityBaselines'
import type { ModalityBaseline, ModalityBaselineSnapshot } from '@/types/baselines'

interface Props {
  subjectLabel: string
  eye: EyeScope
  /**
   * Display locale for the modality label column. Defaults to German
   * — same as the rest of the SPA. The wire contract carries both
   * locales so we don't need to re-fetch when the user toggles.
   */
  i18nLocale?: 'en' | 'de'
}

const props = withDefaults(defineProps<Props>(), { i18nLocale: 'de' })

const { t } = useI18n()
const store = useModalityBaselinesStore()

const key = computed(() => baselinesKey(props.subjectLabel, props.eye))
const rows = computed<ModalityBaseline[]>(() => store.byKey.get(key.value) ?? [])
const isLoading = computed(() => store.isLoadingByKey.get(key.value) === true)
const errorMessage = computed(() => store.errorByKey.get(key.value) ?? null)
// Distinguish "still on the initial fetch" from "fetched, just empty".
const hasFetched = computed(() => store.byKey.has(key.value))

onMounted(() => {
  void store.load(props.subjectLabel, props.eye)
})

function modalityLabel(row: ModalityBaseline): string {
  return props.i18nLocale === 'en' ? row.labelEn : row.labelDe
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return iso
}

function formatValue(snapshot: ModalityBaselineSnapshot, row: ModalityBaseline): string {
  if (snapshot.value === null) return '—'
  if (row.dataType === 'numeric' && row.unit) {
    return `${snapshot.value} ${row.unit}`
  }
  return snapshot.value
}

async function retry() {
  await store.load(props.subjectLabel, props.eye, true)
}
</script>

<template>
  <section
    class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5"
    :data-testid="`modality-baselines-${eye}`"
  >
    <div class="px-5 py-3 border-b border-slate-200">
      <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
        {{ t('subjectDetail.modalityBaselines.title', { eye }) }}
      </h2>
    </div>

    <!-- Loading skeleton — three placeholder rows so the layout
         doesn't jump when the data arrives. -->
    <DenseTable v-if="isLoading && !hasFetched" :bordered="false" :hoverable="false">
      <template #header>
        <tr class="border-b border-slate-200">
          <th scope="col" class="px-5 py-2 font-medium">{{ t('subjectDetail.modalityBaselines.columns.modality') }}</th>
          <th scope="col" class="px-5 py-2 font-medium w-28">{{ t('subjectDetail.modalityBaselines.columns.baselineDateGlobal') }}</th>
          <th scope="col" class="px-5 py-2 font-medium">{{ t('subjectDetail.modalityBaselines.columns.baselineValueGlobal') }}</th>
          <th scope="col" class="px-5 py-2 font-medium w-28">{{ t('subjectDetail.modalityBaselines.columns.baselineDatePerStudy') }}</th>
          <th scope="col" class="px-5 py-2 font-medium">{{ t('subjectDetail.modalityBaselines.columns.baselineValuePerStudy') }}</th>
          <th scope="col" class="px-5 py-2 font-medium w-16 text-right">{{ t('subjectDetail.modalityBaselines.columns.count') }}</th>
        </tr>
      </template>
      <tr v-for="i in 3" :key="`skeleton-${i}`" data-testid="modality-baselines-skeleton-row">
        <td class="px-5 py-2.5"><div class="h-3 bg-slate-100 rounded w-24 animate-pulse" /></td>
        <td class="px-5 py-2.5"><div class="h-3 bg-slate-100 rounded w-20 animate-pulse" /></td>
        <td class="px-5 py-2.5"><div class="h-3 bg-slate-100 rounded w-16 animate-pulse" /></td>
        <td class="px-5 py-2.5"><div class="h-3 bg-slate-100 rounded w-20 animate-pulse" /></td>
        <td class="px-5 py-2.5"><div class="h-3 bg-slate-100 rounded w-16 animate-pulse" /></td>
        <td class="px-5 py-2.5 text-right"><div class="h-3 bg-slate-100 rounded w-6 ml-auto animate-pulse" /></td>
      </tr>
    </DenseTable>

    <!-- Error state — inline ErrorText + retry button. We render this
         INSTEAD of the table so the operator focuses on the fix.  -->
    <div
      v-else-if="errorMessage"
      class="px-5 py-4"
      data-testid="modality-baselines-error"
    >
      <ErrorText>{{ errorMessage }}</ErrorText>
      <button
        type="button"
        class="mt-2 text-xs text-muw-blue hover:underline"
        data-testid="modality-baselines-retry"
        @click="retry"
      >
        {{ t('subjectDetail.modalityBaselines.error.retry') }}
      </button>
    </div>

    <!-- Empty state — fetch came back with zero modalities. -->
    <div
      v-else-if="hasFetched && rows.length === 0"
      class="px-5 py-6 text-xs text-slate-500 italic"
      data-testid="modality-baselines-empty"
    >
      {{ t('subjectDetail.modalityBaselines.empty') }}
    </div>

    <DenseTable v-else :bordered="false">
      <template #header>
        <tr class="border-b border-slate-200">
          <th scope="col" class="px-5 py-2 font-medium">{{ t('subjectDetail.modalityBaselines.columns.modality') }}</th>
          <th scope="col" class="px-5 py-2 font-medium w-28">{{ t('subjectDetail.modalityBaselines.columns.baselineDateGlobal') }}</th>
          <th scope="col" class="px-5 py-2 font-medium">{{ t('subjectDetail.modalityBaselines.columns.baselineValueGlobal') }}</th>
          <th scope="col" class="px-5 py-2 font-medium w-28">{{ t('subjectDetail.modalityBaselines.columns.baselineDatePerStudy') }}</th>
          <th scope="col" class="px-5 py-2 font-medium">{{ t('subjectDetail.modalityBaselines.columns.baselineValuePerStudy') }}</th>
          <th scope="col" class="px-5 py-2 font-medium w-16 text-right">{{ t('subjectDetail.modalityBaselines.columns.count') }}</th>
        </tr>
      </template>
      <tr
        v-for="row in rows"
        :key="row.modalityCode"
        data-testid="modality-baselines-row"
      >
        <td class="px-5 py-2.5 font-medium">
          <div>{{ modalityLabel(row) }}</div>
          <div class="text-[10px] font-mono text-slate-400 mt-0.5">{{ row.modalityCode }}</div>
        </td>
        <td class="px-5 py-2.5 text-xs font-mono text-slate-600">{{ formatDate(row.global.date) }}</td>
        <td class="px-5 py-2.5 text-sm">{{ formatValue(row.global, row) }}</td>
        <td class="px-5 py-2.5 text-xs font-mono text-slate-600">{{ formatDate(row.perStudy.date) }}</td>
        <td class="px-5 py-2.5 text-sm">{{ formatValue(row.perStudy, row) }}</td>
        <td class="px-5 py-2.5 text-right text-xs text-slate-500">{{ row.perStudy.observationCount }}</td>
      </tr>
    </DenseTable>
  </section>
</template>

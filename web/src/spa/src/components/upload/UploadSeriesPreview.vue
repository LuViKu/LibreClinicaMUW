<script setup lang="ts">
/**
 * DR-041 amendment (b) — the browser preview of a split E2E row.
 *
 * Before the upload the person sees what the file holds: the label read from it and whether it
 * agrees with the folder, and every series grouped by eye with a thumbnail, its modality, frames,
 * date and time, the visit it would land on, and a checkbox. Unticked series are left out of the
 * upload (the commit sends them as `excludeSeries`). After the upload the server's items are
 * shown by the row as before; this component then only marks the series that were left out.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import EyeBadge from '@/components/octportal/EyeBadge.vue'
import { renderSeriesThumbnail, type E2eSeriesInfo } from '@/lib/e2eSeries'
import { eyeOf, groupByEye } from '@/lib/e2eSplitPreview'
import { formatDate } from '@/lib/dateFormat'
import { hasNoSeriesLeft, useUploadWorkbenchStore, type UploadRow } from '@/stores/uploadWorkbench'

const props = defineProps<{ row: UploadRow }>()
const store = useUploadWorkbenchStore()
const { t, te } = useI18n()

const preview = computed(() => props.row.preview)
const ready = computed(() => preview.value?.status === 'ready')
const committed = computed(() => props.row.state === 'committed')
const locked = computed(() => props.row.state === 'committing' || committed.value)
const series = computed<E2eSeriesInfo[]>(() => preview.value?.series ?? [])
const groups = computed(() => groupByEye(series.value))
const off = computed(() => new Set(props.row.excludedKeys ?? []))
const selectedCount = computed(() => series.value.filter((s) => !off.value.has(s.seriesKey)).length)
const noneLeft = computed(() => hasNoSeriesLeft(props.row))
const leftOut = computed(() => series.value.filter((s) => off.value.has(s.seriesKey)))
const modalities = computed(() => Array.from(new Set(series.value.map((s) => s.seriesModality))))

const folder = computed(() => props.row.patientId.trim())

function modalityLabel(m: string): string {
  const key = `uploadPortal.split.modality.${m}`
  return te(key) ? t(key) : m
}

function eyeLabel(eye: string): string {
  return t(`uploadPortal.split.preview.eye.${eye}`)
}

function timeOf(s: E2eSeriesInfo): string {
  if (!s.acquiredAt) return ''
  const time = s.acquiredAt.slice(11, 16)
  return time ? `${formatDate(s.acquiredAt)} ${time}` : formatDate(s.acquiredAt)
}

function describeSeries(s: E2eSeriesInfo): string {
  return [modalityLabel(s.seriesModality), eyeOf(s) === 'none' ? '' : eyeOf(s), timeOf(s)].filter(Boolean).join(', ')
}

function visitText(s: E2eSeriesInfo): string {
  const manual = props.row.selectedEvent
  if (manual && manual.matchPolicy === 'visit-picked') {
    return t('uploadPortal.split.preview.visit.picked', { visit: manual.definitionLabel })
  }
  const v = preview.value?.visits[s.seriesKey]
  if (!v) return ''
  if (v.state === 'nopatient') return t('uploadPortal.split.preview.visit.noSubject')
  if (v.visitLabel) {
    return t('uploadPortal.split.preview.visit.suggested', {
      visit: v.visitLabel,
      date: v.visitDate ? formatDate(v.visitDate) : '',
    }).trim()
  }
  if (v.state === 'ambiguous') return t('uploadPortal.split.preview.visit.ambiguous')
  return t('uploadPortal.split.preview.visit.none')
}

function onTick(s: E2eSeriesInfo, e: Event): void {
  store.setSeriesIncluded(props.row.rowId, [s.seriesKey], (e.target as HTMLInputElement).checked)
}

function setGroup(group: E2eSeriesInfo[], included: boolean): void {
  store.setSeriesIncluded(props.row.rowId, group.map((s) => s.seriesKey), included)
}

function modalityState(m: string): boolean {
  return series.value.filter((s) => s.seriesModality === m).every((s) => !off.value.has(s.seriesKey))
}

function toggleModality(m: string): void {
  const keys = series.value.filter((s) => s.seriesModality === m).map((s) => s.seriesKey)
  store.setSeriesIncluded(props.row.rowId, keys, !modalityState(m))
}

function groupCount(group: E2eSeriesInfo[]): number {
  return group.filter((s) => !off.value.has(s.seriesKey)).length
}

// ---- thumbnails (renderSeriesThumbnail reads one image at a time across all rows)
const thumbs = ref<Record<string, string | null>>({})
let alive = true
let started = false
onBeforeUnmount(() => { alive = false })

async function loadThumbs(): Promise<void> {
  if (started || !ready.value || committed.value) return
  started = true
  for (const s of series.value) {
    if (!alive) return
    try {
      thumbs.value = { ...thumbs.value, [s.seriesKey]: await renderSeriesThumbnail(props.row.file, s) }
    } catch {
      thumbs.value = { ...thumbs.value, [s.seriesKey]: null }
    }
  }
}
watch(ready, () => { void loadThumbs() }, { immediate: true })
</script>

<template>
  <div
    v-if="preview && (ready || preview.status === 'failed') && !(committed && leftOut.length === 0)"
    class="relative z-10 w-full md:basis-full rounded-lg bg-white ring-1 ring-slate-200 px-3 py-2.5"
    :data-testid="`series-preview-${props.row.rowId}`"
  >
    <template v-if="committed">
      <div class="text-[12px] font-medium text-slate-700" data-testid="series-left-out-summary">
        {{ t('uploadPortal.split.preview.leftOut', { n: leftOut.length }) }}
      </div>
      <ul class="mt-1 text-[12px] text-slate-500">
        <li v-for="s in leftOut" :key="s.seriesKey" :data-testid="`series-left-out-${s.seriesKey}`">
          {{ describeSeries(s) }} · {{ t('uploadPortal.split.preview.leftOutTag') }}
        </li>
      </ul>
    </template>

    <p v-else-if="preview.status === 'failed'" class="text-[12px] text-slate-600" data-testid="series-read-failed">
      {{ t('uploadPortal.split.preview.readFailed') }}
    </p>

    <template v-else>
      <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span class="text-[12px] font-semibold text-slate-800">{{ t('uploadPortal.split.preview.title') }}</span>
        <span class="text-[12px] text-slate-500 tabular-nums" data-testid="series-selected-count">
          {{ t('uploadPortal.split.preview.selected', { n: selectedCount, total: series.length }) }}
        </span>
        <span
          v-if="preview.headerLabel"
          class="text-[12px] text-slate-500"
          data-testid="series-header-label"
        >{{ t('uploadPortal.split.preview.labelInFile') }} <span class="font-mono text-slate-700">{{ preview.headerLabel }}</span></span>
      </div>

      <p
        v-if="preview.labelCheck === 'differs'"
        class="mt-1 text-[12px] text-amber-800"
        role="alert"
        data-testid="series-label-differs"
      >{{ t('uploadPortal.split.preview.labelDiffers', { label: preview.headerLabel ?? '', folder }) }}</p>
      <p
        v-else-if="preview.labelCheck === 'folder-only'"
        class="mt-1 text-[12px] text-slate-600"
        data-testid="series-label-folder-only"
      >{{ t('uploadPortal.split.preview.labelFolderUsed', { folder }) }}</p>
      <p
        v-else-if="preview.labelCheck === 'none'"
        class="mt-1 text-[12px] text-amber-800"
        data-testid="series-label-none"
      >{{ t('uploadPortal.split.preview.labelNone') }}</p>
      <p
        v-else-if="preview.labelCheck === 'matches'"
        class="mt-1 text-[12px] text-emerald-800"
        data-testid="series-label-matches"
      >{{ t('uploadPortal.split.preview.labelMatches') }}</p>

      <p
        v-if="noneLeft"
        class="mt-1 text-[12px] font-medium text-amber-800"
        role="alert"
        data-testid="series-none-selected"
      >{{ t('uploadPortal.split.preview.noneSelected') }}</p>

      <div v-if="modalities.length > 1" class="mt-2 flex flex-wrap items-center gap-1.5" role="group" :aria-label="t('uploadPortal.split.preview.byModality')">
        <button
          v-for="m in modalities"
          :key="m"
          type="button"
          class="px-2 py-0.5 rounded-md text-[11px] font-medium ring-1 min-h-6"
          :class="modalityState(m) ? 'bg-muw-blue-50 text-muw-blue ring-muw-blue-100' : 'bg-white text-slate-500 ring-slate-300'"
          :aria-pressed="modalityState(m)"
          :disabled="locked"
          :data-testid="`series-modality-toggle-${m}`"
          @click="toggleModality(m)"
        >{{ modalityLabel(m) }}</button>
      </div>

      <section
        v-for="g in groups"
        :key="g.eye"
        class="mt-2.5"
        :data-testid="`series-eye-${g.eye}`"
      >
        <div class="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-slate-100 pb-1">
          <span class="inline-flex items-center gap-1.5 text-[12px] font-semibold text-slate-800">
            <EyeBadge v-if="g.eye !== 'none'" :laterality="g.eye" />
            {{ eyeLabel(g.eye) }}
          </span>
          <span class="text-[11px] text-slate-500 tabular-nums">{{ groupCount(g.series) }} / {{ g.series.length }}</span>
          <button
            type="button"
            class="text-[11px] font-medium text-muw-blue hover:text-muw-blue-700 min-h-6 px-1 disabled:opacity-50"
            :disabled="locked"
            :data-testid="`series-eye-all-${g.eye}`"
            @click="setGroup(g.series, true)"
          >{{ t('uploadPortal.split.preview.all') }}</button>
          <button
            type="button"
            class="text-[11px] font-medium text-muw-blue hover:text-muw-blue-700 min-h-6 px-1 disabled:opacity-50"
            :disabled="locked"
            :data-testid="`series-eye-none-${g.eye}`"
            @click="setGroup(g.series, false)"
          >{{ t('uploadPortal.split.preview.none') }}</button>
        </div>
        <ul class="divide-y divide-slate-100">
          <li
            v-for="s in g.series"
            :key="s.seriesKey"
            class="flex items-center gap-3 py-1.5"
            :class="off.has(s.seriesKey) ? 'opacity-50' : ''"
            :data-testid="`series-row-${s.seriesKey}`"
          >
            <input
              type="checkbox"
              class="h-4 w-4 shrink-0"
              :checked="!off.has(s.seriesKey)"
              :disabled="locked"
              :aria-label="t('uploadPortal.split.preview.includeAria', { series: describeSeries(s) })"
              :data-testid="`series-check-${s.seriesKey}`"
              @change="onTick(s, $event)"
            />
            <div class="h-14 w-14 shrink-0 rounded bg-slate-900 ring-1 ring-slate-300 overflow-hidden flex items-center justify-center">
              <img
                v-if="thumbs[s.seriesKey]"
                :src="thumbs[s.seriesKey]!"
                alt=""
                class="max-h-full max-w-full object-contain"
                :data-testid="`series-thumb-${s.seriesKey}`"
              />
            </div>
            <div class="min-w-0 flex-1 text-[12px] text-slate-600">
              <div class="flex flex-wrap items-center gap-x-3">
                <span class="font-semibold text-slate-800">{{ modalityLabel(s.seriesModality) }}</span>
                <span class="tabular-nums">{{ t('uploadPortal.split.frames', { n: s.frames }, s.frames) }}</span>
                <span class="tabular-nums text-slate-500">{{ s.width }}&times;{{ s.height }}</span>
                <span class="text-slate-500">{{ timeOf(s) }}</span>
              </div>
              <div v-if="visitText(s)" class="text-slate-500" :data-testid="`series-visit-${s.seriesKey}`">{{ visitText(s) }}</div>
            </div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — Retinal scan metrics viewer.
 *
 * Centerpiece of the retinal inference workflow. Three columns on
 * lg+ viewports (KPI strip / fundus overlay / per-B-scan trace), with
 * an ETDRS sub-totals table + a download list + a raw JSON payload
 * tree below.
 *
 * <p>Lifecycle:
 *   1. {@code onMounted} → {@code loadJob(jobId)} pulls the fat DTO.
 *   2. When the job has a {@code geometryUrl}, {@code loadGeometry()}
 *      pulls geometry.json (cached by e2eUuid).
 *   3. Empty states render for non-succeeded statuses + when the
 *      primary metric is missing.
 *
 * <p>Hover wiring: the per-B-scan chart emits a slice index on hover
 * which the view forwards as {@code hoveredBscanZ} to the overlay so
 * the matching B-scan polyline highlights on the fundus image.
 */
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'
import FundusOverlay from '@/components/FundusOverlay.vue'
import RetinalArtifactList from '@/components/RetinalArtifactList.vue'
import RetinalVisitComparison from '@/components/RetinalVisitComparison.vue'
import ArmBadge from '@/components/ArmBadge.vue'
import JsonTree from '@/components/JsonTree.vue'
import type { FundusOverlayTask } from '@/components/FundusOverlay.vue'
import { useStudyArm } from '@/composables/useStudyArm'

import { useRetinalJobStore } from '@/stores/retinalJob'
import type { FluidPayload, GaPayload, ThicknessPayload, RetinalJobDetail } from '@/api/retinal'
import { useJobStatusStream } from '@/composables/useJobStatusStream'

/**
 * nAMD Slice 5 — Cornerstone.js B-scan viewer is large
 * (~150 KB gzipped including the dicom-image-loader web-worker).
 * Lazy-load only when the operator opens a retinal view.
 */
const BscanViewer = defineAsyncComponent(() => import('@/components/BscanViewer.vue'))

const { t } = useI18n()
const route = useRoute()
const store = useRetinalJobStore()

const jobId = computed<number>(() => Number(route.params.jobId))

const job = computed<RetinalJobDetail | null>(() => store.jobs[jobId.value] ?? null)
const geometry = computed(() => {
  const uuid = job.value?.e2eUuid ?? null
  if (uuid == null) return null
  return store.geometries[uuid] ?? null
})
const isLoading = computed<boolean>(() => !!store.loading[jobId.value])
const loadError = computed<string | null>(() => store.errors[jobId.value] ?? null)

/**
 * 2026-06-22 round 9 follow-up — German status + task labels.
 * Falls through to the raw value when the SPA hasn't shipped a
 * translation for the token (e.g. a new sidecar emits a new
 * task name).
 *
 * Computeds rather than functions called from the template — calling
 * t() inside a function invoked per render can cause vue-i18n to
 * re-track locale dependencies on every reactive pass, which the
 * StatusPill upstream caught as "recursive updates" once a third
 * StatusPill landed in the heading.
 */
const statusLabelText = computed<string>(() => {
  const status = job.value?.status
  if (!status) return ''
  const key = `retinal.status.${status}`
  const translated = t(key)
  return translated === key ? status : translated
})
const taskLabelText = computed<string>(() => {
  const task = job.value?.task
  if (!task) return ''
  const key = `retinal.task.${task}`
  const translated = t(key)
  return translated === key ? String(task).toUpperCase() : translated
})

/**
 * 2026-06-22 — artifacts surfaced in the Downloads section. Drops
 * derived presentation PNGs (projection composite + per-biomarker
 * + per-slice seg overlays) so the list is just the canonical
 * scientific output: fluidseg.npz / fluid_labels.npy / etc. The
 * PNGs still serve via the artifact-URL endpoint; they just don't
 * clutter the manual-download list.
 */
const downloadableArtifacts = computed<string[]>(() => {
  const names = job.value?.artifactNames ?? []
  return names.filter((name) =>
    !name.startsWith('projection_fluid')
    && !name.startsWith('projection_ga')
    && !name.startsWith('projection_onl')
    && !name.startsWith('projection_pr')
    && !name.startsWith('seg_bscan_'),
  )
})

const hoveredBscanZ = ref<number | null>(null)

/** Always a defined integer for the BscanViewer's v-model — falls
 *  back to the middle slice when no hover has set hoveredBscanZ. */
const currentBscanZ = computed<number>(() => {
  if (hoveredBscanZ.value != null) return hoveredBscanZ.value
  const n = geometry.value?.bscan?.dim_z_bscans ?? 0
  return Math.max(0, Math.floor(n / 2))
})

/**
 * nAMD Slice 6 — honour-system arm gate. When the subject is in
 * Arm B (`AI_HIDDEN`) we hide the KPI strip, en-face overlay layer,
 * and visit-to-visit comparison panel. The B-scan navigator + bbox
 * + ETDRS rings stay visible — those don't expose AI output.
 */
const armGate = useStudyArm(() => job.value?.subjectArm ?? null)
function onHoverBscan(z: number | null) {
  hoveredBscanZ.value = z
}

/* -------- Lifecycle -------------------------------------------------- */

async function load() {
  try {
    await store.loadJob(jobId.value, true)
    if (job.value?.geometryUrl != null) {
      await store.loadGeometry(jobId.value)
    }
  } catch {
    // store records the error per-job; the template surfaces it.
  }
}

onMounted(() => {
  void load()
})

watch(jobId, () => {
  void load()
})

/* -------- Derived view-model ---------------------------------------- */

const isFluid = computed(() => job.value?.task === 'fluid')
const isGa = computed(() => job.value?.task === 'ga')
const isOnl = computed(() => job.value?.task === 'onl')
const isPr = computed(() => job.value?.task === 'pr')
const isThickness = computed(() => isOnl.value || isPr.value)

const fluidPayload = computed<FluidPayload | null>(() => {
  if (!isFluid.value || !job.value) return null
  return job.value.outputPayload as unknown as FluidPayload
})
const gaPayload = computed<GaPayload | null>(() => {
  if (!isGa.value || !job.value) return null
  return job.value.outputPayload as unknown as GaPayload
})
const thicknessPayload = computed<ThicknessPayload | null>(() => {
  if (!isThickness.value || !job.value) return null
  return job.value.outputPayload as unknown as ThicknessPayload
})

interface KpiTile {
  label: string
  value: string
  unit: string
  subtitle?: string
  tone: 'irf' | 'srf' | 'ped' | 'ga' | 'thickness' | 'neutral'
}

function formatNumber(n: number | undefined | null, precision = 3): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return Number(n).toPrecision(precision)
}

const kpiTiles = computed<KpiTile[]>(() => {
  if (fluidPayload.value) {
    const b = fluidPayload.value.biomarkers ?? {
      irf_mm3: 0,
      srf_mm3: 0,
      ped_mm3: 0,
      total_mm3: 0,
    }
    const totalSlices = totalBscanCount.value
    function subtitleFor(label: 'irf' | 'srf' | 'ped', value: number): string {
      if (value <= 0) return t('retinal.kpi.subtitle.notDetected')
      if (totalSlices > 0) {
        return t('retinal.kpi.subtitle.affected', {
          count: affectedBscanCount(label),
          total: totalSlices,
        })
      }
      return ''
    }
    return [
      {
        label: t('retinal.kpi.irf'),
        value: formatNumber(b.irf_mm3),
        unit: 'mm³',
        subtitle: subtitleFor('irf', b.irf_mm3 ?? 0),
        tone: 'irf',
      },
      {
        label: t('retinal.kpi.srf'),
        value: formatNumber(b.srf_mm3),
        unit: 'mm³',
        subtitle: subtitleFor('srf', b.srf_mm3 ?? 0),
        tone: 'srf',
      },
      {
        label: t('retinal.kpi.ped'),
        value: formatNumber(b.ped_mm3),
        unit: 'mm³',
        subtitle: subtitleFor('ped', b.ped_mm3 ?? 0),
        tone: 'ped',
      },
      {
        label: t('retinal.kpi.total'),
        value: formatNumber(b.total_mm3),
        unit: 'mm³',
        subtitle: t('retinal.kpi.subtitle.totalLoad'),
        tone: 'neutral',
      },
    ]
  }
  if (gaPayload.value) {
    return [
      {
        label: t('retinal.kpi.gaArea'),
        value: formatNumber(gaPayload.value.ga_area_mm2),
        unit: 'mm²',
        tone: 'ga',
      },
    ]
  }
  if (thicknessPayload.value) {
    const tp = thicknessPayload.value
    const validRatio =
      tp.total_ascans > 0
        ? t('retinal.kpi.validAscans', { valid: tp.valid_ascans, total: tp.total_ascans })
        : undefined
    return [
      {
        label: isOnl.value ? t('retinal.kpi.onlThickness') : t('retinal.kpi.prThickness'),
        value: formatNumber(tp.thickness_mean_um),
        unit: 'µm',
        subtitle: validRatio,
        tone: 'thickness',
      },
    ]
  }
  return []
})

/* -------- ETDRS sub-totals table ------------------------------------ */

interface EtdrsRow {
  label: string
  values: string[]
}

const etdrsHeaders = computed<string[]>(() => {
  if (fluidPayload.value) return [t('retinal.etdrs.colRing'), t('retinal.etdrs.colIrf'), t('retinal.etdrs.colSrf'), t('retinal.etdrs.colPed'), t('retinal.etdrs.colTotal')]
  if (gaPayload.value) return [t('retinal.etdrs.colRing'), t('retinal.etdrs.colGaArea')]
  return []
})

const etdrsRows = computed<EtdrsRow[]>(() => {
  if (fluidPayload.value) {
    const m = fluidPayload.value.etdrs_mm3
    if (m == null) return []
    return [
      [t('retinal.etdrs.ringLabel', { mm: 1 }), m.central_1mm],
      [t('retinal.etdrs.ringLabel', { mm: 3 }), m.central_3mm],
      [t('retinal.etdrs.ringLabel', { mm: 6 }), m.central_6mm],
    ].map(([label, ring]) => ({
      label: label as string,
      values: [
        formatNumber((ring as { irf?: number }).irf),
        formatNumber((ring as { srf?: number }).srf),
        formatNumber((ring as { ped?: number }).ped),
        formatNumber((ring as { total?: number }).total),
      ],
    }))
  }
  if (gaPayload.value) {
    const m = gaPayload.value.etdrs_mm2
    if (m == null) return []
    return [
      { label: t('retinal.etdrs.ringLabel', { mm: 1 }), values: [formatNumber(m.central_1mm)] },
      { label: t('retinal.etdrs.ringLabel', { mm: 3 }), values: [formatNumber(m.central_3mm)] },
      { label: t('retinal.etdrs.ringLabel', { mm: 6 }), values: [formatNumber(m.central_6mm)] },
    ]
  }
  return []
})

/* -------- FundusOverlay task discriminator -------------------------- */

const overlayTask = computed<FundusOverlayTask>(() => {
  const t = job.value?.task
  if (t === 'fluid' || t === 'onl' || t === 'pr' || t === 'ga') return t
  return 'fluid'
})

/* -------- Header laterality long-form -------------------------------- */
/**
 * "OS · linkes Auge" / "OD · rechtes Auge". Falls back to the raw
 * laterality token when the locale doesn't carry a long form for it.
 */
const lateralityLongText = computed<string>(() => {
  const lat = job.value?.laterality
  if (!lat) return ''
  const key = `retinal.header.lateralityLong.${lat}`
  const translated = t(key)
  return translated === key ? String(lat) : translated
})

/* -------- KPI tile subtitles (design-spec hints) -------------------- */
/**
 * 2026-06-22 — count of B-scans where at least one biomarker voxel
 * exists, by label. Drives the PED card's "{count} von {total} B-Scans
 * betroffen" subtitle. Reads from the fluid payload's
 * `per_bscan_mm2[label][]` series (already populated by the cluster
 * runner). Returns 0 when the series is missing — silent degrade
 * since the subtitle is informational only.
 */
function affectedBscanCount(label: 'irf' | 'srf' | 'ped'): number {
  const series = (fluidPayload.value?.per_bscan_mm2 ?? {})[label]
  if (!Array.isArray(series)) return 0
  return series.reduce<number>((n, v) => (Number(v) > 1e-9 ? n + 1 : n), 0)
}

const totalBscanCount = computed<number>(
  () => geometry.value?.bscan?.dim_z_bscans ?? 0,
)

/**
 * 2026-06-22 — biomarker-tone → Tailwind classes for the inline
 * metric-card variant. Kept inline (not via RetinalKpiTile) so the
 * design's mockup styling (rounded-2xl, absolute left bar, larger
 * value font) lives in one place — and so the existing
 * RetinalKpiTile component, with subtly different styling, can stay
 * untouched for other call sites + the histoire story.
 */
function metricBarClass(tone: KpiTile['tone']): string {
  switch (tone) {
    case 'irf': return 'bg-cyan-400'
    case 'srf': return 'bg-amber-400'
    case 'ped': return 'bg-fuchsia-500'
    case 'ga': return 'bg-pink-500'
    case 'thickness': return 'bg-sky-500'
    case 'neutral':
    default: return 'bg-muw-blue'
  }
}
function metricLabelClass(tone: KpiTile['tone']): string {
  switch (tone) {
    case 'irf': return 'text-cyan-700'
    case 'srf': return 'text-amber-700'
    case 'ped': return 'text-fuchsia-700'
    case 'ga': return 'text-pink-700'
    case 'thickness': return 'text-sky-700'
    case 'neutral':
    default: return 'text-slate-500'
  }
}

/* -------- Display formatting helpers -------------------------------- */

function formatIsoDate(iso: string | null): string {
  if (!iso) return '—'
  // Show date + HH:MM in UTC. Operators ask for the run time the same
  // way they'd inspect a sidecar log, so we don't localise here.
  return iso.slice(0, 16).replace('T', ' ')
}

function emptyStateMessage(status: string | null | undefined): string | null {
  if (!status) return null
  if (status === 'queued') return t('retinal.empty.awaitingSidecar')
  if (status === 'preprocessing') return t('retinal.empty.preprocessing')
  if (status === 'segmenting') return t('retinal.empty.segmenting')
  if (status === 'failed') return t('retinal.empty.failed')
  return null
}

const inflightMessage = computed(() => emptyStateMessage(job.value?.status))

/* ------------------------------------------------------------- */
/* 2026-06-19 retry — operator re-dispatch of a failed job.      */
/*                                                               */
/* Posts to /retinal-jobs/{id}/retry; the store optimistically   */
/* patches status → 'remote_pending' so the SSE stream reopens   */
/* and the live indicator pops back on. Any failure stays in the */
/* loadError ref so the regular error banner surfaces it; we     */
/* don't toast here because the view's own state already         */
/* communicates the new status.                                  */
/* ------------------------------------------------------------- */
const retrying = computed<boolean>(() => !!store.retryInflight[jobId.value])
async function onRetry(): Promise<void> {
  try {
    await store.retryJob(jobId.value)
  } catch (e) {
    const message = e instanceof Error ? e.message : t('retinal.retry.error')
    store.errors[jobId.value] = message
  }
}

const showJson = ref(false)

/* ------------------------------------------------------------- */
/* Wave 2A — Real-time job-status SSE subscriber.                */
/*                                                               */
/* While the job is in an in-flight state we subscribe to the    */
/* SSE stream and re-fetch the DTO when the backend pushes a     */
/* terminal `done`. Heartbeats keep the connection up without    */
/* triggering a re-fetch — useJobStatusStream surfaces those     */
/* via the connected flag, not the status ref.                   */
/*                                                               */
/* The composable closes the stream on any of:                   */
/*   - jobId watch returning null;                               */
/*   - the enabled flag flipping false (terminal state).         */
/*   - component unmount.                                        */
/* ------------------------------------------------------------- */
const IN_FLIGHT_STATUSES = new Set([
  'remote_pending',
  'queued',
  'screening',
  'screened',
  'preprocessing',
  'segmenting',
])

const streamEnabled = computed<boolean>(() => {
  const status = job.value?.status
  return status != null && IN_FLIGHT_STATUSES.has(status)
})

const streamJobId = computed<number | null>(() =>
  streamEnabled.value ? jobId.value : null,
)

const { connected: liveConnected } = useJobStatusStream(streamJobId, {
  enabled: streamEnabled,
  onStatus: (e) => {
    // The store's DTO carries the authoritative payload (e.g.
    // metrics, artifacts). The SSE event just tells us "something
    // changed" — re-fetch when the status hits a terminal state.
    if (e.status === 'done' || e.status === 'failed') {
      void store.loadJob(jobId.value, true)
    }
  },
})
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('retinal.nav.home') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 min-w-0 px-8 py-7">
      <div class="max-w-[1200px] mx-auto">
        <p v-if="isLoading && !job" class="text-slate-500 italic" data-testid="retinal-view-loading">
          {{ t('retinal.loading') }}
        </p>

        <div
          v-else-if="loadError && !job"
          class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800"
          data-testid="retinal-view-error"
        >
          {{ t('retinal.failedToLoad', { error: loadError }) }}
        </div>

        <template v-else-if="job">
          <!-- ════════ Page header ════════ -->
          <div class="flex items-start justify-between gap-6 mb-5">
            <div class="min-w-0">
              <div class="text-[12.5px] text-slate-500 mb-1.5">
                {{ t('retinal.header.eventCrfLabel', { id: job.eventCrfId, jobId: job.jobId }) }}
              </div>
              <h1
                class="font-serif text-[26px] font-semibold tracking-tight text-slate-900 leading-none mb-2.5"
                data-testid="retinal-view-heading"
              >
                {{ t('retinal.header.title') }}
              </h1>
              <div class="flex items-center gap-2 flex-wrap">
                <StatusPill variant="info">{{ lateralityLongText || String(job.laterality) }}</StatusPill>
                <StatusPill v-if="job.task" variant="neutral">{{ taskLabelText }}</StatusPill>
                <StatusPill
                  v-if="job.status === 'succeeded'"
                  variant="success"
                >{{ statusLabelText }}</StatusPill>
                <StatusPill
                  v-else-if="job.status === 'failed'"
                  variant="danger"
                >{{ statusLabelText }}</StatusPill>
                <StatusPill v-else variant="warning">{{ statusLabelText }}</StatusPill>
                <!-- Wave 2A — Live indicator. Visible while the SSE
                     stream is connected (job in flight + handshake
                     done). The pulse is cosmetic; the i18n key is the
                     SR-readable label. -->
                <span
                  v-if="liveConnected"
                  class="inline-flex items-center gap-1.5 text-xs text-emerald-700"
                  data-testid="retinal-view-live-indicator"
                  :title="t('retinal.live.indicator')"
                >
                  <span aria-hidden="true" class="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                  {{ t('retinal.live.indicator') }}
                </span>
              </div>
              <div class="flex items-center gap-x-5 gap-y-1 flex-wrap mt-3 text-[12.5px] text-slate-500">
                <span>{{ t('retinal.header.modelLabel') }}
                  <span class="font-mono text-slate-700">{{ job.modelVersion ?? '—' }}</span>
                </span>
                <span class="text-slate-300">·</span>
                <span>{{ t('retinal.header.runLabel') }}
                  <span class="font-mono text-slate-700">{{ formatIsoDate(job.completedAt ?? job.enqueuedAt) }}</span>
                </span>
                <span class="text-slate-300">·</span>
                <span class="inline-flex items-center gap-2">{{ t('retinal.header.confidenceLabel') }}
                  <span class="inline-flex items-center gap-1.5">
                    <span class="w-20 h-1.5 rounded-full bg-slate-200 overflow-hidden inline-block align-middle">
                      <span
                        class="block h-full rounded-full bg-muw-blue"
                        :style="{ width: `${Math.round(Math.max(0, Math.min(1, job.confidence ?? 0)) * 100)}%` }"
                      />
                    </span>
                    <span class="font-semibold text-slate-700 tabular-nums">
                      {{ job.confidence != null ? job.confidence.toFixed(2) : '—' }}
                    </span>
                  </span>
                </span>
              </div>
            </div>
            <div class="flex items-center gap-2.5 shrink-0">
              <button
                type="button"
                class="px-3.5 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                :disabled="retrying"
                data-testid="retinal-view-retry"
                @click="onRetry"
              >
                <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M3 2v6h6M21 12a9 9 0 1 1-3-6.7L21 8" />
                </svg>
                {{ retrying ? t('retinal.retry.inflight') : t('retinal.retry.cta') }}
              </button>
              <a
                href="#retinal-downloads"
                class="px-3.5 py-2 text-[13px] font-semibold bg-muw-blue text-white rounded-lg hover:bg-muw-blue-700 inline-flex items-center gap-2 shadow-[0_1px_2px_rgba(17,29,78,.18)]"
                data-testid="retinal-view-download-all"
              >
                <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
                </svg>
                {{ t('retinal.header.downloadAll') }}
              </a>
            </div>
          </div>

          <!-- Empty / in-flight banner -->
          <div
            v-if="inflightMessage"
            class="mb-5 rounded-2xl border px-4 py-3 text-xs"
            :class="job.status === 'failed'
              ? 'border-rose-200 bg-rose-50 text-rose-900'
              : 'border-amber-200 bg-amber-50 text-amber-900'"
            data-testid="retinal-view-inflight"
          >
            {{ inflightMessage }}
          </div>
          <div
            v-else-if="!job.primaryMetric"
            class="mb-5 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900"
            data-testid="retinal-view-no-metric"
          >
            {{ t('retinal.empty.noMetric') }}
          </div>

          <!-- Slice 6 — honour-system arm badge -->
          <div v-if="armGate.showBadge.value" class="mb-4">
            <ArmBadge :arm="job.subjectArm" />
            <p class="mt-1.5 text-xs text-slate-500">
              {{ t('retinal.arm.blindedExplain') }}
            </p>
          </div>

          <!-- Slice 4 — visit-to-visit comparison panel -->
          <RetinalVisitComparison
            v-if="job.task === 'fluid' && job.status === 'done' && !armGate.hideAi.value"
            :job-id="job.jobId"
          />

          <!-- ════════ Metric cards — 4-card horizontal strip ════════ -->
          <div
            v-if="!armGate.hideAi.value && kpiTiles.length"
            class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-5"
            data-testid="retinal-view-kpis"
          >
            <div
              v-for="tile in kpiTiles"
              :key="tile.label"
              class="relative rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] pl-5 pr-4 py-3.5 overflow-hidden"
              data-testid="retinal-kpi-tile"
            >
              <span class="absolute left-0 top-0 bottom-0 w-1.5" :class="metricBarClass(tile.tone)" />
              <div class="text-[11px] font-semibold uppercase tracking-[0.08em]" :class="metricLabelClass(tile.tone)">
                {{ tile.label }}
              </div>
              <div class="flex items-baseline gap-1 mt-1">
                <span class="text-[24px] font-semibold text-slate-900 tabular-nums leading-none">{{ tile.value }}</span>
                <span class="text-[12px] text-slate-400">{{ tile.unit }}</span>
              </div>
              <div v-if="tile.subtitle" class="text-[11px] text-slate-400 mt-1.5">{{ tile.subtitle }}</div>
            </div>
          </div>
          <div
            v-else-if="!armGate.hideAi.value"
            class="mb-5 bg-slate-50 border border-dashed border-slate-300 rounded-2xl px-4 py-3 text-xs text-slate-500"
            data-testid="retinal-view-kpis"
          >
            {{ t('retinal.empty.noKpi') }}
          </div>

          <!-- ════════ Viewer row: en-face (5) + B-scan (7) ════════ -->
          <div class="grid grid-cols-1 lg:grid-cols-12 gap-5 mb-5 items-stretch">
            <!-- En-face fundus locator -->
            <section
              class="lg:col-span-5 rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] flex flex-col"
              data-testid="retinal-view-fundus"
            >
              <div class="flex items-center justify-between px-5 pt-4 pb-3 border-b border-slate-100">
                <h3 class="text-[12px] font-semibold uppercase tracking-[0.1em] text-muw-blue">
                  {{ t('retinal.fundus.header') }}
                </h3>
                <span class="text-[11px] text-slate-400">{{ t('retinal.fundus.bscanPosition') }}</span>
              </div>
              <div class="p-4 flex-1 flex items-center">
                <div
                  v-if="!job.fundusUrl || !geometry"
                  class="aspect-square w-full bg-slate-100 border border-dashed border-slate-300 rounded-xl flex items-center justify-center text-xs text-slate-500"
                >
                  {{ t('retinal.empty.fundusNotAvailable') }}
                </div>
                <div v-else class="w-full">
                  <FundusOverlay
                    :fundus-url="job.fundusUrl"
                    :geometry="geometry"
                    :payload="job.outputPayload"
                    :task="overlayTask"
                    :laterality="job.laterality"
                    :hovered-bscan-z="currentBscanZ"
                    :job-id="job.jobId"
                    :artifact-names="armGate.hideAi.value ? [] : job.artifactNames"
                    @hover-bscan="onHoverBscan"
                  />
                </div>
              </div>
            </section>

            <!-- B-Scan navigator -->
            <div class="lg:col-span-7 flex flex-col">
              <BscanViewer
                v-if="job.bscanDcmUrl && (geometry?.bscan?.dim_z_bscans ?? 0) > 0"
                :bscan-dcm-url="job.bscanDcmUrl"
                :n-bscans="geometry!.bscan!.dim_z_bscans"
                :model-value="currentBscanZ"
                :job-id="job.jobId"
                class="flex-1"
                @update:model-value="(z) => hoveredBscanZ = z"
              />
              <div
                v-else
                class="flex-1 rounded-2xl border border-slate-200 bg-slate-50 flex items-center justify-center text-xs text-slate-500 italic min-h-[300px]"
              >
                {{ t('retinal.empty.fundusNotAvailable') }}
              </div>
            </div>
          </div>

          <!-- ════════ ETDRS + Downloads row ════════ -->
          <div class="grid grid-cols-1 lg:grid-cols-12 gap-5 mb-5">
            <section
              v-if="etdrsRows.length"
              class="lg:col-span-7 rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] overflow-clip"
              data-testid="retinal-view-etdrs"
            >
              <div class="px-5 pt-4 pb-3 border-b border-slate-100">
                <h3 class="text-[12px] font-semibold uppercase tracking-[0.1em] text-muw-blue">
                  {{ t('retinal.etdrs.header') }}
                </h3>
              </div>
              <DenseTable :bordered="false">
                <template #header>
                  <tr class="text-left text-slate-400 text-[11px] uppercase tracking-[0.06em]">
                    <th
                      v-for="(h, idx) in etdrsHeaders"
                      :key="h"
                      scope="col"
                      :class="['px-5 py-2.5 font-semibold', idx > 0 ? 'text-right' : '']"
                    >
                      {{ h }}
                    </th>
                  </tr>
                </template>
                <tr v-for="row in etdrsRows" :key="row.label" data-testid="retinal-etdrs-row" class="border-t border-slate-100">
                  <td class="px-5 py-3 font-medium text-slate-700 text-[13px]">{{ row.label }}</td>
                  <td
                    v-for="(value, idx) in row.values"
                    :key="`${row.label}-${idx}`"
                    class="px-5 py-3 text-[12.5px] tabular-nums font-mono text-right text-slate-700"
                  >
                    {{ value }}
                  </td>
                </tr>
              </DenseTable>
            </section>

            <RetinalArtifactList
              id="retinal-downloads"
              class="lg:col-span-5"
              :class="etdrsRows.length ? '' : 'lg:col-span-12'"
              :job-id="job.jobId"
              :artifact-names="downloadableArtifacts"
              :companion-names="job.companionNames"
            />
          </div>

          <!-- Raw output payload (collapsible) -->
          <section
            class="rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] overflow-clip mb-6"
            data-testid="retinal-view-json"
          >
            <button
              type="button"
              class="w-full px-5 py-3.5 flex items-center justify-between text-[12px] font-semibold uppercase tracking-[0.1em] text-muw-blue hover:bg-slate-50"
              :aria-expanded="showJson"
              @click="showJson = !showJson"
            >
              <span>{{ t('retinal.jsonTree.rawPayloadHeader') }}</span>
              <span class="text-slate-400">{{ showJson ? '▾' : '▸' }}</span>
            </button>
            <div v-if="showJson" class="p-5 bg-slate-50 overflow-auto max-h-[40rem] border-t border-slate-100">
              <JsonTree :value="job.outputPayload" />
            </div>
          </section>

          <RouterLink
            v-if="job.eventCrfId"
            to="/subjects"
            class="inline-flex items-center gap-2 text-[13px] font-medium text-muw-blue hover:text-muw-blue-700"
          >
            <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M19 12H5M12 19l-7-7 7-7" />
            </svg>
            {{ t('retinal.nav.backToSubjects') }}
          </RouterLink>
        </template>
      </div>
    </main>
  </div>
</template>

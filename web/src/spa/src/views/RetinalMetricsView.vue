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
import RetinalKpiTile from '@/components/RetinalKpiTile.vue'
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
 * Lazy-load the per-B-scan trace — Chart.js + vue-chartjs only ship
 * to operators who land on the metrics viewer (see
 * {@code PatientDetailModal} for the exact same pattern).
 */
const PerBscanTrace = defineAsyncComponent(() => import('@/components/PerBscanTrace.vue'))

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
    return [
      { label: t('retinal.kpi.irf'), value: formatNumber(b.irf_mm3), unit: 'mm³', tone: 'irf' },
      { label: t('retinal.kpi.srf'), value: formatNumber(b.srf_mm3), unit: 'mm³', tone: 'srf' },
      { label: t('retinal.kpi.ped'), value: formatNumber(b.ped_mm3), unit: 'mm³', tone: 'ped' },
      { label: t('retinal.kpi.total'), value: formatNumber(b.total_mm3), unit: 'mm³', tone: 'neutral' },
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

const showPerBscan = computed(() => isFluid.value || isGa.value)
const perBscanTask = computed<'fluid' | 'ga'>(() => (isGa.value ? 'ga' : 'fluid'))

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

    <main class="flex-1 max-w-6xl px-8 py-6">
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
        <!-- Header -->
        <div class="mb-5">
          <div class="text-xs text-slate-500 mb-1">
            {{ t('retinal.header.eventCrfLabel', { id: job.eventCrfId, jobId: job.jobId }) }}
          </div>
          <h1 class="text-xl font-semibold tracking-tight flex items-center gap-3 flex-wrap" data-testid="retinal-view-heading">
            {{ t('retinal.header.title') }}
            <StatusPill variant="info">{{ String(job.laterality) }}</StatusPill>
            <StatusPill v-if="job.task" variant="neutral">{{ String(job.task).toUpperCase() }}</StatusPill>
            <StatusPill
              v-if="job.status === 'succeeded'"
              variant="success"
            >{{ job.status }}</StatusPill>
            <StatusPill
              v-else-if="job.status === 'failed'"
              variant="danger"
            >{{ job.status }}</StatusPill>
            <StatusPill v-else variant="warning">{{ job.status }}</StatusPill>
            <!-- Wave 2A — Live indicator. Visible while the SSE
                 stream is connected (i.e. the job is in flight + the
                 EventSource has at least handshaked). The animated
                 dot is purely cosmetic; the semantic for screen
                 readers is the i18n key value. -->
            <span
              v-if="liveConnected"
              class="inline-flex items-center gap-1.5 text-xs text-emerald-700"
              data-testid="retinal-view-live-indicator"
              :title="t('retinal.live.indicator')"
            >
              <span
                aria-hidden="true"
                class="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-pulse"
              />
              {{ t('retinal.live.indicator') }}
            </span>
          </h1>
          <div class="mt-2 text-xs text-slate-500 flex flex-wrap gap-x-4 gap-y-1">
            <span>{{ t('retinal.header.modelLabel') }} <span class="font-mono">{{ job.modelVersion ?? '—' }}</span></span>
            <span>{{ t('retinal.header.runLabel') }} <span class="font-mono">{{ formatIsoDate(job.completedAt ?? job.enqueuedAt) }}</span></span>
            <span>{{ t('retinal.header.confidenceLabel') }} <span class="font-mono">{{ job.confidence != null ? job.confidence.toFixed(2) : '—' }}</span></span>
          </div>
        </div>

        <!-- Empty / in-flight banner -->
        <div
          v-if="inflightMessage"
          class="mb-5 rounded-muw border px-4 py-3 text-xs flex items-center justify-between gap-3"
          :class="job.status === 'failed'
            ? 'border-rose-200 bg-rose-50 text-rose-900'
            : 'border-amber-200 bg-amber-50 text-amber-900'"
          data-testid="retinal-view-inflight"
        >
          <span>{{ inflightMessage }}</span>
          <button
            v-if="job.status === 'failed'"
            type="button"
            class="px-3 py-1.5 rounded-md bg-rose-600 hover:bg-rose-700 disabled:bg-rose-300 text-white text-xs font-medium whitespace-nowrap"
            :disabled="retrying"
            data-testid="retinal-view-retry"
            @click="onRetry"
          >
            {{ retrying ? t('retinal.retry.inflight') : t('retinal.retry.cta') }}
          </button>
        </div>
        <div
          v-else-if="!job.primaryMetric"
          class="mb-5 rounded-muw border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900"
          data-testid="retinal-view-no-metric"
        >
          {{ t('retinal.empty.noMetric') }}
        </div>

        <!-- Slice 6 — honour-system arm badge. Renders once at the
             top of the view when the subject is in Arm B so the
             operator knows AI is intentionally hidden (not failed). -->
        <div v-if="armGate.showBadge.value" class="mb-4">
          <ArmBadge :arm="job.subjectArm" />
          <p class="mt-1.5 text-xs text-slate-500">
            {{ t('retinal.arm.blindedExplain') }}
          </p>
        </div>

        <!-- Slice 4 — visit-to-visit comparison panel. Self-hides
             when there is no prior visit. Only meaningful for fluid
             (the only task with multi-metric deltas the panel
             knows how to render); other tasks fall through.
             Slice 6 — also hide for Arm B. -->
        <RetinalVisitComparison
          v-if="job.task === 'fluid' && job.status === 'done' && !armGate.hideAi.value"
          :job-id="job.jobId"
        />

        <!-- 3-column grid: KPIs / fundus / per-B-scan trace -->
        <div class="grid grid-cols-1 lg:grid-cols-4 gap-4 mb-5">
          <!-- KPI strip — hidden for Arm B by the arm gate -->
          <div
            v-if="!armGate.hideAi.value"
            class="lg:col-span-1 space-y-3"
            data-testid="retinal-view-kpis"
          >
            <RetinalKpiTile
              v-for="tile in kpiTiles"
              :key="tile.label"
              :label="tile.label"
              :value="tile.value"
              :unit="tile.unit"
              :subtitle="tile.subtitle"
              :tone="tile.tone"
            />
            <div
              v-if="kpiTiles.length === 0"
              class="bg-slate-50 border border-dashed border-slate-300 rounded-muw px-4 py-3 text-xs text-slate-500"
            >
              {{ t('retinal.empty.noKpi') }}
            </div>
          </div>

          <!-- Fundus overlay -->
          <div class="lg:col-span-2" data-testid="retinal-view-fundus">
            <div
              v-if="!job.fundusUrl || !geometry"
              class="aspect-square w-full bg-slate-100 border border-dashed border-slate-300 rounded-muw flex items-center justify-center text-xs text-slate-500"
            >
              {{ t('retinal.empty.fundusNotAvailable') }}
            </div>
            <div v-else class="aspect-square w-full">
              <!-- Slice 6 — when Arm B, drop projection artifacts from
                   the overlay so the en-face PED / IRF / SRF layers
                   don't render. FundusOverlay's per-biomarker /
                   composite gates already key off artifactNames, so
                   passing an empty list is the cleanest gate without
                   threading a new prop into the component. -->
              <FundusOverlay
                :fundus-url="job.fundusUrl"
                :geometry="geometry"
                :payload="job.outputPayload"
                :task="overlayTask"
                :laterality="job.laterality"
                :hovered-bscan-z="hoveredBscanZ"
                :job-id="job.jobId"
                :artifact-names="armGate.hideAi.value ? [] : job.artifactNames"
                @hover-bscan="onHoverBscan"
              />
            </div>
          </div>

          <!-- Per-B-scan trace -->
          <div class="lg:col-span-1" data-testid="retinal-view-trace">
            <PerBscanTrace
              v-if="showPerBscan"
              :payload="job.outputPayload"
              :task="perBscanTask"
              @hover-bscan="onHoverBscan"
            />
            <div
              v-else
              class="bg-white border border-slate-200 rounded-muw p-3 h-64 flex items-center justify-center text-xs text-slate-500 italic"
            >
              {{ t('retinal.empty.noPerBscan') }}
            </div>
          </div>
        </div>

        <!-- Slice 5 — B-scan navigator (Cornerstone.js). Shows when
             we have a bscan.dcm artifact + a known n_bscans from the
             geometry payload. Bidirectional hover sync with the
             FundusOverlay via the shared `hoveredBscanZ` ref. -->
        <BscanViewer
          v-if="job.bscanDcmUrl && (geometry?.bscan?.dim_z_bscans ?? 0) > 0"
          :bscan-dcm-url="job.bscanDcmUrl"
          :n-bscans="geometry!.bscan!.dim_z_bscans"
          :model-value="currentBscanZ"
          class="mb-5"
          @update:model-value="(z) => hoveredBscanZ = z"
        />

        <!-- ETDRS sub-totals -->
        <section
          v-if="etdrsRows.length"
          class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5"
          data-testid="retinal-view-etdrs"
        >
          <div class="px-5 py-3 border-b border-slate-200">
            <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
              {{ t('retinal.etdrs.header') }}
            </h2>
          </div>
          <DenseTable :bordered="false">
            <template #header>
              <tr class="border-b border-slate-200">
                <th
                  v-for="h in etdrsHeaders"
                  :key="h"
                  scope="col"
                  class="px-5 py-2 font-medium"
                >
                  {{ h }}
                </th>
              </tr>
            </template>
            <tr v-for="row in etdrsRows" :key="row.label" data-testid="retinal-etdrs-row">
              <td class="px-5 py-2.5 font-medium text-xs">{{ row.label }}</td>
              <td
                v-for="(value, idx) in row.values"
                :key="`${row.label}-${idx}`"
                class="px-5 py-2.5 text-xs tabular-nums font-mono"
              >
                {{ value }}
              </td>
            </tr>
          </DenseTable>
        </section>

        <!-- Downloads -->
        <RetinalArtifactList
          class="mb-5"
          :job-id="job.jobId"
          :artifact-names="job.artifactNames"
          :companion-names="job.companionNames"
        />

        <!-- Raw output payload (collapsible) -->
        <section
          class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5"
          data-testid="retinal-view-json"
        >
          <button
            type="button"
            class="w-full px-5 py-3 border-b border-slate-200 flex items-center justify-between text-xs font-semibold uppercase tracking-wider text-slate-500 hover:bg-slate-50"
            :aria-expanded="showJson"
            @click="showJson = !showJson"
          >
            <span>{{ t('retinal.jsonTree.rawPayloadHeader') }}</span>
            <span class="text-slate-400">{{ showJson ? '▾' : '▸' }}</span>
          </button>
          <div v-if="showJson" class="p-4 bg-slate-50 overflow-auto max-h-[40rem]">
            <JsonTree :value="job.outputPayload" />
          </div>
        </section>

        <RouterLink
          v-if="job.eventCrfId"
          to="/subjects"
          class="text-xs text-muw-blue underline"
        >
          {{ t('retinal.nav.backToSubjects') }}
        </RouterLink>
      </template>
    </main>
  </div>
</template>

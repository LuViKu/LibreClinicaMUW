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
import { RouterLink, useRoute } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'
import FundusOverlay from '@/components/FundusOverlay.vue'
import RetinalKpiTile from '@/components/RetinalKpiTile.vue'
import RetinalArtifactList from '@/components/RetinalArtifactList.vue'
import JsonTree from '@/components/JsonTree.vue'
import type { FundusOverlayTask } from '@/components/FundusOverlay.vue'

import { useRetinalJobStore } from '@/stores/retinalJob'
import type { FluidPayload, GaPayload, ThicknessPayload, RetinalJobDetail } from '@/api/retinal'

/**
 * Lazy-load the per-B-scan trace — Chart.js + vue-chartjs only ship
 * to operators who land on the metrics viewer (see
 * {@code PatientDetailModal} for the exact same pattern).
 */
const PerBscanTrace = defineAsyncComponent(() => import('@/components/PerBscanTrace.vue'))

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
      { label: 'IRF', value: formatNumber(b.irf_mm3), unit: 'mm³', tone: 'irf' },
      { label: 'SRF', value: formatNumber(b.srf_mm3), unit: 'mm³', tone: 'srf' },
      { label: 'PED', value: formatNumber(b.ped_mm3), unit: 'mm³', tone: 'ped' },
      { label: 'Total', value: formatNumber(b.total_mm3), unit: 'mm³', tone: 'neutral' },
    ]
  }
  if (gaPayload.value) {
    return [
      {
        label: 'GA area',
        value: formatNumber(gaPayload.value.ga_area_mm2),
        unit: 'mm²',
        tone: 'ga',
      },
    ]
  }
  if (thicknessPayload.value) {
    const t = thicknessPayload.value
    const validRatio =
      t.total_ascans > 0
        ? `${t.valid_ascans} / ${t.total_ascans} A-scans valid`
        : undefined
    return [
      {
        label: isOnl.value ? 'ONL thickness' : 'PR thickness',
        value: formatNumber(t.thickness_mean_um),
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
  if (fluidPayload.value) return ['Ring', 'IRF mm³', 'SRF mm³', 'PED mm³', 'Total mm³']
  if (gaPayload.value) return ['Ring', 'GA area mm²']
  return []
})

const etdrsRows = computed<EtdrsRow[]>(() => {
  if (fluidPayload.value) {
    const m = fluidPayload.value.etdrs_mm3
    if (m == null) return []
    return [
      ['1 mm', m.central_1mm],
      ['3 mm', m.central_3mm],
      ['6 mm', m.central_6mm],
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
      { label: '1 mm', values: [formatNumber(m.central_1mm)] },
      { label: '3 mm', values: [formatNumber(m.central_3mm)] },
      { label: '6 mm', values: [formatNumber(m.central_6mm)] },
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
  if (status === 'queued') return 'Awaiting sidecar…'
  if (status === 'preprocessing') return 'Preprocessing the OCT volume…'
  if (status === 'segmenting') return 'Running segmentation — metrics will appear when the sidecar completes.'
  if (status === 'failed') return 'Inference failed — segmentation artifacts (if any) are still available below.'
  return null
}

const inflightMessage = computed(() => emptyStateMessage(job.value?.status))

const showJson = ref(false)
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        Home
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-6xl px-8 py-6">
      <p v-if="isLoading && !job" class="text-slate-500 italic" data-testid="retinal-view-loading">
        Loading retinal scan metrics…
      </p>

      <div
        v-else-if="loadError && !job"
        class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800"
        data-testid="retinal-view-error"
      >
        Failed to load retinal job: {{ loadError }}
      </div>

      <template v-else-if="job">
        <!-- Header -->
        <div class="mb-5">
          <div class="text-xs text-slate-500 mb-1">
            Event-CRF #{{ job.eventCrfId }} · Retinal inference job #{{ job.jobId }}
          </div>
          <h1 class="text-xl font-semibold tracking-tight flex items-center gap-3 flex-wrap" data-testid="retinal-view-heading">
            Retinal scan metrics
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
          </h1>
          <div class="mt-2 text-xs text-slate-500 flex flex-wrap gap-x-4 gap-y-1">
            <span>Model: <span class="font-mono">{{ job.modelVersion ?? '—' }}</span></span>
            <span>Run: <span class="font-mono">{{ formatIsoDate(job.completedAt ?? job.enqueuedAt) }}</span></span>
            <span>Confidence: <span class="font-mono">{{ job.confidence != null ? job.confidence.toFixed(2) : '—' }}</span></span>
          </div>
        </div>

        <!-- Empty / in-flight banner -->
        <div
          v-if="inflightMessage"
          class="mb-5 rounded-muw border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900"
          data-testid="retinal-view-inflight"
        >
          {{ inflightMessage }}
        </div>
        <div
          v-else-if="!job.primaryMetric"
          class="mb-5 rounded-muw border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900"
          data-testid="retinal-view-no-metric"
        >
          Metrics couldn't be computed — segmentation artifacts are still available below.
        </div>

        <!-- 3-column grid: KPIs / fundus / per-B-scan trace -->
        <div class="grid grid-cols-1 lg:grid-cols-4 gap-4 mb-5">
          <!-- KPI strip -->
          <div class="lg:col-span-1 space-y-3" data-testid="retinal-view-kpis">
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
              No KPI yet.
            </div>
          </div>

          <!-- Fundus overlay -->
          <div class="lg:col-span-2" data-testid="retinal-view-fundus">
            <div
              v-if="!job.fundusUrl || !geometry"
              class="aspect-square w-full bg-slate-100 border border-dashed border-slate-300 rounded-muw flex items-center justify-center text-xs text-slate-500"
            >
              Fundus image not yet available.
            </div>
            <div v-else class="aspect-square w-full">
              <FundusOverlay
                :fundus-url="job.fundusUrl"
                :geometry="geometry"
                :payload="job.outputPayload"
                :task="overlayTask"
                :laterality="job.laterality"
                :hovered-bscan-z="hoveredBscanZ"
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
              No per-B-scan trace for this task.
            </div>
          </div>
        </div>

        <!-- ETDRS sub-totals -->
        <section
          v-if="etdrsRows.length"
          class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5"
          data-testid="retinal-view-etdrs"
        >
          <div class="px-5 py-3 border-b border-slate-200">
            <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
              ETDRS sub-totals
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
            <span>Raw output payload</span>
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
          ← Back to subjects
        </RouterLink>
      </template>
    </main>
  </div>
</template>

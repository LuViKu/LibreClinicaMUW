<script setup lang="ts">
/**
 * nAMD workspace — OCT Viewer tab: 5/7 fundus/B-scan split + quantification
 * strip (mirrors RetinalMetricsView).
 *
 * The ETDRS region breakdown: the runner reports cumulative central discs;
 * we derive the four disjoint regions (center / ring 1-3 / ring 3-6 /
 * corners) by subtraction so multi-select can sum without double-counting.
 *
 * Hover sync — FundusOverlay's per-B-scan indicator and NamdScanFrame's
 * slice are two-way bound via {@link slice}.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FundusOverlay, { type EtdrsRegion, type FundusOverlayTask } from '@/components/FundusOverlay.vue'
import RetinalVisitComparison from '@/components/RetinalVisitComparison.vue'
import { useRetinalJobStore } from '@/stores/retinalJob'
import { useAuthStore } from '@/stores/auth'
import { useErrorsStore } from '@/stores/errors'
import NamdScanFrame from '../components/NamdScanFrame.vue'
import Card from '../components/primitives/Card.vue'
import type { NamdWorkspaceData } from '../types'

interface Props {
  data: NamdWorkspaceData
}
const props = defineProps<Props>()
const { t } = useI18n()
const store = useRetinalJobStore()
const auth = useAuthStore()
const errors = useErrorsStore()

/**
 * 2026-06-26 — role gate for the layer-correction UI. Mirrors
 * RetinalResultsApiController.canCorrectSegmentation: only the
 * Investigator + Data-Manager + Administrator roles get the edit
 * tools; every other role sees a read-only fullscreen viewer.
 */
const canCorrectLayers = computed<boolean>(
  () => auth.hasRole('Investigator')
    || auth.hasRole('Data Manager')
    || auth.hasRole('Administrator'),
)

function onCorrectionSaved(_info: { layers: number; slices: number }): void {
  // 2026-06-27 — success toast is rendered INSIDE NamdScanFrame's
  // fullscreen masthead as a local emerald pill; routing through the
  // errors store would render it red via GlobalErrorToast. No-op here
  // (the parent gets the bubble so future side effects can hook in).
}
function onCorrectionError(message: string): void {
  errors.push(
    new Error(t('retinal.correction.saveFailed', { message })),
    'retinal.correction.saveFailed',
  )
}

const retinalJobId = computed<number | null>(() => props.data.current?.retinalJobId ?? null)

/** Load the job + geometry for the current visit. Re-runs on visit change. */
async function ensureLoaded(jobId: number | null): Promise<void> {
  if (jobId == null) return
  if (store.jobs[jobId] == null) await store.loadJob(jobId)
  if (store.geometries[store.jobs[jobId]?.e2eUuid ?? ''] == null) await store.loadGeometry(jobId)
}

onMounted(() => { void ensureLoaded(retinalJobId.value) })
watch(retinalJobId, (id) => { void ensureLoaded(id) })

const job = computed(() => (retinalJobId.value != null ? store.jobs[retinalJobId.value] ?? null : null))
const geometry = computed(() => {
  const uuid = job.value?.e2eUuid ?? null
  if (uuid == null) return null
  return store.geometries[uuid] ?? null
})

/** OCT slice scrolled in NamdScanFrame; synced into FundusOverlay's bscan locator. */
const nSlices = computed(() => geometry.value?.bscan?.dim_z_bscans ?? props.data.nSlices ?? 49)
const slice = ref(Math.floor(nSlices.value / 2))
const mask = ref(true)
watch(() => props.data.current?.id, () => { slice.value = Math.floor(nSlices.value / 2) })
watch(nSlices, (n) => { if (slice.value >= n) slice.value = Math.floor(n / 2) })

/** Task discriminator forwarded to FundusOverlay — nAMD is always fluid. */
const overlayTask: FundusOverlayTask = 'fluid'

/* ── ETDRS region selection + biomarker quantification ────────────── */

/**
 * Currently-selected ETDRS regions for biomarker quantification.
 * FundusOverlay emits clicks; the parent owns the array so the
 * selection survives slice scrubbing + fundus image remount.
 */
const selectedRegions = ref<EtdrsRegion[]>([])

interface FluidEtdrsRing {
  irf: number
  srf: number
  ped: number
  total: number
}

interface FluidPayloadShape {
  biomarkers: { irf_mm3: number; srf_mm3: number; ped_mm3: number; total_mm3: number }
  etdrs_mm3: { central_1mm: FluidEtdrsRing; central_3mm: FluidEtdrsRing; central_6mm: FluidEtdrsRing }
}

function isFluidPayload(p: unknown): p is FluidPayloadShape {
  if (!p || typeof p !== 'object') return false
  const e = (p as { etdrs_mm3?: unknown }).etdrs_mm3
  if (!e || typeof e !== 'object') return false
  const c1 = (e as { central_1mm?: unknown }).central_1mm
  return !!c1 && typeof c1 === 'object'
}

type RingContribution = { irf: number; srf: number; ped: number; total: number }

function ringDiff(a: FluidEtdrsRing | RingContribution | undefined, b: FluidEtdrsRing | RingContribution | undefined): RingContribution {
  const keys: (keyof RingContribution)[] = ['irf', 'srf', 'ped', 'total']
  const out: RingContribution = { irf: 0, srf: 0, ped: 0, total: 0 }
  for (const k of keys) {
    out[k] = Math.max(0, (a?.[k] ?? 0) - (b?.[k] ?? 0))
  }
  return out
}

/**
 * Biomarker contributions per DISJOINT ETDRS region. The runner
 * reports CUMULATIVE central discs (central_1mm ⊂ central_3mm ⊂
 * central_6mm); we subtract to get the rings + use the
 * full-volume {@code biomarkers} totals minus central_6mm for the
 * bbox-corner remainder. Multi-selection sums these without
 * double-counting.
 */
const regionBreakdown = computed<Record<EtdrsRegion, RingContribution> | null>(() => {
  const payload = job.value?.outputPayload
  if (!isFluidPayload(payload)) return null
  const e = payload.etdrs_mm3
  const fullVolume: RingContribution = {
    irf: payload.biomarkers.irf_mm3,
    srf: payload.biomarkers.srf_mm3,
    ped: payload.biomarkers.ped_mm3,
    total: payload.biomarkers.total_mm3,
  }
  return {
    center: ringDiff(e.central_1mm, undefined),
    ring_1_3: ringDiff(e.central_3mm, e.central_1mm),
    ring_3_6: ringDiff(e.central_6mm, e.central_3mm),
    corners: ringDiff(fullVolume, e.central_6mm),
  }
})

/** Sum of selected ETDRS regions' biomarker contributions. */
const selectedSum = computed<RingContribution | null>(() => {
  if (selectedRegions.value.length === 0) return null
  const bd = regionBreakdown.value
  if (!bd) return null
  const out: RingContribution = { irf: 0, srf: 0, ped: 0, total: 0 }
  for (const id of selectedRegions.value) {
    const r = bd[id]
    out.irf += r.irf
    out.srf += r.srf
    out.ped += r.ped
    out.total += r.total
  }
  return out
})

function regionLabel(id: EtdrsRegion): string {
  // Reuse the retinal scan view's region labels — they're already
  // translated under retinal.fundusOverlay.region.
  return t(`retinal.fundusOverlay.region.${id}`)
}

function formatNumber(v: number): string {
  if (!Number.isFinite(v)) return '—'
  if (Math.abs(v) < 0.001) return '0.000'
  return v.toFixed(3)
}
</script>

<template>
  <div data-testid="namd-viewer-tab" class="space-y-5">
    <!-- ── Row 1: fundus + B-scan side-by-side (4/8 split).
         2026-06-26 user-feedback round — narrower fundus column
         (was 5/12) so the B-scan gets meaningfully more horizontal
         space, matching the inference job viewer's proportions
         where the fundus is a thumbnail/locator and the B-scan is
         the primary working surface. ── -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-5 items-stretch">
      <!-- Fundus column — locator + click-quantify ETDRS regions -->
      <section
        class="lg:col-span-4 rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] flex flex-col"
        data-testid="namd-viewer-fundus"
      >
        <div class="flex items-center justify-between px-5 pt-4 pb-3 border-b border-slate-100">
          <h3 class="text-[12px] font-semibold uppercase tracking-[0.1em] text-muw-blue">
            {{ t('studyModules.namd.viewer2.fundusHeader') }}
          </h3>
          <span class="text-[11px] text-slate-400">{{ t('studyModules.namd.viewer2.fundusSub') }}</span>
        </div>
        <div class="p-4 flex-1 flex items-center">
          <div
            v-if="!job?.fundusUrl || !geometry"
            class="aspect-square w-full bg-slate-100 border border-dashed border-slate-300 rounded-xl flex items-center justify-center text-xs text-slate-500"
          >
            {{ t('studyModules.namd.viewer2.fundusEmpty') }}
          </div>
          <div v-else class="w-full">
            <FundusOverlay
              :fundus-url="job.fundusUrl"
              :geometry="geometry"
              :payload="job.outputPayload"
              :task="overlayTask"
              :laterality="props.data.patient.eye"
              :hovered-bscan-z="slice"
              :job-id="job.jobId"
              :artifact-names="job.artifactNames"
              :selected-regions="selectedRegions"
              @hover-bscan="(z: number | null) => { if (z != null) slice = z }"
              @update:selected-regions="(r: EtdrsRegion[]) => selectedRegions = r"
            />
          </div>
        </div>

        <!-- ETDRS selection summary — visible only when at least
             one region is clicked. The selection chips let the
             operator remove individual regions; the biomarker row
             shows IRF / SRF / PED / ∑ in mm³ for the union. -->
        <div
          v-if="selectedSum"
          data-testid="namd-viewer-etdrs-selection"
          class="px-5 py-3 border-t border-slate-100 bg-muw-sky-50/60 flex flex-col gap-2"
        >
          <div class="flex items-center gap-2 flex-wrap min-w-0">
            <span class="text-[11px] uppercase tracking-[0.08em] font-semibold text-muw-blue">
              {{ t('studyModules.namd.viewer2.selectionHeader') }}
            </span>
            <button
              v-for="r in selectedRegions"
              :key="`chip-${r}`"
              type="button"
              class="inline-flex items-center gap-1.5 rounded-full bg-white border border-muw-sky-200 text-muw-sky-700 text-[11px] font-semibold px-2.5 py-0.5 hover:bg-muw-sky-50"
              :data-testid="`namd-viewer-etdrs-chip-${r}`"
              @click="selectedRegions = selectedRegions.filter((x) => x !== r)"
            >
              {{ regionLabel(r) }}
              <span aria-hidden="true" class="text-slate-400">×</span>
            </button>
            <button
              type="button"
              class="text-[11px] text-slate-500 hover:text-muw-blue underline ml-1"
              data-testid="namd-viewer-etdrs-clear"
              @click="selectedRegions = []"
            >
              {{ t('studyModules.namd.viewer2.selectionClear') }}
            </button>
          </div>
          <div class="flex items-center gap-x-4 gap-y-1 flex-wrap text-[12.5px] font-mono tabular-nums text-slate-700">
            <span><span class="font-semibold text-cyan-700">IRF</span> {{ formatNumber(selectedSum.irf) }}</span>
            <span><span class="font-semibold text-amber-700">SRF</span> {{ formatNumber(selectedSum.srf) }}</span>
            <span><span class="font-semibold text-fuchsia-700">PED</span> {{ formatNumber(selectedSum.ped) }}</span>
            <span class="ml-auto"><span class="font-semibold text-slate-500">∑</span> {{ formatNumber(selectedSum.total) }} mm³</span>
          </div>
        </div>
        <div
          v-else
          class="px-5 py-3 border-t border-slate-100 text-[11px] text-slate-400"
        >
          {{ t('studyModules.namd.viewer2.selectionHint') }}
        </div>
      </section>

      <!-- B-scan column — the existing NamdScanFrame with sky-blue
           slider + en-face thumb hidden (the FundusOverlay
           replaces it). Fullscreen button still works. -->
      <section
        class="lg:col-span-8 rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] p-4 flex flex-col"
        data-testid="namd-viewer-bscan"
      >
        <NamdScanFrame
          v-if="props.data.current"
          :visit="props.data.current"
          :prev-visit="props.data.prev"
          :eye="props.data.patient.eye"
          :study-subject-id="props.data.patient.studySubjectId"
          :can-correct-layers="canCorrectLayers"
          :n-slices="nSlices"
          :slice="slice"
          :mask="mask"
          :show-thumbs="false"
          slider-tone="sky"
          id-base="viewer"
          @update:slice="(z: number) => (slice = z)"
          @update:mask="(m: boolean) => (mask = m)"
          @correction-saved="onCorrectionSaved"
          @correction-error="onCorrectionError"
        />
        <div
          v-else
          data-testid="namd-viewer-empty"
          class="rounded-md bg-slate-50 px-4 py-6 text-center text-sm text-slate-500"
        >
          {{ t('studyModules.namd.viewer.empty') }}
        </div>
      </section>
    </div>

    <!-- ── Row 2: biomarker quantification + delta vs previous visit ── -->
    <Card
      v-if="retinalJobId != null"
      :title="t('studyModules.namd.viewer2.deltaHeader')"
    >
      <RetinalVisitComparison :job-id="retinalJobId" />
    </Card>
  </div>
</template>

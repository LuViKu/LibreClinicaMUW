<script setup lang="ts">
/**
 * nAMD workspace — Compare tab.
 *
 * Port of {@code CompareTab} from namd-compare.jsx. Two
 * {@link NamdScanFrame}s side-by-side with:
 *   - A masthead card carrying the delta bar header on the left
 *     and a "Synchroner Scroll" toggle on the right.
 *   - A per-pane visit picker + activity pill + scan frame +
 *     three mini stats (IRF / SRF / PED).
 *   - A subtle "tip" line below explaining the sync toggle.
 *
 * <p>State: a single shared {@code slice} drives both panes when
 * {@code synced=true}; otherwise each pane writes to its own
 * {@code leftSolo} / {@code rightSolo}. The KI-Maske is always
 * shared across panes (per design — the operator wants both
 * frames to switch in lockstep) and the toggle is per-frame.
 *
 * <p>Defaults: A = previous visit, B = current. The previous
 * visit is the operator's most common reference for "what
 * changed today?".
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from '../components/primitives/Card.vue'
import NamdVisitPicker from '../components/NamdVisitPicker.vue'
import NamdCompareDeltaBar from '../components/NamdCompareDeltaBar.vue'
import NamdScanFrame from '../components/NamdScanFrame.vue'
import { useStudyArm } from '../composables/useStudyArm'
import { FLUID } from '../fluid'
import { I } from '../icons'
import type { NamdVisit, NamdSubjectArm } from '../types'

interface Props {
  data: { patient: { eye: 'OD' | 'OS' } } & {
    visits: NamdVisit[]
    current: NamdVisit | null
    prev: NamdVisit | null
    nSlices: number | null
    /** 2026-06-30 — cohort assignment; control arm hides the AI delta bar. */
    subjectArm: NamdSubjectArm
  }
}
const props = defineProps<Props>()
const { t } = useI18n()
const armRef = computed(() => props.data.subjectArm)
const { aiVisible } = useStudyArm(armRef)

const nSlices = computed(() => props.data.nSlices ?? 49)
const aId = ref<string | null>(props.data.prev?.id ?? null)
const bId = ref<string | null>(props.data.current?.id ?? null)

watch(
  () => props.data,
  (next) => {
    aId.value = next.prev?.id ?? aId.value
    bId.value = next.current?.id ?? bId.value
  },
)

const aVisit = computed(() => props.data.visits.find((v) => v.id === aId.value) ?? null)
const bVisit = computed(() => props.data.visits.find((v) => v.id === bId.value) ?? null)

/**
 * 2026-06-26 user-feedback round — colormap reference visit.
 *
 * <p>The activity-heatmap colormap means 'change since the
 * chronologically previous visit'. For each compare pane, that's
 * the visit immediately BEFORE the one being displayed in the
 * full timeline, NOT the other pane's selected visit. So when the
 * operator picks A=V01 and B=V03 the V01 pane shows uniform grey
 * (no predecessor exists), while the V03 pane shows change vs V02.
 *
 * <p>Returns null when the visit is the first in the timeline or
 * isn't in the visits array at all. NamdScanFrame's barColor
 * falls back to grey on null prev.
 */
function chronologicalPrev(visit: NamdVisit | null): NamdVisit | null {
  if (!visit) return null
  const idx = props.data.visits.findIndex((v) => v.id === visit.id)
  if (idx <= 0) return null
  return props.data.visits[idx - 1] ?? null
}

const aPrev = computed<NamdVisit | null>(() => chronologicalPrev(aVisit.value))
const bPrev = computed<NamdVisit | null>(() => chronologicalPrev(bVisit.value))

// Shared scroll state. When synced, both frames write through
// `slice`. When unsynced, each pane uses its own solo ref so the
// operator can park one frame at the fovea while scrolling the
// other through the volume.
const synced = ref(true)
const slice = ref(Math.floor(nSlices.value / 2))
const leftSolo = ref(slice.value)
const rightSolo = ref(slice.value)
const mask = ref(true)

watch(nSlices, (n) => {
  // The slice / solo refs are bounded by [0, n-1]; clamp on a
  // visit / job change to avoid an out-of-range counter at first
  // render.
  const center = Math.floor(n / 2)
  slice.value = Math.min(Math.max(0, slice.value), n - 1)
  leftSolo.value = Math.min(Math.max(0, leftSolo.value), n - 1)
  rightSolo.value = Math.min(Math.max(0, rightSolo.value), n - 1)
  if (slice.value === 0) slice.value = center
})

function leftSetSlice(z: number): void {
  if (synced.value) slice.value = z
  else leftSolo.value = z
}

function rightSetSlice(z: number): void {
  if (synced.value) slice.value = z
  else rightSolo.value = z
}

const leftSlice = computed(() => (synced.value ? slice.value : leftSolo.value))
const rightSlice = computed(() => (synced.value ? slice.value : rightSolo.value))

/* ── 2026-06-26 user-feedback round — stacked compare fullscreen ── */

/**
 * Tab-level fullscreen — both panes render stacked vertically over
 * a dark backdrop (top = reference / V_A, bottom = current / V_B)
 * so the operator sees BOTH visits at the same time + can sync-
 * scroll through them. The per-frame fullscreen button is disabled
 * here because the stacked layout is what the operator wants —
 * triggering a single-frame fullscreen would defeat the whole
 * point. Esc + the close button + the maximize/minimize toggle on
 * the masthead all dismiss.
 */
const fsOpen = ref(false)

function openFullscreen(): void {
  fsOpen.value = true
}

function closeFullscreen(): void {
  fsOpen.value = false
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' && fsOpen.value) closeFullscreen()
}

let fsPrevOverflow = ''
watch(fsOpen, (open) => {
  if (typeof document === 'undefined') return
  if (open) {
    document.addEventListener('keydown', onKey)
    fsPrevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  } else {
    document.removeEventListener('keydown', onKey)
    document.body.style.overflow = fsPrevOverflow
    fsPrevOverflow = ''
  }
})

onBeforeUnmount(() => {
  if (fsOpen.value && typeof document !== 'undefined') {
    document.removeEventListener('keydown', onKey)
    document.body.style.overflow = fsPrevOverflow
  }
})

function toggleSynced(): void {
  synced.value = !synced.value
}

/**
 * 2026-06-29 — ETDRS-rings toggle on the compare-tab masthead. Drives
 * both stacked panes via NamdScanFrame's {@code etdrsRings} prop so
 * they toggle together. Default off; persisted in localStorage under
 * the same key the per-frame fullscreen + correction fullscreen use.
 */
const etdrsRings = ref<boolean>(
  typeof localStorage !== 'undefined'
    && localStorage.getItem('retinal.correction.etdrsRings') === '1',
)
function toggleEtdrsRings(): void {
  etdrsRings.value = !etdrsRings.value
  try {
    localStorage.setItem('retinal.correction.etdrsRings', etdrsRings.value ? '1' : '0')
  } catch {
    /* sandboxed / private mode */
  }
}
</script>

<template>
  <div
    data-testid="namd-compare-tab"
    :class="
      fsOpen
        ? 'fixed inset-0 z-50 bg-black/95 backdrop-blur-sm px-5 py-4 flex flex-col gap-3 overflow-hidden select-none'
        : 'space-y-5'
    "
  >
    <!-- Fullscreen masthead — eyebrow ("VERGLEICH · OD · Woche 16 → Heute"),
         synced-scroll toggle in dark style, KI-Maske toggle mirror, close.
         Mirrors the design's namd-fs-compare screenshot. -->
    <header
      v-if="fsOpen"
      data-testid="namd-compare-fs-header"
      class="flex items-center justify-between gap-4 shrink-0 text-white"
    >
      <div class="flex items-center gap-3 min-w-0">
        <span class="text-[12px] font-semibold uppercase tracking-[0.12em] whitespace-nowrap">
          {{ t('studyModules.namd.compare.fsEyebrow') }}
        </span>
        <span class="text-white/45 text-[12px] truncate">
          {{ props.data.patient.eye }} ·
          {{ aVisit?.label ?? '—' }} → {{ bVisit?.label ?? '—' }}
        </span>
      </div>
      <div class="flex items-center gap-2.5 shrink-0">
        <label
          class="inline-flex items-center gap-2 text-[12px] cursor-pointer select-none"
          data-testid="namd-compare-fs-synced"
        >
          <span
            class="relative w-9 h-5 rounded-full transition inline-block"
            :class="synced ? 'bg-muw-sky' : 'bg-white/20'"
            @click="toggleSynced"
          >
            <span
              class="absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all"
              :class="synced ? 'left-4' : 'left-0.5'"
            />
          </span>
          <span>{{ t('studyModules.namd.compare.syncedScroll') }}</span>
        </label>
        <!-- 2026-06-29 — ETDRS-rings toggle, controls both stacked panes. -->
        <button
          type="button"
          data-testid="namd-compare-fs-etdrs"
          :title="t('retinal.correction.etdrsToggle')"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition"
          :class="etdrsRings ? 'bg-amber-400 text-slate-900' : 'bg-white/10 text-white/85 hover:bg-white/20'"
          @click="toggleEtdrsRings"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <circle cx="12" cy="12" r="3" />
            <circle cx="12" cy="12" r="7" />
            <circle cx="12" cy="12" r="11" />
          </svg>
          ETDRS
        </button>
        <button
          type="button"
          data-testid="namd-compare-fs-mask"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition"
          :class="mask ? 'bg-white text-muw-blue' : 'bg-white/10 text-white/85 hover:bg-white/20'"
          @click="mask = !mask"
        >
          <span class="w-2 h-2 rounded-full" :class="mask ? 'bg-muw-teal' : 'bg-white/50'" />
          {{ mask ? t('studyModules.namd.scanFrame.maskOn') : t('studyModules.namd.scanFrame.maskOff') }}
        </button>
        <button
          type="button"
          data-testid="namd-compare-fs-close"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold bg-white/10 text-white hover:bg-white/20 transition"
          @click="closeFullscreen"
        >
          <span class="inline-block w-3.5 h-3.5" v-html="I.close" />
          {{ t('studyModules.namd.scanFrame.fsClose') }}
        </button>
      </div>
    </header>

    <!-- ── Fullscreen stacked layout: A on top + B below.
         2026-06-26 user-feedback round — maximise B-scan real estate:
         drop the per-pane card chrome (background/padding/rounded),
         drop the inter-pane <hr>, and inline the header row directly
         above the scan box (no gap). The two scans get the full
         vertical budget minus the masthead + two thin info rows +
         two compact slider strips. ── -->
    <div
      v-if="fsOpen"
      class="flex-1 min-h-0 flex flex-col gap-2"
      data-testid="namd-compare-fs-stack"
    >
      <section
        v-for="side in [
          { key: 'A', tag: t('studyModules.namd.compare.tagReference'), visit: aVisit, prev: aPrev, slice: leftSlice, setSlice: leftSetSlice, idBase: 'cmp-fs-a' },
          { key: 'B', tag: t('studyModules.namd.compare.tagCurrent'),   visit: bVisit, prev: bPrev, slice: rightSlice, setSlice: rightSetSlice, idBase: 'cmp-fs-b' },
        ]"
        :key="side.key"
        :data-testid="`namd-compare-fs-pane-${side.key}`"
        class="flex-1 min-h-0 flex flex-col"
      >
        <div class="flex items-center gap-3 text-white/75 text-[11px] shrink-0 pb-1">
          <span class="font-semibold uppercase tracking-[0.1em] text-white/55">{{ side.tag }}</span>
          <span v-if="side.visit" class="font-semibold text-white">{{ side.visit.label }}</span>
          <span v-if="side.visit" class="text-white/50">{{ side.visit.date || '—' }}</span>
          <span v-if="side.visit" class="ml-auto tabular-nums text-white/50">
            IRF {{ side.visit.irf }} · SRF {{ side.visit.srf }} · PED {{ side.visit.ped }} nL
          </span>
        </div>
        <NamdScanFrame
          v-if="side.visit"
          :visit="side.visit"
          :prev-visit="side.prev"
          :eye="props.data.patient.eye"
          :n-slices="nSlices"
          :slice="side.slice"
          :mask="mask"
          :etdrs-rings="etdrsRings"
          :show-thumbs="false"
          :enable-fullscreen="false"
          :fill-container="true"
          slider-tone="sky"
          :id-base="side.idBase"
          class="flex-1 min-h-0"
          @update:slice="(z: number) => side.setSlice(z)"
          @update:mask="(m: boolean) => (mask = m)"
        />
        <div
          v-else
          class="flex-1 rounded-md bg-white/5 flex items-center justify-center text-sm text-white/45"
        >
          {{ t('studyModules.namd.viewer.empty') }}
        </div>
      </section>
    </div>

    <!-- ── Inline (non-fullscreen) layout below ── -->
    <!-- AI delta bar — study-arm only. Control-arm comparison shows
         the OCT panes without the AI-derived fluid delta. -->
    <Card v-if="!fsOpen && aiVisible" :title="t('studyModules.namd.compare.delta')">
      <template #right>
        <div class="inline-flex items-center gap-3">
          <label
            class="inline-flex items-center gap-2 text-[12px] text-slate-600 cursor-pointer select-none"
            data-testid="namd-compare-synced-toggle"
          >
            <span
              class="relative w-9 h-5 rounded-full transition inline-block"
              :class="synced ? 'bg-muw-blue' : 'bg-slate-300'"
              @click="toggleSynced"
            >
              <span
                class="absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all"
                :class="synced ? 'left-4' : 'left-0.5'"
              />
            </span>
            {{ t('studyModules.namd.compare.syncedScroll') }}
          </label>
          <button
            type="button"
            data-testid="namd-compare-fs-toggle"
            :aria-label="t('studyModules.namd.compare.fsMaximize')"
            :title="t('studyModules.namd.compare.fsMaximize')"
            class="inline-flex items-center justify-center w-7 h-7 rounded-md bg-slate-100 text-slate-600 hover:bg-slate-200 transition"
            @click="openFullscreen"
          >
            <span class="inline-block w-3.5 h-3.5" v-html="I.maximize" />
          </button>
        </div>
      </template>
      <NamdCompareDeltaBar :a="aVisit" :b="bVisit" />
    </Card>

    <div v-if="!fsOpen" class="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <Card
        v-for="(side, i) in [
          { tag: t('studyModules.namd.compare.tagReference'), id: aId, setId: (v: string) => (aId = v), visit: aVisit, prev: aPrev, slice: leftSlice, setSlice: leftSetSlice, excludeOther: bId, variant: 'A' as const, idBase: 'cmp-a' },
          { tag: t('studyModules.namd.compare.tagCurrent'),   id: bId, setId: (v: string) => (bId = v), visit: bVisit, prev: bPrev, slice: rightSlice, setSlice: rightSetSlice, excludeOther: aId, variant: 'B' as const, idBase: 'cmp-b' },
        ]"
        :key="i"
      >
        <!-- Per-pane header. Card has no #title slot, so we render
             the tag + visit picker inline above the frame. -->
        <div class="flex items-center mb-3 gap-2 flex-wrap">
          <span class="text-[10px] font-semibold uppercase tracking-[0.1em] text-slate-400 whitespace-nowrap">
            {{ side.tag }}
          </span>
          <NamdVisitPicker
            :model-value="side.id"
            :visits="props.data.visits"
            :exclude-id="side.excludeOther"
            :variant="side.variant"
            @update:model-value="(v) => side.setId(v)"
          />
        </div>
        <NamdScanFrame
          v-if="side.visit"
          :visit="side.visit"
          :prev-visit="side.prev"
          :eye="props.data.patient.eye"
          :n-slices="nSlices"
          :slice="side.slice"
          :mask="mask"
          :show-thumbs="false"
          :enable-fullscreen="false"
          :id-base="side.idBase"
          @update:slice="(z) => side.setSlice(z)"
          @update:mask="(m) => (mask = m)"
        />
        <div
          v-else
          class="rounded-md bg-slate-50 px-4 py-6 text-center text-sm text-slate-500"
        >
          {{ t('studyModules.namd.viewer.empty') }}
        </div>
        <div
          v-if="side.visit"
          class="mt-3 grid grid-cols-3 gap-2 text-center"
        >
          <div
            v-for="k in (['IRF','SRF','PED'] as const)"
            :key="k"
            class="rounded-lg bg-slate-50 py-1.5"
          >
            <div class="inline-flex items-center gap-1 text-[10px] text-slate-500">
              <span class="w-2 h-2 rounded-full" :style="`background:${FLUID[k].color}`" />{{ k }}
            </div>
            <div class="text-[14px] font-semibold text-slate-900 tabular-nums">
              {{ side.visit[k.toLowerCase() as 'irf' | 'srf' | 'ped'] }}
              <span class="text-[10px] text-slate-400 font-normal"> nL</span>
            </div>
          </div>
        </div>
      </Card>
    </div>

    <div v-if="!fsOpen" class="flex items-center justify-center gap-2 text-[12px] text-slate-500">
      <span class="inline-block w-4 h-4 align-middle">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 4 L22 20 H2 Z M12 10 V14 M12 17 V17.5" />
        </svg>
      </span>
      <span>{{ t('studyModules.namd.compare.syncedScrollTip') }}</span>
    </div>
  </div>
</template>

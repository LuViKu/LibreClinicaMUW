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
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from '../components/primitives/Card.vue'
import NamdVisitPicker from '../components/NamdVisitPicker.vue'
import NamdCompareDeltaBar from '../components/NamdCompareDeltaBar.vue'
import NamdScanFrame from '../components/NamdScanFrame.vue'
import { FLUID } from '../fluid'
import type { NamdVisit } from '../types'

interface Props {
  data: { patient: { eye: 'OD' | 'OS' } } & {
    visits: NamdVisit[]
    current: NamdVisit | null
    prev: NamdVisit | null
    nSlices: number | null
  }
}
const props = defineProps<Props>()
const { t } = useI18n()

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
</script>

<template>
  <div data-testid="namd-compare-tab" class="space-y-5">
    <Card :title="t('studyModules.namd.compare.delta')">
      <template #right>
        <label
          class="inline-flex items-center gap-2 text-[12px] text-slate-600 cursor-pointer select-none"
          data-testid="namd-compare-synced-toggle"
        >
          <span
            class="relative w-9 h-5 rounded-full transition inline-block"
            :class="synced ? 'bg-muw-blue' : 'bg-slate-300'"
            @click="synced = !synced"
          >
            <span
              class="absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all"
              :class="synced ? 'left-4' : 'left-0.5'"
            />
          </span>
          {{ t('studyModules.namd.compare.syncedScroll') }}
        </label>
      </template>
      <NamdCompareDeltaBar :a="aVisit" :b="bVisit" />
    </Card>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <Card
        v-for="(side, i) in [
          { tag: t('studyModules.namd.compare.tagReference'), id: aId, setId: (v: string) => (aId = v), visit: aVisit, slice: leftSlice, setSlice: leftSetSlice, excludeOther: bId, variant: 'A' as const, idBase: 'cmp-a' },
          { tag: t('studyModules.namd.compare.tagCurrent'),   id: bId, setId: (v: string) => (bId = v), visit: bVisit, slice: rightSlice, setSlice: rightSetSlice, excludeOther: aId, variant: 'B' as const, idBase: 'cmp-b' },
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
          :eye="props.data.patient.eye"
          :n-slices="nSlices"
          :slice="side.slice"
          :mask="mask"
          :show-thumbs="false"
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

    <div class="flex items-center justify-center gap-2 text-[12px] text-slate-500">
      <span class="inline-block w-4 h-4 align-middle">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 4 L22 20 H2 Z M12 10 V14 M12 17 V17.5" />
        </svg>
      </span>
      <span>{{ t('studyModules.namd.compare.syncedScrollTip') }}</span>
    </div>
  </div>
</template>

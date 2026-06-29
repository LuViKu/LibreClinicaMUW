<script setup lang="ts">
/**
 * 2026-06-26 — Fullscreen wrapper around BscanViewer + BscanLayerEditOverlay.
 *
 * Single component both the inference-job viewer (RetinalMetricsView) and
 * the nAMD OCT-Viewer tab (NamdScanFrame) mount when they want to expose
 * the IOWA layer correction UI. Owns:
 *
 *   - The fixed-inset fullscreen wrapper + dark backdrop + body scroll-lock
 *     + Esc handler.
 *   - The masthead (eyebrow, KI-Maske toggle, Save button, close).
 *   - The Save → POST cycle (one request per edited layer); on success
 *     clears pending state + busts the segmentation cache so the canvas
 *     repaints with the merged envelope.
 *   - The unsaved-changes discard confirm dialog.
 *
 * The role gate is on the parent — `canEdit` is forwarded into the overlay.
 * If false the overlay still renders (read-only) but no tool palette is
 * shown and the Save button is hidden.
 */
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import BscanLayerEditOverlay from '@/components/BscanLayerEditOverlay.vue'
import {
  clearSegmentationEnvelopeCache,
  useSegmentationEnvelope,
} from '@/composables/useSegmentationEnvelope'
import { saveLayerCorrection } from '@/api/retinal'
import { I } from '@/studyModules/nAMD/icons'
import { IOWA_LAYER_LABELS } from '@/components/retinalPalette'

const BscanViewer = defineAsyncComponent(() => import('@/components/BscanViewer.vue'))

interface Props {
  /** Job whose segmentation envelope drives the overlay. */
  jobId: number
  /** Streaming DICOM URL — passed straight to BscanViewer. */
  bscanDcmUrl: string
  /** Number of B-scans in the volume. */
  nBscans: number
  /** Current slice — v-model. */
  modelValue: number
  /** KI-Maske toggle — v-model. */
  showSegmentation: boolean
  /** Eyebrow text on the fullscreen masthead. e.g. "OD · V03 · 23.06.2026". */
  title?: string
  /** Role-gated: false renders the overlay read-only (no tools, no Save). */
  canEdit: boolean
  /**
   * Surfaces the operator may correct. Defaults to all surfaces in the
   * envelope. nAMD viewer passes `[0, 10]` for ILM + BM only.
   */
  correctableLayerIndices?: readonly number[]
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  correctableLayerIndices: () => [],
})

const emit = defineEmits<{
  'update:modelValue': [z: number]
  'update:showSegmentation': [v: boolean]
  close: []
  /** Emitted after the Save POSTs succeed. Parent renders the toast. */
  'save-success': [info: { layers: number; slices: number }]
  /** Emitted when any Save POST throws. Parent renders the toast. */
  'save-error': [message: string]
}>()

const { t } = useI18n()

const jobIdRef = computed(() => props.jobId)
const { envelope } = useSegmentationEnvelope(jobIdRef)

const cols = computed<number>(() => {
  const env = envelope.value
  if (!env || env.shape.length < 2) return 0
  // For layers task: shape = [n_surfaces, z, cols] → cols = shape[2].
  // For onl/pr (2-rank): shape = [z, cols] → cols = shape[1].
  return env.shape.length === 3 ? (env.shape[2] ?? 0) : (env.shape[1] ?? 0)
})
/**
 * 2026-06-27 — image rows is read from BscanViewer's overlay slot
 * via {@link slotRows} below; this stays as a safety fallback only
 * (Heidelberg cube default) for any code path that needs a value
 * before the slot binds.
 */
const rows = computed<number>(() => 496)
function slotRows(slotProps: unknown): number {
  const dims = (slotProps as { imageDims?: { rows?: number } | null })?.imageDims
  return dims?.rows ?? rows.value
}
function slotBbox(slotProps: unknown): Record<string, string> {
  const bbox = (slotProps as { bboxStyle?: Record<string, string> })?.bboxStyle
  return bbox ?? {}
}
const nSurfaces = computed<number>(() => {
  const env = envelope.value
  if (!env) return 0
  return env.shape.length === 3 ? (env.shape[0] ?? 0) : 1
})
const envelopeData = computed<Float32Array | null>(() => {
  const env = envelope.value
  if (!env || env.dtype !== 'float32') return null
  return env.data as Float32Array
})
const labels = computed<readonly string[]>(() => {
  const env = envelope.value
  if (env?.labels?.length) return env.labels
  return IOWA_LAYER_LABELS
})

const overlayRef = ref<InstanceType<typeof BscanLayerEditOverlay> | null>(null)
const pendingCount = ref(0)
const saving = ref(false)
const showDiscardConfirm = ref(false)
/**
 * 2026-06-27 — Surface indices the edit overlay is painting itself.
 * Forwarded to BscanViewer's {@code suppressedSurfaceIndices} so the
 * cornerstone canvas SKIPS those surfaces in its surface_y paint and
 * doesn't double-render the original AI line under the operator's
 * edited curve.
 */
const paintedSurfaces = ref<readonly number[]>([])

/**
 * 2026-06-27 — Inline success pill displayed in the fullscreen
 * masthead after a save. The SPA has no toast store; routing this
 * through useErrorsStore makes the green "saved" message render as a
 * RED "Ein Fehler ist aufgetreten" pill via GlobalErrorToast. The pill
 * auto-clears after 3.5 s.
 */
const savedToastText = ref<string>('')
let savedToastTimer: ReturnType<typeof setTimeout> | null = null
function showSavedToast(text: string): void {
  savedToastText.value = text
  if (savedToastTimer) clearTimeout(savedToastTimer)
  savedToastTimer = setTimeout(() => {
    savedToastText.value = ''
    savedToastTimer = null
  }, 3500)
}

function onUpdateSlice(z: number): void {
  emit('update:modelValue', z)
}

async function onSaveClick(): Promise<void> {
  if (saving.value || pendingCount.value === 0) return
  saving.value = true
  try {
    const overlay = overlayRef.value
    if (!overlay) return
    // Trigger emit('save', payload) from the overlay component.
    // The overlay exposes emitSave() via defineExpose.
    const payload = await new Promise<Map<number, Map<number, number[]>>>((resolve) => {
      // Use a once-listener pattern — overlay's 'save' event is the
      // operator's intent; we transform pendingEdits into the payload
      // shape inside the overlay's emitSave().
      const handler = (p: Map<number, Map<number, number[]>>) => {
        resolve(p)
      }
      saveResolver = handler
      overlay.emitSave()
    })
    // Per-layer POSTs in parallel. The backend's UPSERT is atomic per
    // (job_id, layer_index) so multiple layers in flight don't race.
    let savedLayers = 0
    let savedSlices = 0
    await Promise.all(Array.from(payload.entries()).map(async ([layerIdx, perSlice]) => {
      const layerLabel = labels.value[layerIdx] ?? IOWA_LAYER_LABELS[layerIdx] ?? `L${layerIdx}`
      const rowsByZ: Record<string, number[]> = {}
      for (const [z, row] of perSlice) {
        rowsByZ[String(z)] = row
        savedSlices++
      }
      await saveLayerCorrection(props.jobId, layerIdx, layerLabel, rowsByZ)
      savedLayers++
    }))
    overlay.clearPending()
    pendingCount.value = 0
    clearSegmentationEnvelopeCache(props.jobId)
    showSavedToast(
      t('retinal.correction.savedToast', { layers: savedLayers, slices: savedSlices }),
    )
    emit('save-success', { layers: savedLayers, slices: savedSlices })
  } catch (e) {
    emit('save-error', e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

let saveResolver: ((p: Map<number, Map<number, number[]>>) => void) | null = null
function onOverlaySave(payload: Map<number, Map<number, number[]>>): void {
  if (saveResolver) {
    saveResolver(payload)
    saveResolver = null
  }
}

function requestClose(): void {
  if (pendingCount.value > 0) {
    showDiscardConfirm.value = true
    return
  }
  emit('close')
}

function confirmDiscard(): void {
  overlayRef.value?.clearPending()
  pendingCount.value = 0
  showDiscardConfirm.value = false
  emit('close')
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.preventDefault()
    if (showDiscardConfirm.value) {
      showDiscardConfirm.value = false
    } else {
      requestClose()
    }
  }
}

let prevOverflow = ''
watch(
  () => true,
  () => {
    if (typeof document === 'undefined') return
    document.addEventListener('keydown', onKey)
    prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  if (typeof document === 'undefined') return
  document.removeEventListener('keydown', onKey)
  document.body.style.overflow = prevOverflow
  if (savedToastTimer) {
    clearTimeout(savedToastTimer)
    savedToastTimer = null
  }
})
</script>

<template>
  <div
    data-testid="retinal-correction-fullscreen"
    class="fixed inset-0 z-50 bg-black/95 backdrop-blur-sm px-5 py-4 flex flex-col gap-3 select-none"
  >
    <!-- Masthead -->
    <header class="flex items-center justify-between gap-4 shrink-0">
      <div class="flex items-center gap-3 text-white min-w-0">
        <span class="text-[12px] font-semibold uppercase tracking-[0.12em] whitespace-nowrap">
          {{ t('retinal.bscanViewer.header') }}
        </span>
        <span v-if="title" class="text-white/45 text-[12px] truncate">{{ title }}</span>
      </div>
      <div class="flex items-center gap-2.5 shrink-0">
        <!-- KI-Maske toggle (mirror NamdScanFrame's style) -->
        <button
          type="button"
          data-testid="correction-fs-mask"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition"
          :class="showSegmentation ? 'bg-white text-muw-blue' : 'bg-white/10 text-white/85 hover:bg-white/20'"
          @click="emit('update:showSegmentation', !showSegmentation)"
        >
          <span class="w-2 h-2 rounded-full" :class="showSegmentation ? 'bg-muw-teal' : 'bg-white/50'" />
          {{ showSegmentation
            ? t('studyModules.namd.scanFrame.maskOn')
            : t('studyModules.namd.scanFrame.maskOff') }}
        </button>
        <!-- 2026-06-27 — Inline success pill (replaces routing through
             the errors store, which would render this green message as
             a red "Ein Fehler ist aufgetreten" toast via GlobalErrorToast). -->
        <transition
          enter-active-class="transition duration-150"
          leave-active-class="transition duration-300"
          enter-from-class="opacity-0 translate-y-1"
          leave-to-class="opacity-0"
        >
          <span
            v-if="savedToastText"
            data-testid="correction-fs-saved-toast"
            class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold bg-emerald-500/90 text-white shadow"
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
              <path d="M5 12 L10 17 L20 7" />
            </svg>
            {{ savedToastText }}
          </span>
        </transition>
        <!-- Save button (edit-gated) -->
        <button
          v-if="canEdit"
          type="button"
          data-testid="correction-fs-save"
          :disabled="pendingCount === 0 || saving"
          class="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[11px] font-semibold transition"
          :class="pendingCount > 0 && !saving
            ? 'bg-muw-teal text-white hover:bg-muw-teal-700'
            : 'bg-white/10 text-white/40'"
          @click="onSaveClick"
        >
          {{ saving ? '…' : t('retinal.correction.save') }}
          <span
            v-if="pendingCount > 0"
            class="ml-1 px-1.5 py-0.5 rounded-full bg-white/25 text-[10px]"
          >{{ t('retinal.correction.saveBadge', { n: pendingCount }) }}</span>
        </button>
        <!-- Close -->
        <button
          type="button"
          data-testid="correction-fs-close"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold bg-white/10 text-white hover:bg-white/20 transition"
          @click="requestClose"
        >
          <span class="inline-block w-3.5 h-3.5" v-html="I.close" />
          {{ t('studyModules.namd.scanFrame.fsClose') }}
        </button>
      </div>
    </header>

    <!-- B-scan + overlay -->
    <div class="flex-1 min-h-0 relative rounded-xl overflow-hidden bg-black ring-1 ring-black/40">
      <BscanViewer
        :bscan-dcm-url="bscanDcmUrl"
        :n-bscans="nBscans"
        :model-value="modelValue"
        :job-id="jobId"
        :show-segmentation="showSegmentation"
        :fill-container="true"
        :suppressed-surface-indices="paintedSurfaces"
        class="h-full"
        @update:model-value="onUpdateSlice"
      >
        <template #overlay="slotProps">
          <BscanLayerEditOverlay
            ref="overlayRef"
            :job-id="jobId"
            :n-bscans="nBscans"
            :cols="cols"
            :rows="slotRows(slotProps)"
            :model-value="modelValue"
            :envelope-data="envelopeData"
            :n-surfaces="nSurfaces"
            :labels="labels"
            :correctable-layer-indices="correctableLayerIndices"
            :can-edit="canEdit"
            :bbox-style="slotBbox(slotProps)"
            @update:model-value="onUpdateSlice"
            @save="onOverlaySave"
            @pending-edit-count="(n) => pendingCount = n"
            @painted-surfaces="(s) => paintedSurfaces = s"
          />
        </template>
      </BscanViewer>
    </div>

    <!-- Discard confirm -->
    <div
      v-if="showDiscardConfirm"
      data-testid="correction-fs-discard-confirm"
      class="fixed inset-0 z-[60] bg-black/70 backdrop-blur-sm flex items-center justify-center"
      @click.self="showDiscardConfirm = false"
    >
      <div class="bg-white rounded-2xl shadow-xl max-w-md mx-auto p-6">
        <p class="text-[14px] font-semibold text-slate-900 mb-4">
          {{ t('retinal.correction.discardConfirm') }}
        </p>
        <div class="flex items-center justify-end gap-2">
          <button
            type="button"
            class="px-3.5 py-1.5 rounded-lg border border-slate-200 text-[12px] font-medium text-slate-700 hover:bg-slate-50"
            @click="showDiscardConfirm = false"
          >{{ t('retinal.correction.discardCancel') }}</button>
          <button
            type="button"
            class="px-3.5 py-1.5 rounded-lg bg-rose-600 text-white text-[12px] font-semibold hover:bg-rose-700"
            @click="confirmDiscard"
          >{{ t('retinal.correction.discardYes') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * nAMD treat-and-extend Slice 5 (2026-06-20) — in-SPA B-scan
 * navigator.
 *
 * Mounts Cornerstone.js 3 against a multi-frame `bscan.dcm`
 * artifact and surfaces a stack scrubber so the physician can
 * scroll individual OCT slices without leaving the browser.
 * Critical for the Arm B path of the nAMD study, where the AI
 * en-face panel is gated off and the raw OCT IS the entire
 * dataset the physician sees.
 *
 * Lazy-loaded via {@code defineAsyncComponent} from
 * {@code RetinalMetricsView} so the ~150 KB Cornerstone bundle
 * only ships to operators who actually open a retinal view.
 *
 * Bidirectional hover sync: emits {@code update:bscan-z} when the
 * user scrubs; accepts {@code modelValue} (the current B-scan
 * index) as input so the {@link FundusOverlay}'s per-B-scan hover
 * can jump the viewer to the matching slice.
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

interface Props {
  /** Absolute URL of the multi-frame {@code bscan.dcm} artifact. */
  bscanDcmUrl: string
  /** Total B-scan count (n_bscans) — used to drive the slider range. */
  nBscans: number
  /** Current B-scan index (0-based) — bidirectional via v-model. */
  modelValue: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [z: number]
}>()

const containerEl = ref<HTMLDivElement | null>(null)
const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const errorMessage = ref<string | null>(null)

// Hold the cornerstone viewport ref non-reactively (the cornerstone
// objects mutate internally; we don't want Vue's proxy attached).
const viewport = shallowRef<unknown | null>(null)
const renderingEngineRef = shallowRef<{ destroy: () => void } | null>(null)

const sliderMax = computed(() => Math.max(0, props.nBscans - 1))

async function initViewer(): Promise<void> {
  if (!containerEl.value) return
  status.value = 'loading'
  errorMessage.value = null
  try {
    // 2026-06-22 — @cornerstonejs/dicom-image-loader 1.86 ships two
    // bundles in dist/: the default web-worker build (needs an
    // explicit worker URL via Vite worker-bundling) and a
    // no-web-workers build that decodes on the main thread. The
    // latter avoids the worker-path bootstrapping entirely; we're
    // viewing one DICOM at a time so the main-thread decode is fine
    // performance-wise. Hitting the file path directly bypasses
    // package-exports inversions that older bundler resolutions
    // sometimes apply to the default UMD entry.
    const [
      cornerstoneCoreModule,
      cornerstoneLoaderModule,
      dicomParserModule,
    ] = await Promise.all([
      import('@cornerstonejs/core'),
      // @ts-expect-error — sub-path import with no .d.ts;
      // the runtime shape matches the with-workers bundle minus
      // webWorkerManager.
      import('@cornerstonejs/dicom-image-loader/dist/cornerstoneDICOMImageLoaderNoWebWorkers.bundle.min.js'),
      import('dicom-parser'),
    ])
    // Dynamic import may wrap the namespace under .default for UMD
    // bundles; unwrap defensively so a future ESM-native release
    // doesn't double-wrap.
    const cornerstoneCore = (cornerstoneCoreModule as { default?: unknown }).default ?? cornerstoneCoreModule
    const cornerstoneLoader = (cornerstoneLoaderModule as { default?: unknown }).default ?? cornerstoneLoaderModule
    const dicomParser = (dicomParserModule as { default?: unknown }).default ?? dicomParserModule

    // Wire the DICOM image loader against the cornerstone core + the
    // parser. Subsequent component mounts in the same session no-op
    // via the loader's internal singleton. The NoWebWorkers build
    // does NOT expose webWorkerManager / init at the top level —
    // decoding kicks in transparently on first wadouri fetch.
    type CornerstoneLoader = {
      external: { cornerstone: unknown; dicomParser: unknown }
    }
    const loader = cornerstoneLoader as CornerstoneLoader
    loader.external.cornerstone = cornerstoneCore
    loader.external.dicomParser = dicomParser
    await (cornerstoneCore as { init: () => Promise<void> }).init()

    const engineId = `bscan-engine-${Math.floor(performance.now())}`
    const viewportId = `bscan-viewport-${Math.floor(performance.now())}`
    type RE = {
      destroy: () => void
      enableElement: (input: unknown) => void
      getViewport: (id: string) => unknown
    }
    const RenderingEngine = (cornerstoneCore as unknown as {
      RenderingEngine: new (id: string) => RE
    }).RenderingEngine
    const re = new RenderingEngine(engineId)
    renderingEngineRef.value = re

    // Build per-frame wado image IDs — one entry per B-scan.
    const imageIds = Array.from({ length: props.nBscans }, (_, frame) =>
      `wadouri:${props.bscanDcmUrl}?frame=${frame}`,
    )

    // Enable a stack viewport into the container element.
    const Enums = (cornerstoneCore as unknown as { Enums: { ViewportType: { STACK: string } } }).Enums
    re.enableElement({
      viewportId,
      type: Enums.ViewportType.STACK,
      element: containerEl.value,
    })
    const vp = re.getViewport(viewportId) as {
      setStack: (ids: string[]) => Promise<void>
      setImageIdIndex: (idx: number) => Promise<void>
      render: () => void
    }
    viewport.value = vp
    await vp.setStack(imageIds)
    await vp.setImageIdIndex(clampZ(props.modelValue))
    vp.render()
    status.value = 'ready'
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Cornerstone init failed'
    status.value = 'error'
  }
}

function clampZ(z: number): number {
  return Math.max(0, Math.min(z, sliderMax.value))
}

async function setZ(z: number) {
  const clamped = clampZ(z)
  if (clamped !== props.modelValue) emit('update:modelValue', clamped)
  if (viewport.value) {
    const vp = viewport.value as {
      setImageIdIndex: (idx: number) => Promise<void>
      render: () => void
    }
    await vp.setImageIdIndex(clamped)
    vp.render()
  }
}

watch(
  () => props.modelValue,
  async (z) => {
    if (status.value !== 'ready') return
    if (viewport.value) {
      const vp = viewport.value as {
        setImageIdIndex: (idx: number) => Promise<void>
        render: () => void
      }
      await vp.setImageIdIndex(clampZ(z))
      vp.render()
    }
  },
)

onMounted(initViewer)

onBeforeUnmount(() => {
  try {
    renderingEngineRef.value?.destroy()
  } catch {
    /* never throw on unmount */
  }
})
</script>

<template>
  <section
    data-testid="bscan-viewer"
    class="bg-slate-900 rounded-muw overflow-clip border border-slate-200"
  >
    <header class="px-3 py-1.5 flex items-baseline justify-between bg-slate-800 text-slate-200">
      <h3 class="text-xs font-semibold uppercase tracking-wider">
        {{ t('retinal.bscanViewer.header') }}
      </h3>
      <span class="text-[11px] text-slate-400 tabular-nums">
        {{ t('retinal.bscanViewer.position', { current: modelValue + 1, total: nBscans }) }}
      </span>
    </header>

    <div
      ref="containerEl"
      data-testid="bscan-viewer-canvas"
      class="aspect-[4/3] w-full bg-black"
      tabindex="0"
      @keydown.left.prevent="setZ(modelValue - 1)"
      @keydown.right.prevent="setZ(modelValue + 1)"
    />

    <div
      v-if="status === 'loading'"
      data-testid="bscan-viewer-loading"
      class="px-3 py-2 text-xs text-slate-300"
    >
      {{ t('retinal.bscanViewer.loading') }}
    </div>
    <div
      v-else-if="status === 'error'"
      data-testid="bscan-viewer-error"
      class="px-3 py-2 text-xs text-rose-300"
    >
      {{ t('retinal.bscanViewer.errorPrefix') }} {{ errorMessage }}
    </div>

    <div class="px-3 py-2 flex items-center gap-2 bg-slate-800">
      <button
        type="button"
        class="text-slate-300 hover:text-white px-2 py-0.5 text-xs"
        :disabled="modelValue <= 0"
        @click="setZ(modelValue - 1)"
      >
        ←
      </button>
      <input
        type="range"
        min="0"
        :max="sliderMax"
        :value="modelValue"
        class="flex-1"
        data-testid="bscan-viewer-slider"
        @input="(e) => setZ(Number((e.target as HTMLInputElement).value))"
      />
      <button
        type="button"
        class="text-slate-300 hover:text-white px-2 py-0.5 text-xs"
        :disabled="modelValue >= sliderMax"
        @click="setZ(modelValue + 1)"
      >
        →
      </button>
    </div>
  </section>
</template>

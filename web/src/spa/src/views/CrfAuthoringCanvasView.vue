<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import PaletteRail from '@/components/crfAuthoring/PaletteRail.vue'
import SectionCanvas from '@/components/crfAuthoring/SectionCanvas.vue'
import PropertiesRail from '@/components/crfAuthoring/PropertiesRail.vue'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'

/**
 * App-feedback Wave 2 (2026-06-19) — CRF authoring canvas view.
 *
 * <p>Replaces the {@link CrfAuthoringWizard} side-rail wizard for one
 * release behind a feature flag (the legacy wizard route stays in
 * {@code router/index.ts} via {@code meta.legacy = true} so the menu
 * can hide it cleanly later).
 *
 * <p>Layout: three columns side-by-side:
 *
 * <ul>
 *   <li><b>Left</b> — {@link PaletteRail}: primitive items + preset
 *       catalog (IOP, OPHTH_EXAM).</li>
 *   <li><b>Middle</b> — {@link SectionCanvas}: drop zones (sections),
 *       drag-to-reorder items, bilateral grid when {@code section.bilateral}
 *       is set.</li>
 *   <li><b>Right</b> — {@link PropertiesRail}: per-selected-item editor.</li>
 * </ul>
 *
 * <p>Persistence is unchanged: the existing {@code store.submit()} POST
 * runs against {@code /pages/api/v1/crfs/{crfOid}/versions} with the
 * same payload shape as the wizard. The canvas only changes the
 * editing surface.
 *
 * <p>Route params accept {@code crfOid} (path param) and {@code crfName}
 * (query string for display). The default landing flow is via the CRF
 * library — operators click "Author new version" which now navigates
 * here with the right params.
 */

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useCrfAuthoringStore()

const crfOid = computed(() => {
  const raw = route.params.crfOid
  return Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')
})

const crfName = computed(() => {
  const raw = route.query.name
  if (Array.isArray(raw)) return raw[0] ?? ''
  return (raw as string | undefined) ?? ''
})

const formError = ref<string | null>(null)
const submitParseErrors = ref<string[]>([])
const submitFieldErrors = ref<Record<string, string>>({})

onMounted(() => {
  // Fresh draft + drop any catalog cache from a previous session.
  store.reset()
  void store.loadResponseSetCatalog()
})

async function onSave(): Promise<void> {
  formError.value = null
  submitParseErrors.value = []
  submitFieldErrors.value = {}
  const result = await store.submit(crfOid.value)
  if (result.ok) {
    // Land back on the CRF library so the operator can verify the new
    // version is listed.
    void router.push({ name: 'crf-library' })
    return
  }
  formError.value = result.message ?? t('crfAuthoring.canvas.errors.unknown')
  submitParseErrors.value = result.parseErrors
  submitFieldErrors.value = result.fieldErrors
}

function onCancel(): void {
  void router.push({ name: 'crf-library' })
}

function onUseLegacyWizard(): void {
  void router.push({ name: 'crf-library', query: { authorWizard: '1', crfOid: crfOid.value } })
}
</script>

<template>
  <div class="flex flex-col h-[calc(100vh-3rem)]" data-testid="crf-canvas-view">
    <!-- Header -->
    <header class="flex items-center justify-between px-4 py-2 border-b border-slate-200 bg-white">
      <div>
        <h1 class="text-base font-semibold text-slate-800">
          {{ t('crfAuthoring.canvas.heading') }}
        </h1>
        <p class="text-[11px] text-slate-500 mt-0.5">
          {{ crfName ? t('crfAuthoring.canvas.subheading', { name: crfName }) : t('crfAuthoring.canvas.subheadingGeneric') }}
        </p>
      </div>
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="text-xs text-slate-600 hover:text-slate-800 underline"
          data-testid="crf-canvas-use-legacy"
          @click="onUseLegacyWizard"
        >
          {{ t('crfAuthoring.canvas.useLegacy') }}
        </button>
        <button
          type="button"
          class="text-xs px-3 py-1.5 rounded border border-slate-300 text-slate-700 hover:bg-slate-100"
          data-testid="crf-canvas-cancel"
          @click="onCancel"
        >
          {{ t('crfAuthoring.canvas.cancel') }}
        </button>
        <button
          type="button"
          class="text-xs px-3 py-1.5 rounded bg-muw-blue text-white hover:bg-muw-blue/90 disabled:opacity-50"
          :disabled="store.isSubmitting || !crfOid"
          data-testid="crf-canvas-save"
          @click="onSave"
        >
          {{ store.isSubmitting ? t('crfAuthoring.canvas.saving') : t('crfAuthoring.canvas.save') }}
        </button>
      </div>
    </header>

    <!-- Error banner -->
    <div
      v-if="formError"
      class="px-4 py-2 bg-red-50 border-b border-red-200 text-xs text-red-800"
      role="alert"
      data-testid="crf-canvas-error"
    >
      <strong>{{ t('crfAuthoring.canvas.errorTitle') }}</strong>
      {{ formError }}
      <ul v-if="submitParseErrors.length > 0" class="mt-1 list-disc list-inside">
        <li v-for="(msg, idx) in submitParseErrors" :key="idx">{{ msg }}</li>
      </ul>
    </div>

    <!-- Three-column body -->
    <div class="flex flex-1 min-h-0">
      <PaletteRail />
      <SectionCanvas />
      <PropertiesRail />
    </div>

    <!-- Footer with metadata -->
    <footer class="flex items-center gap-3 px-4 py-2 border-t border-slate-200 bg-slate-50 text-[11px]">
      <label class="flex items-center gap-1.5 text-slate-700">
        <span>{{ t('crfAuthoring.canvas.versionName') }}</span>
        <input
          type="text"
          class="text-xs border-slate-200 rounded px-2 py-0.5"
          :value="store.draft.versionName"
          data-testid="crf-canvas-version-name"
          @input="(ev) => store.setVersionName((ev.target as HTMLInputElement).value)"
        />
      </label>
      <label class="flex items-center gap-1.5 text-slate-700 flex-1">
        <span>{{ t('crfAuthoring.canvas.versionDescription') }}</span>
        <input
          type="text"
          class="flex-1 text-xs border-slate-200 rounded px-2 py-0.5"
          :value="store.draft.versionDescription"
          data-testid="crf-canvas-version-description"
          @input="(ev) => store.setVersionDescription((ev.target as HTMLInputElement).value)"
        />
      </label>
    </footer>
  </div>
</template>

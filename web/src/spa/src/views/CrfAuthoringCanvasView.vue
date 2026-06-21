<script setup lang="ts">
import { computed, nextTick, onMounted, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import PaletteRail from '@/components/crfAuthoring/PaletteRail.vue'
import SectionCanvas from '@/components/crfAuthoring/SectionCanvas.vue'
import PropertiesRail from '@/components/crfAuthoring/PropertiesRail.vue'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'
import { useCrfPreviewStore } from '@/stores/crfPreview'
import PreviewCrfEntryView from '@/views/PreviewCrfEntryView.vue'
import { CrfAuthoringErrorsKey } from '@/components/crfAuthoring/errorsInjection'
import { humanizeValidationError } from '@/components/crfAuthoring/humanizeValidationError'

/**
 * App-feedback Wave 2 (2026-06-19) — CRF authoring canvas view.
 *
 * <p>Replaces the legacy side-rail CRF authoring wizard. The wizard
 * component + its mount in {@code CrfLibraryView} have been removed
 * (D3 follow-up, 2026-06-20) — this canvas is now the sole authoring
 * surface.
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

/**
 * 2026-06-21 user-feedback batch — preview button + overlay. The
 * preview store hydrates from the live authoring draft and the
 * existing PreviewCrfEntryView renders the same widgets the runtime
 * CRF-entry view uses. No persistence; close returns to the canvas.
 */
const previewStore = useCrfPreviewStore()

function onPreview(): void {
  previewStore.load(store.draft, { crfName: crfName.value })
}
function onClosePreview(): void {
  previewStore.close()
}

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
// Validate-only state: separate from save errors so re-validating after
// edits doesn't tear down a still-pending save banner. Lit-up the
// inline highlights via provide/inject (CrfAuthoringErrorsKey) so any
// descendant can opt-in without prop drilling.
const isValidating = ref(false)
const lastValidationOk = ref<boolean | null>(null)

/**
 * Field-error map provided to descendants (SectionCanvas) via inject.
 * Localising up-front keeps the inline red-border message in step
 * with the top summary card.
 */
const localizedFieldErrors = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(submitFieldErrors.value)) {
    out[k] = humanizeValidationError(v, t)
  }
  return out
})

// Provide the LOCALIZED error map to descendants so SectionCanvas's
// inline message stays in German alongside the top summary card.
provide(CrfAuthoringErrorsKey, localizedFieldErrors)

/**
 * 2026-06-21 user-feedback round 3 — footer-level versionName /
 * versionDescription inputs live OUTSIDE SectionCanvas, so the
 * provide/inject channel above does not reach them. The two computeds
 * below mirror the same localised lookup directly into the canvas
 * template so a "versionName is required" error highlights the matching
 * input + renders the German message under it.
 */
const versionNameError = computed<string | null>(
  () => localizedFieldErrors.value.versionName ?? null,
)
const versionDescriptionError = computed<string | null>(
  () => localizedFieldErrors.value.versionDescription ?? null,
)

/**
 * 2026-06-21 user-feedback round 6 — fork-from-version. The CRF library
 * deep-links into the canvas with {@code ?fromVersion=<versionOid>} when
 * the operator chose "aus v1.0 kopieren" instead of a blank-start. The
 * canvas seeds its draft via {@code store.loadFromVersion()} when the
 * query param is present and shows an info banner with the source name
 * so the operator can confirm the fork. Falls back to the standard
 * blank-start when loading the prior version fails or no fromVersion
 * is present.
 */
const fromVersionOid = computed<string | null>(() => {
  const raw = route.query.fromVersion
  if (Array.isArray(raw)) return raw[0] ?? null
  return (raw as string | undefined) ?? null
})
const fromVersionName = computed<string | null>(() => {
  const raw = route.query.fromVersionName
  if (Array.isArray(raw)) return raw[0] ?? null
  return (raw as string | undefined) ?? null
})
const isForking = ref(false)
const forkLoaded = ref(false)
const forkFailed = ref(false)

onMounted(async () => {
  // Drop any catalog cache from a previous session.
  void store.loadResponseSetCatalog()
  if (crfOid.value && fromVersionOid.value) {
    isForking.value = true
    try {
      const ok = await store.loadFromVersion(crfOid.value, fromVersionOid.value)
      forkLoaded.value = ok
      forkFailed.value = !ok
      if (!ok) store.reset()
    } finally {
      isForking.value = false
    }
    return
  }
  store.reset()
})

async function onValidate(): Promise<void> {
  if (!crfOid.value) return
  formError.value = null
  submitParseErrors.value = []
  submitFieldErrors.value = {}
  isValidating.value = true
  lastValidationOk.value = null
  try {
    const result = await store.preview(crfOid.value)
    if (result.ok) {
      lastValidationOk.value = true
      return
    }
    lastValidationOk.value = false
    formError.value = result.message === 'Validation failed'
      ? t('crfAuthoring.canvas.errors.validationFailed')
      : result.message ?? t('crfAuthoring.canvas.errors.unknown')
    submitParseErrors.value = result.parseErrors
    submitFieldErrors.value = result.fieldErrors
  } finally {
    isValidating.value = false
  }
}

/**
 * Build a flat list of failures the summary card can render.
 * Each entry knows its OID + the section/item indices needed for the
 * "Springen" anchor link so the operator can scroll to the offending
 * item.
 */
interface FieldErrorEntry {
  oid: string
  message: string
  sectionIndex: number | null
  itemUid: string | null
  itemName: string | null
}

const fieldErrorList = computed<FieldErrorEntry[]>(() => {
  const out: FieldErrorEntry[] = []
  for (const [oid, message] of Object.entries(submitFieldErrors.value)) {
    let sectionIndex: number | null = null
    let itemUid: string | null = null
    let itemName: string | null = null
    // OIDs come back with optional `items[oid].field` or `sections[label].*`
    // paths from the backend validator; match the bare OID first.
    const directMatch = oid.replace(/^items\./, '').replace(/\.(name|oid|.*)$/, '')
    for (let si = 0; si < store.draft.sections.length; si++) {
      const sec = store.draft.sections[si]!
      const found = sec.items.find((it) => it.oid === directMatch || it.oid === oid)
      if (found) {
        sectionIndex = si
        itemUid = found.uid
        itemName = found.name || found.oid
        break
      }
    }
    // 2026-06-21 user-feedback batch — surface the German equivalent
    // when the backend validator emits a known English string.
    out.push({
      oid,
      message: humanizeValidationError(message, t),
      sectionIndex,
      itemUid,
      itemName,
    })
  }
  return out
})

function jumpToError(entry: FieldErrorEntry): void {
  if (entry.itemUid) {
    store.selectItem(entry.itemUid)
    // Defer scroll until the next tick so the item card is mounted /
    // re-rendered with its error highlight.
    void nextTick(() => {
      const el = document.querySelector(`[data-item-uid="${entry.itemUid}"]`)
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
  }
}

async function onSave(): Promise<void> {
  formError.value = null
  submitParseErrors.value = []
  submitFieldErrors.value = {}
  lastValidationOk.value = null
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
          class="text-xs px-3 py-1.5 rounded border border-slate-300 text-slate-700 hover:bg-slate-100"
          data-testid="crf-canvas-preview"
          @click="onPreview"
        >
          {{ t('crfAuthoring.canvas.preview') }}
        </button>
        <button
          type="button"
          class="text-xs px-3 py-1.5 rounded border border-muw-blue text-muw-blue hover:bg-muw-blue/5 disabled:opacity-50"
          :disabled="isValidating || !crfOid"
          data-testid="crf-canvas-validate"
          @click="onValidate"
        >
          {{ isValidating ? t('crfAuthoring.canvas.validating') : t('crfAuthoring.canvas.validate') }}
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

    <!-- 2026-06-21 round 6 — fork-from-version status banners. -->
    <div
      v-if="isForking"
      class="px-4 py-2 bg-sky-50 border-b border-sky-200 text-xs text-sky-800"
      role="status"
      data-testid="crf-canvas-fork-loading"
    >
      {{ t('crfAuthoring.canvas.fork.loading') }}
    </div>
    <div
      v-else-if="forkLoaded"
      class="px-4 py-2 bg-sky-50 border-b border-sky-200 text-xs text-sky-800"
      role="status"
      data-testid="crf-canvas-fork-loaded"
    >
      {{ fromVersionName
          ? t('crfAuthoring.canvas.fork.loadedNamed', { name: fromVersionName })
          : t('crfAuthoring.canvas.fork.loaded') }}
    </div>
    <div
      v-else-if="forkFailed"
      class="px-4 py-2 bg-amber-50 border-b border-amber-200 text-xs text-amber-900"
      role="alert"
      data-testid="crf-canvas-fork-failed"
    >
      {{ t('crfAuthoring.canvas.fork.failed') }}
    </div>

    <!-- Validate-only success toast — disappears on the next change. -->
    <div
      v-if="lastValidationOk === true"
      class="px-4 py-2 bg-emerald-50 border-b border-emerald-200 text-xs text-emerald-800"
      role="status"
      data-testid="crf-canvas-validate-ok"
    >
      {{ t('crfAuthoring.canvas.validationOk') }}
    </div>

    <!-- Error banner — top summary listing every offending item with
         "Springen" anchor links + parse-error tail. Same data drives
         the inline red borders inside the canvas via inject. -->
    <div
      v-if="formError"
      class="px-4 py-3 bg-red-50 border-b border-red-200 text-xs text-red-800"
      role="alert"
      data-testid="crf-canvas-error"
    >
      <div class="flex items-start gap-2">
        <strong class="shrink-0">{{ t('crfAuthoring.canvas.errorTitle') }}</strong>
        <span>{{ formError }}</span>
      </div>
      <ul
        v-if="fieldErrorList.length > 0"
        class="mt-2 space-y-1"
        data-testid="crf-canvas-error-list"
      >
        <li
          v-for="entry in fieldErrorList"
          :key="entry.oid"
          class="flex items-start gap-1.5"
        >
          <span class="text-red-500">•</span>
          <button
            v-if="entry.itemUid"
            type="button"
            class="text-left underline decoration-dotted hover:text-red-900"
            :data-testid="`crf-canvas-error-jump-${entry.oid}`"
            @click="jumpToError(entry)"
          >
            <span class="font-semibold">{{ entry.itemName || entry.oid }}</span>
            <span class="ml-1.5 text-red-700">{{ entry.message }}</span>
          </button>
          <span v-else>
            <span class="font-semibold">{{ entry.oid }}</span>
            <span class="ml-1.5 text-red-700">{{ entry.message }}</span>
          </span>
        </li>
      </ul>
      <ul v-if="submitParseErrors.length > 0" class="mt-2 list-disc list-inside">
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
          class="text-xs bg-white rounded px-2 py-0.5 focus:outline-none focus:ring-1 placeholder:text-slate-400"
          :class="versionNameError
            ? 'border border-red-500 focus:border-red-600 focus:ring-red-300'
            : 'border border-slate-300 focus:border-muw-blue focus:ring-muw-blue'"
          :value="store.draft.versionName"
          :placeholder="t('crfAuthoring.canvas.versionNamePlaceholder')"
          data-testid="crf-canvas-version-name"
          :aria-invalid="versionNameError ? 'true' : 'false'"
          @input="(ev) => store.setVersionName((ev.target as HTMLInputElement).value)"
        />
        <span
          v-if="versionNameError"
          class="text-[10px] text-red-700"
          data-testid="crf-canvas-version-name-error"
        >
          {{ versionNameError }}
        </span>
      </label>
      <label class="flex items-center gap-1.5 text-slate-700 flex-1">
        <span>{{ t('crfAuthoring.canvas.versionDescription') }}</span>
        <input
          type="text"
          class="flex-1 text-xs bg-white rounded px-2 py-0.5 focus:outline-none focus:ring-1 placeholder:text-slate-400"
          :class="versionDescriptionError
            ? 'border border-red-500 focus:border-red-600 focus:ring-red-300'
            : 'border border-slate-300 focus:border-muw-blue focus:ring-muw-blue'"
          :value="store.draft.versionDescription"
          :placeholder="t('crfAuthoring.canvas.versionDescriptionPlaceholder')"
          data-testid="crf-canvas-version-description"
          :aria-invalid="versionDescriptionError ? 'true' : 'false'"
          @input="(ev) => store.setVersionDescription((ev.target as HTMLInputElement).value)"
        />
        <span
          v-if="versionDescriptionError"
          class="text-[10px] text-red-700"
          data-testid="crf-canvas-version-description-error"
        >
          {{ versionDescriptionError }}
        </span>
      </label>
    </footer>

    <!-- 2026-06-21 user-feedback batch — preview overlay. Mounted only
         when the preview store is open so descendants don't pay the
         render cost while the operator's still authoring. -->
    <PreviewCrfEntryView
      v-if="previewStore.isOpen"
      as-overlay
      @close="onClosePreview"
    />
  </div>
</template>

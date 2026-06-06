<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import draggable from 'vuedraggable'

import Modal from '@/components/Modal.vue'
import StatusPill from '@/components/StatusPill.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import ErrorText from '@/components/ErrorText.vue'
import ItemEditor from '@/components/ItemEditor.vue'
import PreviewCrfEntryView from '@/views/PreviewCrfEntryView.vue'

import {
  useCrfAuthoringStore,
  type AuthoringItem,
  type AuthoringPreviewSuccess,
} from '@/stores/crfAuthoring'
import { useCrfPreviewStore } from '@/stores/crfPreview'

/**
 * Phase E.6 Milestone B — manual eCRF authoring wizard.
 *
 * <p>Side-rail wizard with three sections: Metadata → Sections → Review.
 * Milestone B widens Milestone A's vertical slice to the full
 * non-formula taxonomy (see {@code crfAuthoring.ts}), adds multi-section
 * drag-reorder + per-section item drag-reorder via {@code vuedraggable},
 * and wires a Preview button on the Review step against the backend
 * {@code :preview} dry-run endpoint.
 *
 * <p>Backend wire (unchanged from A): {@code POST
 * /pages/api/v1/crfs/{crfOid}/versions} with {@code Content-Type:
 * application/json}. The backend synthesises an HSSF workbook from
 * the JSON and feeds it to the existing spreadsheet parser pipeline —
 * zero parity drift with XLS upload.
 */

interface Props {
  open: boolean
  crfOid: string
  crfName: string
}
const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [v: boolean]
  close: []
}>()

const { t } = useI18n()
const store = useCrfAuthoringStore()
const previewStore = useCrfPreviewStore()

type Step = 'metadata' | 'sections' | 'review'
const currentStep = ref<Step>('metadata')

const formError = ref<string | null>(null)
const submitParseErrors = ref<string[]>([])
const submitFieldErrors = ref<Record<string, string>>({})

const previewSummary = ref<AuthoringPreviewSuccess | null>(null)
const previewFieldErrors = ref<Record<string, string>>({})
const previewParseErrors = ref<string[]>([])
const previewError = ref<string | null>(null)

watch(
  () => props.open,
  (next) => {
    if (next) {
      store.reset()
      // Phase E.6 — drop any lingering live-preview overlay from a
      // previous wizard session so re-opening the wizard never
      // resurrects a stale preview.
      previewStore.close()
      currentStep.value = 'metadata'
      formError.value = null
      submitParseErrors.value = []
      submitFieldErrors.value = {}
      previewSummary.value = null
      previewFieldErrors.value = {}
      previewParseErrors.value = []
      previewError.value = null
      // Pull the response-set catalog so the inline picker can offer
      // existing entries. Failure is a soft fall-back (empty catalog).
      void store.loadResponseSetCatalog()
    }
  },
)

onMounted(() => {
  if (props.open) {
    void store.loadResponseSetCatalog()
  }
})

const sectionList = computed({
  get: () => store.draft.sections,
  set: (next) => store.reorderSections(next),
})

const canSubmit = computed(() => {
  const d = store.draft
  if (d.versionName.trim() === '') return false
  if (d.sections.length === 0) return false
  for (const section of d.sections) {
    if (section.label.trim() === '' || section.title.trim() === '') return false
    if (section.items.length === 0) return false
    for (const item of section.items) {
      if (item.name.trim() === '') return false
      if (!/^\w+$/.test(item.name.trim())) return false
      if (item.descriptionLabel.trim() === '') return false
    }
  }
  return true
})

function goToStep(step: Step): void {
  currentStep.value = step
}

function onAddItem(sectionIndex: number): void {
  store.addItem(sectionIndex)
}

function onRemoveItem(sectionIndex: number, itemIndex: number): void {
  store.removeItem(sectionIndex, itemIndex)
}

function onAddSection(): void {
  store.addSection()
}

function onRemoveSection(sectionIndex: number): void {
  store.removeSection(sectionIndex)
}

function onItemsReorder(sectionIndex: number, reordered: AuthoringItem[]): void {
  store.reorderItems(sectionIndex, reordered)
}

async function onPreview(): Promise<void> {
  if (store.isPreviewing) return
  previewSummary.value = null
  previewFieldErrors.value = {}
  previewParseErrors.value = []
  previewError.value = null
  const result = await store.preview(props.crfOid)
  if (result.ok) {
    previewSummary.value = result.preview
  } else {
    previewFieldErrors.value = result.fieldErrors
    previewParseErrors.value = result.parseErrors
    previewError.value = result.message ?? null
  }
}

async function onSubmit(): Promise<void> {
  if (!canSubmit.value || store.isSubmitting) return
  formError.value = null
  submitParseErrors.value = []
  submitFieldErrors.value = {}
  const result = await store.submit(props.crfOid)
  if (result.ok) {
    emit('update:open', false)
    emit('close')
  } else {
    submitFieldErrors.value = result.fieldErrors
    submitParseErrors.value = result.parseErrors
    formError.value = result.message ?? null
  }
}

function onCancel(): void {
  emit('update:open', false)
  emit('close')
}

/**
 * Phase E.6 — capture the live draft + mount the in-memory
 * {@link PreviewCrfEntryView} as an overlay so the operator can try
 * out the CRF without committing anything.
 *
 * <p>Available from every wizard step (the Review step's button is
 * the canonical entry point; future steps can wire to the same
 * handler). The preview is in-memory: closing the overlay drops the
 * preview values and leaves the authoring draft untouched.
 */
function onOpenLivePreview(): void {
  previewStore.load(store.draft, { crfName: props.crfName })
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="crf-authoring-heading"
    panel-class="max-w-4xl"
    @update:open="(v) => emit('update:open', v)"
    @close="onCancel"
  >
    <template #header>
      <div>
        <h2 id="crf-authoring-heading" class="text-base font-semibold tracking-tight">
          {{ t('crfLibrary.author.heading', { name: props.crfName }) }}
        </h2>
        <p class="text-[11px] text-slate-500 mt-0.5">
          {{ t('crfLibrary.author.subheading') }}
        </p>
      </div>
    </template>

    <div class="flex gap-4 min-h-[24rem]">
      <!-- Side rail -->
      <nav class="w-44 shrink-0 border-r border-slate-200 pr-3 -ml-1">
        <ul class="space-y-1 text-xs">
          <li>
            <button
              type="button"
              class="block w-full text-left px-2.5 py-1.5 rounded-md hover:bg-slate-50"
              :class="currentStep === 'metadata' ? 'bg-muw-blue/10 text-muw-blue font-medium' : 'text-slate-700'"
              @click="goToStep('metadata')"
            >
              {{ t('crfLibrary.author.rail.metadata') }}
            </button>
          </li>
          <li>
            <button
              type="button"
              class="block w-full text-left px-2.5 py-1.5 rounded-md hover:bg-slate-50"
              :class="currentStep === 'sections' ? 'bg-muw-blue/10 text-muw-blue font-medium' : 'text-slate-700'"
              @click="goToStep('sections')"
            >
              {{ t('crfLibrary.author.rail.sections') }}
            </button>
          </li>
          <li>
            <button
              type="button"
              class="block w-full text-left px-2.5 py-1.5 rounded-md hover:bg-slate-50"
              :class="currentStep === 'review' ? 'bg-muw-blue/10 text-muw-blue font-medium' : 'text-slate-700'"
              @click="goToStep('review')"
            >
              {{ t('crfLibrary.author.rail.review') }}
            </button>
          </li>
        </ul>
        <div class="mt-4 rounded-md border border-amber-200 bg-amber-50 p-2 text-[11px] text-amber-900 leading-snug">
          {{ t('crfLibrary.author.scopeNote') }}
        </div>
      </nav>

      <!-- Main panel -->
      <section class="flex-1 min-w-0 space-y-4">
        <!-- Metadata step -->
        <div v-if="currentStep === 'metadata'" class="space-y-4">
          <h3 class="text-sm font-semibold">{{ t('crfLibrary.author.metadata.heading') }}</h3>
          <div>
            <FieldLabel for="crf-author-vname" required>
              {{ t('crfLibrary.versionName') }}
            </FieldLabel>
            <TextInput
              id="crf-author-vname"
              :model-value="store.draft.versionName"
              :error="submitFieldErrors.versionName != null"
              @update:model-value="(v: string) => store.setVersionName(v)"
            />
            <ErrorText v-if="submitFieldErrors.versionName">{{ submitFieldErrors.versionName }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="crf-author-vdesc">{{ t('crfLibrary.versionDescription') }}</FieldLabel>
            <TextInput
              id="crf-author-vdesc"
              :model-value="store.draft.versionDescription"
              @update:model-value="(v: string) => store.setVersionDescription(v)"
            />
          </div>
          <div>
            <FieldLabel for="crf-author-vrev">{{ t('crfLibrary.revisionNotes') }}</FieldLabel>
            <TextInput
              id="crf-author-vrev"
              :model-value="store.draft.revisionNotes"
              @update:model-value="(v: string) => store.setMetadata({ revisionNotes: v })"
            />
          </div>
        </div>

        <!-- Sections step -->
        <div v-if="currentStep === 'sections'" class="space-y-5">
          <div class="flex items-center justify-between">
            <h3 class="text-sm font-semibold">{{ t('crfLibrary.author.sections.heading') }}</h3>
            <button
              type="button"
              class="text-xs text-muw-blue hover:underline"
              data-testid="crf-author-add-section"
              @click="onAddSection"
            >{{ t('crfLibrary.author.addSection') }}</button>
          </div>

          <draggable
            v-model="sectionList"
            item-key="uid"
            handle=".section-drag-handle"
            ghost-class="opacity-50"
            class="space-y-5"
            data-testid="crf-author-sections-dragroot"
          >
            <template #item="{ element: section, index: sIdx }">
              <div
                class="rounded-md border border-slate-200 bg-white p-3"
                :data-testid="`crf-author-section-${sIdx}`"
              >
                <div class="flex items-start justify-between gap-2 mb-2">
                  <button
                    type="button"
                    class="section-drag-handle inline-flex items-center text-[11px] text-slate-400 hover:text-slate-600 cursor-grab"
                    :aria-label="t('crfLibrary.author.dragSection')"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                      <circle cx="9" cy="6" r="1.2" fill="currentColor" />
                      <circle cx="15" cy="6" r="1.2" fill="currentColor" />
                      <circle cx="9" cy="12" r="1.2" fill="currentColor" />
                      <circle cx="15" cy="12" r="1.2" fill="currentColor" />
                      <circle cx="9" cy="18" r="1.2" fill="currentColor" />
                      <circle cx="15" cy="18" r="1.2" fill="currentColor" />
                    </svg>
                  </button>
                  <button
                    v-if="sectionList.length > 1"
                    type="button"
                    class="text-[11px] text-rose-600 hover:underline"
                    @click="onRemoveSection(sIdx)"
                  >{{ t('common.remove') }}</button>
                </div>

                <div class="grid grid-cols-2 gap-3">
                  <div>
                    <FieldLabel :for="`crf-author-slabel-${sIdx}`" required>
                      {{ t('crfLibrary.author.sectionLabel') }}
                    </FieldLabel>
                    <TextInput
                      :id="`crf-author-slabel-${sIdx}`"
                      v-model="section.label"
                    />
                  </div>
                  <div>
                    <FieldLabel :for="`crf-author-stitle-${sIdx}`" required>
                      {{ t('crfLibrary.author.sectionTitle') }}
                    </FieldLabel>
                    <TextInput
                      :id="`crf-author-stitle-${sIdx}`"
                      v-model="section.title"
                    />
                  </div>
                  <div class="col-span-2">
                    <FieldLabel :for="`crf-author-sinstr-${sIdx}`">
                      {{ t('crfLibrary.author.sectionInstructions') }}
                    </FieldLabel>
                    <TextInput
                      :id="`crf-author-sinstr-${sIdx}`"
                      v-model="section.instructions"
                    />
                  </div>
                </div>

                <div class="mt-4">
                  <div class="flex items-center justify-between mb-2">
                    <h4 class="text-xs font-semibold text-slate-700">
                      {{ t('crfLibrary.author.itemsHeading') }}
                    </h4>
                    <button
                      type="button"
                      class="text-xs text-muw-blue hover:underline"
                      :data-testid="`crf-author-add-item-${sIdx}`"
                      @click="onAddItem(sIdx)"
                    >{{ t('crfLibrary.author.addItem') }}</button>
                  </div>
                  <p v-if="section.items.length === 0" class="text-xs italic text-slate-500">
                    {{ t('crfLibrary.author.itemsEmpty') }}
                  </p>
                  <draggable
                    :model-value="section.items"
                    item-key="uid"
                    handle=".item-drag-handle"
                    ghost-class="opacity-50"
                    class="space-y-3"
                    :data-testid="`crf-author-items-dragroot-${sIdx}`"
                    @update:model-value="(next: typeof section.items) => onItemsReorder(sIdx, next)"
                  >
                    <template #item="{ element: item, index: iIdx }">
                      <div class="relative">
                        <button
                          type="button"
                          class="item-drag-handle absolute left-1 top-2 text-[11px] text-slate-400 hover:text-slate-600 cursor-grab"
                          :aria-label="t('crfLibrary.author.dragItem')"
                        >
                          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                            <circle cx="9" cy="6" r="1.2" fill="currentColor" />
                            <circle cx="15" cy="6" r="1.2" fill="currentColor" />
                            <circle cx="9" cy="12" r="1.2" fill="currentColor" />
                            <circle cx="15" cy="12" r="1.2" fill="currentColor" />
                            <circle cx="9" cy="18" r="1.2" fill="currentColor" />
                            <circle cx="15" cy="18" r="1.2" fill="currentColor" />
                          </svg>
                        </button>
                        <ItemEditor
                          :item="item"
                          :sections="sectionList"
                          :available-response-sets="store.responseSetCatalog"
                          :id-prefix="`crf-author-${sIdx}-${iIdx}`"
                          @remove="onRemoveItem(sIdx, iIdx)"
                        />
                      </div>
                    </template>
                  </draggable>
                </div>
              </div>
            </template>
          </draggable>
        </div>

        <!-- Review step -->
        <div v-if="currentStep === 'review'" class="space-y-3 text-xs">
          <h3 class="text-sm font-semibold">{{ t('crfLibrary.author.review.heading') }}</h3>
          <p class="text-slate-600 leading-relaxed">
            {{ t('crfLibrary.author.review.intro') }}
          </p>
          <dl class="rounded-md border border-slate-200 bg-white p-3 space-y-1">
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.crfName') }}</dt>
              <dd class="flex-1 font-mono">{{ props.crfName }}</dd>
            </div>
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.versionName') }}</dt>
              <dd class="flex-1 font-mono">{{ store.draft.versionName || '—' }}</dd>
            </div>
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.author.review.sectionCount') }}</dt>
              <dd class="flex-1">{{ store.draft.sections.length }}</dd>
            </div>
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.author.review.itemCount') }}</dt>
              <dd class="flex-1">{{ store.draft.sections.reduce((acc, s) => acc + s.items.length, 0) }}</dd>
            </div>
          </dl>

          <!-- Preview button + summary / errors -->
          <div class="flex items-center gap-2 flex-wrap">
            <button
              type="button"
              class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700 disabled:opacity-50"
              :disabled="store.isPreviewing"
              data-testid="crf-author-preview"
              @click="onPreview"
            >{{ store.isPreviewing ? t('common.saving') : t('crfAuthoring.preview.button') }}</button>
            <!-- Phase E.6 — Live preview: opens the in-memory entry
                 view as an overlay so the operator can test the CRF
                 draft as if entering data, without persistence. -->
            <button
              type="button"
              class="px-3 py-1.5 text-xs border border-muw-blue text-muw-blue rounded-md bg-white hover:bg-muw-blue/10 disabled:opacity-50"
              :disabled="!canSubmit"
              data-testid="crf-author-live-preview"
              @click="onOpenLivePreview"
            >{{ t('crfPreview.openButton') }}</button>
            <StatusPill v-if="previewSummary" variant="success">
              {{ t('crfAuthoring.preview.success', {
                sections: previewSummary.sectionCount,
                items: previewSummary.itemCount,
              }) }}
            </StatusPill>
          </div>

          <div
            v-if="Object.keys(previewFieldErrors).length > 0"
            class="rounded-md border border-rose-200 bg-rose-50 p-2 text-[11px] text-rose-800"
            data-testid="crf-author-preview-field-errors"
          >
            <div class="font-medium mb-1">{{ t('crfAuthoring.preview.errorsTitle') }}</div>
            <ul class="list-disc pl-4 space-y-0.5">
              <li v-for="(msg, field) in previewFieldErrors" :key="field">
                <span class="font-mono">{{ field }}</span>: {{ msg }}
              </li>
            </ul>
          </div>
          <div v-if="previewParseErrors.length > 0" class="rounded-md border border-rose-200 bg-rose-50 p-2 text-[11px] text-rose-800">
            <div class="font-medium mb-1">{{ t('crfLibrary.author.review.parseErrors', { count: previewParseErrors.length }) }}</div>
            <ul class="list-disc pl-4 space-y-0.5">
              <li v-for="(msg, i) in previewParseErrors" :key="i">{{ msg }}</li>
            </ul>
          </div>
          <ErrorText v-if="previewError">{{ previewError }}</ErrorText>

          <div v-if="!canSubmit" class="rounded-md border border-amber-200 bg-amber-50 p-2 text-[11px] text-amber-900">
            {{ t('crfLibrary.author.review.incomplete') }}
          </div>
          <div v-if="submitParseErrors.length > 0" class="rounded-md border border-rose-200 bg-rose-50 p-2 text-[11px] text-rose-800">
            <div class="font-medium mb-1">{{ t('crfLibrary.author.review.parseErrors', { count: submitParseErrors.length }) }}</div>
            <ul class="list-disc pl-4 space-y-0.5">
              <li v-for="(msg, i) in submitParseErrors" :key="i">{{ msg }}</li>
            </ul>
          </div>
          <ErrorText v-if="formError">{{ formError }}</ErrorText>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="flex items-center gap-2">
        <StatusPill v-if="store.isSubmitting" variant="neutral">
          {{ t('common.saving') }}
        </StatusPill>
      </div>
      <div class="flex items-center gap-2">
        <button
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="onCancel"
        >{{ t('common.cancel') }}</button>
        <button
          v-if="currentStep !== 'review'"
          class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700"
          @click="goToStep(currentStep === 'metadata' ? 'sections' : 'review')"
        >{{ t('crfLibrary.author.next') }}</button>
        <button
          v-if="currentStep === 'review'"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="!canSubmit || store.isSubmitting"
          @click="onSubmit"
        >{{ store.isSubmitting ? t('common.saving') : t('crfLibrary.author.submit') }}</button>
      </div>
    </template>
  </Modal>

  <!-- Phase E.6 — Live preview overlay. Mounted as a sibling of the
       Modal so its z-50 stacking sits cleanly on top of the wizard.
       The overlay reads from useCrfPreviewStore; closing the overlay
       calls store.close() and leaves the wizard draft untouched. -->
  <PreviewCrfEntryView as-overlay />
</template>

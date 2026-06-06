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
import {
  OPHTH_PRESET_CATALOG,
  generateOphthSectionItems,
} from '@/types/ophthPreset'
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
 * Ophthalmology bilateral preset picker — triggered by typing
 * {@code OPHTH_EXAM} or {@code OPHTHA_EXAM} (case-insensitive) as a
 * section label and pressing Shift+Enter.
 *
 * <p>Opens a modal listing every entry in {@link OPHTH_PRESET_CATALOG}
 * with a checkbox. On confirm, the generator produces paired OD / OS
 * items and REPLACES the trigger section's items + clears the magic
 * label so the resulting section reads as a normal OPHTH_EXAM
 * section (titled by the i18n preset). If the picker is opened
 * without a trigger section (defensive null case), a new section is
 * appended instead. The picker resets on each open so prior
 * selections don't survive cancel.
 */
const OPHTH_TRIGGER_LABELS = ['OPHTH_EXAM', 'OPHTHA_EXAM']
const ophthPickerOpen = ref(false)
const ophthSelection = ref<Set<string>>(new Set())
const ophthTriggerSectionIndex = ref<number | null>(null)

function isOphthTrigger(value: string): boolean {
  const normalised = value.trim().toUpperCase()
  return OPHTH_TRIGGER_LABELS.includes(normalised)
}

function openOphthPickerForSection(sectionIndex: number): void {
  ophthSelection.value = new Set()
  ophthTriggerSectionIndex.value = sectionIndex
  ophthPickerOpen.value = true
}

function onSectionLabelKeydown(sectionIndex: number, event: KeyboardEvent): void {
  if (!event.shiftKey || event.key !== 'Enter') return
  const section = store.draft.sections[sectionIndex]
  if (!section || !isOphthTrigger(section.label)) return
  event.preventDefault()
  openOphthPickerForSection(sectionIndex)
}

function closeOphthPicker(): void {
  ophthPickerOpen.value = false
  ophthTriggerSectionIndex.value = null
}

function toggleOphthEntry(key: string): void {
  const next = new Set(ophthSelection.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  ophthSelection.value = next
}

function selectAllOphth(): void {
  ophthSelection.value = new Set(OPHTH_PRESET_CATALOG.map((e) => e.key))
}

function clearAllOphth(): void {
  ophthSelection.value = new Set()
}

const ophthSelectedCount = computed(() => ophthSelection.value.size)

function confirmOphthPicker(): void {
  if (ophthSelection.value.size === 0) return
  // Preserve catalog order — the picker keys are checkbox-driven so a
  // {@code Set} loses the natural ordering; iterate the catalog instead.
  const ordered = OPHTH_PRESET_CATALOG
    .map((e) => e.key)
    .filter((k) => ophthSelection.value.has(k))
  const items = generateOphthSectionItems(ordered, (key) => t(key))
  store.addOphthPresetSection({
    items,
    title: t('ophthPreset.section.title'),
    instructions: t('ophthPreset.section.instructions'),
    replaceAtIndex: ophthTriggerSectionIndex.value ?? undefined,
  })
  closeOphthPicker()
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

/**
 * Phase E.6 polish — expand/collapse state for sections + items in
 * the Sections step.
 *
 * <p>Operators were getting overwhelmed by the fully-expanded editor
 * tree, especially during reorder; a 10-item section produced 10
 * tall ItemEditor blocks and the drag handles were spread across
 * pages of scroll. The new collapse model:
 *
 * <ul>
 *   <li>Sections default to collapsed; items default to collapsed.</li>
 *   <li>A freshly added section / item is auto-expanded so the
 *       operator can type into the fields immediately.</li>
 *   <li>Clicking the chevron toggles. State is component-local
 *       (Set&lt;string&gt; keyed off the stable {@code uid}); we don't
 *       persist across reloads because the wizard's draft is itself
 *       a session-scoped Pinia store.</li>
 *   <li>On {@code reset} (a fresh wizard open) we clear the sets so
 *       the next session starts from a clean baseline.</li>
 * </ul>
 *
 * <p>Drag-and-drop still works on collapsed rows — the drag handle
 * is on the header, not the body, so the operator can reorder
 * without expanding anything.
 */
const expandedSections = ref<Set<string>>(new Set())
const expandedItems = ref<Set<string>>(new Set())

function isSectionExpanded(uid: string): boolean {
  return expandedSections.value.has(uid)
}
function isItemExpanded(uid: string): boolean {
  return expandedItems.value.has(uid)
}
function toggleSection(uid: string): void {
  const next = new Set(expandedSections.value)
  if (next.has(uid)) next.delete(uid)
  else next.add(uid)
  expandedSections.value = next
}
function toggleItem(uid: string): void {
  const next = new Set(expandedItems.value)
  if (next.has(uid)) next.delete(uid)
  else next.add(uid)
  expandedItems.value = next
}

/** Reset expansion sets whenever the wizard opens — see {@link store.reset}. */
watch(
  () => props.open,
  (next) => {
    if (next) {
      expandedSections.value = new Set()
      expandedItems.value = new Set()
    }
  },
)

/**
 * Wrapped action handlers — keep the original behaviour but
 * auto-expand the new section / item so the operator sees fields
 * immediately. The section auto-expand also makes the newly-added
 * item's chevron actually visible.
 */
function onAddSectionAndExpand(): void {
  onAddSection()
  // The store appends to the end of draft.sections, so reach back
  // and grab the new uid.
  const last = store.draft.sections[store.draft.sections.length - 1]
  if (last) {
    expandedSections.value = new Set([...expandedSections.value, last.uid])
  }
}
function onAddItemAndExpand(sectionIndex: number): void {
  onAddItem(sectionIndex)
  const section = store.draft.sections[sectionIndex]
  if (section) {
    // Make sure the section is visible so the new item's chevron
    // isn't hidden behind a collapsed parent.
    expandedSections.value = new Set([...expandedSections.value, section.uid])
    const last = section.items[section.items.length - 1]
    if (last) {
      expandedItems.value = new Set([...expandedItems.value, last.uid])
    }
  }
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
            <div class="flex items-center gap-3">
              <button
                type="button"
                class="text-xs text-muw-blue hover:underline"
                data-testid="crf-author-add-section"
                @click="onAddSectionAndExpand"
              >{{ t('crfLibrary.author.addSection') }}</button>
            </div>
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
                class="rounded-md border border-slate-200 bg-white"
                :data-testid="`crf-author-section-${sIdx}`"
              >
                <!-- Phase E.6 polish — section header with chevron
                     toggle. Drag handle stays here so reorder works
                     on collapsed rows. Body below is v-show'd off
                     the per-uid expansion set. -->
                <div class="flex items-center gap-2 px-3 py-2 border-b border-slate-200" :class="{ 'border-b-0': !isSectionExpanded(section.uid) }">
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
                    type="button"
                    class="flex items-center gap-2 flex-1 min-w-0 text-left"
                    :aria-expanded="isSectionExpanded(section.uid)"
                    :aria-controls="`crf-author-section-body-${sIdx}`"
                    :data-testid="`crf-author-section-toggle-${sIdx}`"
                    @click="toggleSection(section.uid)"
                  >
                    <svg
                      width="12"
                      height="12"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      class="text-slate-500 transition-transform"
                      :class="{ 'rotate-90': isSectionExpanded(section.uid) }"
                      aria-hidden="true"
                    >
                      <polyline points="9 6 15 12 9 18" />
                    </svg>
                    <span class="text-xs font-medium text-slate-800 truncate">
                      {{ section.title.trim() || section.label.trim() || t('crfLibrary.author.sectionUntitled') }}
                    </span>
                    <span class="text-[10px] text-slate-500">
                      {{ t('crfLibrary.author.sectionItemCount', { n: section.items.length }) }}
                    </span>
                  </button>
                  <button
                    v-if="sectionList.length > 1"
                    type="button"
                    class="text-[11px] text-rose-600 hover:underline"
                    @click="onRemoveSection(sIdx)"
                  >{{ t('common.remove') }}</button>
                </div>

                <div
                  v-show="isSectionExpanded(section.uid)"
                  :id="`crf-author-section-body-${sIdx}`"
                  class="p-3 transition-all"
                >
                <div class="grid grid-cols-2 gap-3">
                  <div @keydown="onSectionLabelKeydown(sIdx, $event)">
                    <FieldLabel :for="`crf-author-slabel-${sIdx}`" required>
                      {{ t('crfLibrary.author.sectionLabel') }}
                    </FieldLabel>
                    <TextInput
                      :id="`crf-author-slabel-${sIdx}`"
                      v-model="section.label"
                    />
                    <p class="mt-1 text-[10px] text-slate-400">
                      {{ t('ophthPreset.triggerHint') }}
                    </p>
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
                      @click="onAddItemAndExpand(sIdx)"
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
                      <div
                        class="rounded-md border border-slate-200 bg-white overflow-hidden"
                        :data-testid="`crf-author-item-wrapper-${sIdx}-${iIdx}`"
                      >
                        <!-- Phase E.6 polish — item header w/ drag
                             handle + chevron + name + datatype chip.
                             Header stays visible when collapsed so
                             reorder works without expanding rows. -->
                        <div class="flex items-center gap-2 px-2 py-1.5 bg-slate-50 border-b border-slate-200" :class="{ 'border-b-0': !isItemExpanded(item.uid) }">
                          <button
                            type="button"
                            class="item-drag-handle inline-flex items-center text-[11px] text-slate-400 hover:text-slate-600 cursor-grab"
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
                          <button
                            type="button"
                            class="flex items-center gap-2 flex-1 min-w-0 text-left"
                            :aria-expanded="isItemExpanded(item.uid)"
                            :aria-controls="`crf-author-item-body-${sIdx}-${iIdx}`"
                            :data-testid="`crf-author-item-toggle-${sIdx}-${iIdx}`"
                            @click="toggleItem(item.uid)"
                          >
                            <svg
                              width="10"
                              height="10"
                              viewBox="0 0 24 24"
                              fill="none"
                              stroke="currentColor"
                              stroke-width="2"
                              class="text-slate-500 transition-transform"
                              :class="{ 'rotate-90': isItemExpanded(item.uid) }"
                              aria-hidden="true"
                            >
                              <polyline points="9 6 15 12 9 18" />
                            </svg>
                            <span class="text-xs font-medium text-slate-700 truncate">
                              {{ item.name.trim() || t('crfLibrary.author.itemUnnamed') }}
                            </span>
                            <span class="text-[10px] px-1.5 py-0.5 rounded bg-white border border-slate-200 text-slate-500 font-mono">
                              {{ item.dataType }}
                            </span>
                            <span v-if="item.required" class="text-[10px] text-rose-600">*</span>
                          </button>
                        </div>
                        <div
                          v-show="isItemExpanded(item.uid)"
                          :id="`crf-author-item-body-${sIdx}-${iIdx}`"
                          class="transition-all"
                          :data-testid="`crf-author-item-body-${sIdx}-${iIdx}`"
                        >
                          <ItemEditor
                            :item="item"
                            :sections="sectionList"
                            :available-response-sets="store.responseSetCatalog"
                            :id-prefix="`crf-author-${sIdx}-${iIdx}`"
                            @remove="onRemoveItem(sIdx, iIdx)"
                          />
                        </div>
                      </div>
                    </template>
                  </draggable>
                </div>
                </div><!-- /section body -->
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

  <!-- Ophthalmology bilateral preset picker -->
  <Modal
    :open="ophthPickerOpen"
    labelled-by="ophth-preset-heading"
    panel-class="max-w-2xl"
    @update:open="(v) => (ophthPickerOpen = v)"
    @close="closeOphthPicker"
  >
    <template #header>
      <div>
        <h2 id="ophth-preset-heading" class="text-base font-semibold tracking-tight">
          {{ t('ophthPreset.picker.heading') }}
        </h2>
        <p class="text-[11px] text-slate-500 mt-0.5">
          {{ t('ophthPreset.picker.subheading') }}
        </p>
      </div>
    </template>

    <div class="space-y-3">
      <div class="flex items-center justify-between text-[11px]">
        <span class="text-slate-500">
          {{ t('ophthPreset.picker.selectedCount', { count: ophthSelectedCount }) }}
        </span>
        <div class="flex items-center gap-3">
          <button
            type="button"
            class="text-muw-blue hover:underline"
            data-testid="ophth-preset-select-all"
            @click="selectAllOphth"
          >{{ t('ophthPreset.picker.selectAll') }}</button>
          <button
            type="button"
            class="text-slate-600 hover:underline"
            data-testid="ophth-preset-clear-all"
            @click="clearAllOphth"
          >{{ t('ophthPreset.picker.clearAll') }}</button>
        </div>
      </div>

      <ul
        class="max-h-96 overflow-y-auto rounded-md border border-slate-200 divide-y divide-slate-100"
        data-testid="ophth-preset-list"
      >
        <li
          v-for="entry in OPHTH_PRESET_CATALOG"
          :key="entry.key"
          class="flex items-start gap-3 px-3 py-2 hover:bg-slate-50"
        >
          <input
            :id="`ophth-preset-${entry.key}`"
            type="checkbox"
            class="mt-0.5"
            :checked="ophthSelection.has(entry.key)"
            :data-testid="`ophth-preset-checkbox-${entry.key}`"
            @change="toggleOphthEntry(entry.key)"
          />
          <label :for="`ophth-preset-${entry.key}`" class="flex-1 text-xs cursor-pointer">
            <div class="font-medium text-slate-800">
              {{ t(entry.labelKey) }}
            </div>
            <div class="text-[11px] text-slate-500 mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5">
              <span class="font-mono">{{ entry.dataType }}</span>
              <span v-if="entry.range">
                {{ t('ophthPreset.picker.range', { min: entry.range.min, max: entry.range.max }) }}
              </span>
              <span v-if="entry.unit">{{ entry.unit }}</span>
            </div>
          </label>
        </li>
      </ul>

      <p class="text-[11px] text-slate-500 leading-snug">
        {{ t('ophthPreset.picker.lateralityHint') }}
      </p>
    </div>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="closeOphthPicker"
        >{{ t('common.cancel') }}</button>
        <button
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="ophthSelectedCount === 0"
          data-testid="ophth-preset-confirm"
          @click="confirmOphthPicker"
        >{{ t('ophthPreset.picker.confirm', { count: ophthSelectedCount }) }}</button>
      </div>
    </template>
  </Modal>

  <!-- Phase E.6 — Live preview overlay. Teleported to document.body so
       its `position: fixed` + z-[60] escape the wizard's Modal
       stacking context (the wizard itself is a Modal at z-50; without
       Teleport the preview rendered behind the wizard content + was
       not interactable). The overlay reads from useCrfPreviewStore;
       closing the overlay calls store.close() and leaves the wizard
       draft untouched. -->
  <Teleport to="body">
    <PreviewCrfEntryView as-overlay />
  </Teleport>
</template>

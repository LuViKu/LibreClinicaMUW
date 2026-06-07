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
  type AuthoringSection,
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
  const section = store.draft.sections[sectionIndex]
  if (section?.bilateral) {
    // Bilateral mode: append a paired OD/OS row (two items) so the
    // operator sees one new row spanning both eye columns rather than
    // an OD-only orphan.
    store.addBilateralPair(sectionIndex)
  } else {
    store.addItem(sectionIndex)
  }
}

function onRemoveItem(sectionIndex: number, itemIndex: number): void {
  store.removeItem(sectionIndex, itemIndex)
}

/**
 * Phase E.6 ophth-bilateral wizard layout — remove an item by its uid
 * (the bilateral grid doesn't carry the flat index that
 * {@link onRemoveItem} expects, so we resolve the index here).
 */
function onRemoveItemByUid(sectionIndex: number, uid: string): void {
  const section = store.draft.sections[sectionIndex]
  if (!section) return
  const itemIndex = section.items.findIndex((it) => it.uid === uid)
  if (itemIndex < 0) return
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

/**
 * Flatten a {@link BilateralAuthoringRow} array back to a linear
 * {@link AuthoringItem} list, preserving the order rows appear in the grid.
 * Within each row OD comes before OS so that {@link bilateralRowsForSection}
 * (which establishes row position from first-seen item per suffix) reconstructs
 * the same row order on the next render.
 */
function flattenBilateralRows(rows: BilateralAuthoringRow[]): AuthoringItem[] {
  const out: AuthoringItem[] = []
  for (const row of rows) {
    if (row.kind === 'compound-bilateral') {
      for (const sub of row.subFields ?? []) {
        if (sub.od) out.push(sub.od)
        if (sub.os) out.push(sub.os)
      }
    } else if (row.kind === 'bilateral') {
      if (row.od) out.push(row.od)
      if (row.os) out.push(row.os)
    } else if (row.kind === 'both-eyes') {
      if (row.bothEyes) out.push(row.bothEyes)
    } else if (row.kind === 'single') {
      if (row.single) out.push(row.single)
    }
  }
  return out
}

function onBilateralRowsReorder(sectionIndex: number, rows: BilateralAuthoringRow[]): void {
  store.reorderItems(sectionIndex, flattenBilateralRows(rows))
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
 * Phase E.6 — bilateral row grouping for the OPHTH_EXAM section in the
 * authoring editor.
 *
 * <p>Mirrors the runtime {@code groupBilateralItems} helper
 * ({@code components/bilateral.ts}) but operates on {@link AuthoringItem}
 * (which uses {@code name}/{@code descriptionLabel} where the runtime
 * shape uses {@code label}). The output is consumed by the
 * v-if="section.label === 'OPHTH_EXAM'" branch of the items rendering
 * block; the row layout puts OD on the LEFT and OS on the RIGHT to
 * match the clinician-facing convention (see [[reference_ophth_laterality]]).
 *
 * <p>Items that don't match the {@code (OD|OS|OU)_<suffix>} prefix
 * fall through as full-width single-cell rows, so an operator who
 * manually adds a non-bilateral item to an OPHTH_EXAM section still
 * sees their item rendered.
 */
interface BilateralAuthoringRow {
  kind: 'bilateral' | 'both-eyes' | 'single' | 'compound-bilateral'
  /** Stable key for v-for + the per-row expand toggle. Shared OID suffix or item OID. */
  key: string
  /** Human-readable row label, e.g. "BCVA letters". */
  label: string
  /** Right eye (renders LEFT in the OPHTH_EXAM grid). null if no OD item for this row. */
  od: AuthoringItem | null
  /** Left eye (renders RIGHT in the grid). null if no OS item. */
  os: AuthoringItem | null
  /** OU item ("both eyes") spans the entire row width. null for {@code 'bilateral'} rows. */
  bothEyes: AuthoringItem | null
  /** For {@code 'single'} rows — the original item that didn't match the OD_/OS_/OU_ prefix. */
  single: AuthoringItem | null
  /**
   * For {@code 'compound-bilateral'} rows — the sub-fields (e.g. Sphere,
   * Torus, Angle, Visus for refraction). Each sub-field has OD + OS slots
   * + a compact label for the in-row preview.
   */
  subFields?: Array<{
    subKey: string
    compactLabel: string
    od: AuthoringItem | null
    os: AuthoringItem | null
  }>
}

const WIZARD_COMPOUND_REGISTRY: Record<
  string,
  { mainLabel: string; compactBySubKey: Record<string, string> }
> = {
  REFRACTION: {
    mainLabel: 'Refraction',
    compactBySubKey: {
      SPHERE: 'Sph',
      TORUS: 'Tor',
      CYLINDER: 'Tor',
      ANGLE: 'Ang',
      AXIS: 'Ang',
      VISUS: 'Vis',
    },
  },
}

function parseWizardCompoundSuffix(suffix: string): { prefix: string; subKey: string } | null {
  for (const prefix of Object.keys(WIZARD_COMPOUND_REGISTRY)) {
    if (suffix.startsWith(`${prefix}_`)) {
      return { prefix, subKey: suffix.slice(prefix.length + 1) }
    }
  }
  return null
}

const EYE_PREFIX_RE = /^(OD|OS|OU)_(.+)$/

function formatCompoundSubLabels(
  subFields: BilateralAuthoringRow['subFields'],
): string {
  return (subFields ?? []).map((s) => s.compactLabel).join(' · ')
}

function stripWizardEyeMarker(label: string): string {
  return label
    .replace(/^\s*(OD|OS|OU)\s*[—\-:_]?\s+/i, '')
    .replace(/\s*[—\-:(]?\s*(OD|OS|OU)\s*\)?\s*$/i, '')
    .trim()
}

function bilateralRowsForSection(section: AuthoringSection): BilateralAuthoringRow[] {
  const rows: BilateralAuthoringRow[] = []
  const indexBySuffix = new Map<string, number>()
  const indexByCompound = new Map<string, number>()
  for (const item of section.items) {
    const m = EYE_PREFIX_RE.exec(item.oid)
    if (!m) {
      rows.push({
        kind: 'single',
        key: item.uid,
        label: item.descriptionLabel || item.name || item.oid,
        od: null,
        os: null,
        bothEyes: null,
        single: item,
      })
      continue
    }
    const eye = m[1] as 'OD' | 'OS' | 'OU'
    const suffix = m[2]
    const compound = parseWizardCompoundSuffix(suffix)
    if (compound && eye !== 'OU') {
      const compoundIdx = indexByCompound.get(compound.prefix)
      const meta = WIZARD_COMPOUND_REGISTRY[compound.prefix]!
      const compactLabel = meta.compactBySubKey[compound.subKey] ?? compound.subKey
      if (compoundIdx === undefined) {
        rows.push({
          kind: 'compound-bilateral',
          key: compound.prefix,
          label: meta.mainLabel,
          od: null, os: null, bothEyes: null, single: null,
          subFields: [{
            subKey: compound.subKey,
            compactLabel,
            od: eye === 'OD' ? item : null,
            os: eye === 'OS' ? item : null,
          }],
        })
        indexByCompound.set(compound.prefix, rows.length - 1)
      } else {
        const row = rows[compoundIdx]!
        const existingSub = row.subFields!.find((s) => s.subKey === compound.subKey)
        if (existingSub) {
          if (eye === 'OD' && !existingSub.od) existingSub.od = item
          else if (eye === 'OS' && !existingSub.os) existingSub.os = item
        } else {
          row.subFields!.push({
            subKey: compound.subKey,
            compactLabel,
            od: eye === 'OD' ? item : null,
            os: eye === 'OS' ? item : null,
          })
        }
      }
      continue
    }
    const existingIdx = indexBySuffix.get(suffix)
    const labelGuess = stripWizardEyeMarker(item.descriptionLabel || item.name || suffix) || suffix.replace(/_/g, ' ')
    if (existingIdx === undefined) {
      if (eye === 'OU') {
        rows.push({
          kind: 'both-eyes', key: suffix, label: labelGuess,
          od: null, os: null, bothEyes: item, single: null,
        })
      } else {
        rows.push({
          kind: 'bilateral', key: suffix, label: labelGuess,
          od: eye === 'OD' ? item : null,
          os: eye === 'OS' ? item : null,
          bothEyes: null, single: null,
        })
      }
      indexBySuffix.set(suffix, rows.length - 1)
      continue
    }
    const row = rows[existingIdx]!
    if (row.kind === 'bilateral' && eye === 'OD' && !row.od) row.od = item
    else if (row.kind === 'bilateral' && eye === 'OS' && !row.os) row.os = item
    else {
      // duplicate, mismatched, or OU joining a paired row — render as own row
      rows.push({
        kind: 'single', key: item.uid,
        label: item.descriptionLabel || item.name || item.oid,
        od: null, os: null, bothEyes: null, single: item,
      })
    }
  }
  return rows
}

/**
 * Phase E.6 ophth-bilateral — replaced the previous label-sniffing
 * helper with an explicit toggle. A section is rendered in the
 * OD-LEFT / OS-RIGHT grid IFF its {@code bilateral} flag is true.
 * The flag is wizard-only metadata (stripped from {@code buildPayload});
 * the runtime renderer keys off the {@code OD_} / {@code OS_} OID
 * prefixes on each item, so the wizard toggle is a presentation hint,
 * not a contract change.
 */
function isBilateralSection(section: AuthoringSection): boolean {
  return Boolean(section.bilateral)
}

/**
 * Bilateral row expansion: each row owns up to four AuthoringItems
 * (od / os / bothEyes / single). A row counts as expanded if ANY of
 * its items are expanded; toggling sets all present items to the
 * opposite of the current row state, so the two ItemEditors in a
 * bilateral row open + close together.
 */
function collectBilateralRowItems(row: BilateralAuthoringRow): AuthoringItem[] {
  const items: AuthoringItem[] = []
  if (row.od) items.push(row.od)
  if (row.os) items.push(row.os)
  if (row.bothEyes) items.push(row.bothEyes)
  if (row.single) items.push(row.single)
  if (row.subFields) {
    for (const sub of row.subFields) {
      if (sub.od) items.push(sub.od)
      if (sub.os) items.push(sub.os)
    }
  }
  return items
}

function isBilateralRowExpanded(row: BilateralAuthoringRow): boolean {
  return collectBilateralRowItems(row).some((it) => expandedItems.value.has(it.uid))
}

function onToggleBilateralRow(row: BilateralAuthoringRow): void {
  const items = collectBilateralRowItems(row)
  const expanded = isBilateralRowExpanded(row)
  const next = new Set(expandedItems.value)
  for (const it of items) {
    if (expanded) next.delete(it.uid)
    else next.add(it.uid)
  }
  expandedItems.value = next
}

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
                  <!-- Phase E.6 ophth-bilateral — explicit per-section toggle.
                       When on, the items list renders as the 3-column OD-LEFT /
                       OS-RIGHT grid with compound-row collapse for refraction
                       quartets; when off, the items render as a flat
                       draggable list. The flag is wizard-only metadata
                       (stripped from buildPayload). -->
                  <div class="col-span-2">
                    <label class="inline-flex items-center gap-2 text-xs text-slate-700 cursor-pointer">
                      <input
                        type="checkbox"
                        class="rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
                        :checked="Boolean(section.bilateral)"
                        :data-testid="`crf-author-bilateral-toggle-${sIdx}`"
                        @change="store.setSectionBilateral(sIdx, ($event.target as HTMLInputElement).checked)"
                      />
                      <span>{{ t('crfLibrary.author.bilateralToggle') }}</span>
                    </label>
                    <p class="mt-1 ml-6 text-[10px] text-slate-400">
                      {{ t('crfLibrary.author.bilateralToggleHint') }}
                    </p>
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

                  <!-- OPHTH_EXAM bilateral grid layout: each row shows the OD item
                       definition on the LEFT and the OS item definition on the RIGHT.
                       Per-pair chevron toggles both ItemEditors at once. Row-level
                       drag-reorder is allowed via the leading grip handle; OD/OS
                       items inside a row stay paired (they move as one unit because
                       reorder happens at the row, not the item, level). -->
                  <div
                    v-if="isBilateralSection(section)"
                    class="space-y-2"
                    :data-testid="`crf-author-bilateral-grid-${sIdx}`"
                  >
                    <!-- Sticky header row: OD (left) | OS (right) -->
                    <div class="grid grid-cols-[14rem_1fr_1fr] gap-2 px-2 text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                      <div></div>
                      <div>{{ t('ophthPreset.bilateral.headerOd') }}</div>
                      <div>{{ t('ophthPreset.bilateral.headerOs') }}</div>
                    </div>
                    <draggable
                      :model-value="bilateralRowsForSection(section)"
                      item-key="key"
                      handle=".bilateral-row-drag-handle"
                      ghost-class="opacity-50"
                      class="space-y-2"
                      :data-testid="`crf-author-bilateral-dragroot-${sIdx}`"
                      @update:model-value="(next: BilateralAuthoringRow[]) => onBilateralRowsReorder(sIdx, next)"
                    >
                      <template #item="{ element: row, index: rIdx }">
                    <div
                      :key="row.key"
                      class="rounded-md border border-slate-200 bg-white overflow-hidden"
                      :data-testid="`crf-author-bilateral-row-${sIdx}-${rIdx}`"
                    >
                      <!-- Row header: drag handle + label + per-pair expand toggle -->
                      <div class="grid grid-cols-[14rem_1fr_1fr] gap-2 items-center px-2 py-1.5 bg-slate-50 border-b border-slate-200">
                        <div class="flex items-center gap-1 min-w-0">
                          <button
                            type="button"
                            class="bilateral-row-drag-handle inline-flex items-center text-[11px] text-slate-400 hover:text-slate-600 cursor-grab"
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
                            class="flex items-center gap-1.5 text-left min-w-0"
                            :aria-expanded="isBilateralRowExpanded(row)"
                            @click="onToggleBilateralRow(row)"
                          >
                            <svg
                              width="10" height="10" viewBox="0 0 24 24" fill="none"
                              stroke="currentColor" stroke-width="2"
                              class="text-slate-500 transition-transform"
                              :class="{ 'rotate-90': isBilateralRowExpanded(row) }"
                              aria-hidden="true"
                            ><polyline points="9 6 15 12 9 18" /></svg>
                            <span class="text-xs font-medium text-slate-700 truncate">{{ row.label }}</span>
                          </button>
                        </div>
                        <template v-if="row.kind === 'compound-bilateral'">
                          <span class="col-span-2 text-[10px] text-slate-500 truncate">
                            {{ formatCompoundSubLabels(row.subFields) }}
                          </span>
                        </template>
                        <template v-else>
                          <span class="text-[10px] font-mono text-slate-500 truncate">
                            {{ row.od?.oid ?? (row.kind === 'both-eyes' ? row.bothEyes?.oid : '—') }}
                          </span>
                          <span class="text-[10px] font-mono text-slate-500 truncate">
                            {{ row.os?.oid ?? '—' }}
                          </span>
                        </template>
                      </div>
                      <!-- Expanded body: OD editor left, OS editor right -->
                      <div
                        v-if="isBilateralRowExpanded(row) && row.kind !== 'compound-bilateral'"
                        class="grid grid-cols-2 gap-3 p-3"
                      >
                        <div>
                          <ItemEditor
                            v-if="row.od"
                            :item="row.od"
                            :sections="sectionList"
                            :available-response-sets="store.responseSetCatalog"
                            :id-prefix="`crf-author-${sIdx}-od-${row.key}`"
                            @remove="onRemoveItemByUid(sIdx, row.od.uid)"
                          />
                          <div v-else-if="row.kind === 'both-eyes'" class="col-span-2 text-[11px] italic text-slate-400">
                            {{ t('ophthPreset.bilateral.bothEyesOnly') }}
                          </div>
                          <div v-else-if="row.kind === 'single'" class="col-span-2">
                            <ItemEditor
                              v-if="row.single"
                              :item="row.single"
                              :sections="sectionList"
                              :available-response-sets="store.responseSetCatalog"
                              :id-prefix="`crf-author-${sIdx}-single-${row.key}`"
                              @remove="onRemoveItemByUid(sIdx, row.single.uid)"
                            />
                          </div>
                          <div v-else class="text-[11px] italic text-slate-400">
                            {{ t('ophthPreset.bilateral.missingOd') }}
                          </div>
                        </div>
                        <div>
                          <ItemEditor
                            v-if="row.os"
                            :item="row.os"
                            :sections="sectionList"
                            :available-response-sets="store.responseSetCatalog"
                            :id-prefix="`crf-author-${sIdx}-os-${row.key}`"
                            @remove="onRemoveItemByUid(sIdx, row.os.uid)"
                          />
                          <div v-else-if="row.kind === 'bilateral'" class="text-[11px] italic text-slate-400">
                            {{ t('ophthPreset.bilateral.missingOs') }}
                          </div>
                        </div>
                      </div>
                      <!-- Expanded body for compound-bilateral rows
                           (e.g. refraction): one nested OD|OS pair per
                           sub-field, stacked vertically with a compact
                           sub-label on the left. -->
                      <div
                        v-else-if="isBilateralRowExpanded(row) && row.kind === 'compound-bilateral'"
                        class="space-y-3 p-3"
                        :data-testid="`crf-author-compound-body-${sIdx}-${rIdx}`"
                      >
                        <div
                          v-for="(sub, subIdx) in row.subFields ?? []"
                          :key="sub.subKey"
                          class="rounded border border-slate-200 bg-slate-50/60 p-2"
                          :data-testid="`crf-author-compound-sub-${sIdx}-${rIdx}-${subIdx}`"
                        >
                          <div class="mb-2 text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                            {{ sub.compactLabel }}
                          </div>
                          <div class="grid grid-cols-2 gap-3">
                            <div>
                              <ItemEditor
                                v-if="sub.od"
                                :item="sub.od"
                                :sections="sectionList"
                                :available-response-sets="store.responseSetCatalog"
                                :id-prefix="`crf-author-${sIdx}-od-${row.key}-${sub.subKey}`"
                                @remove="onRemoveItemByUid(sIdx, sub.od.uid)"
                              />
                              <div v-else class="text-[11px] italic text-slate-400">
                                {{ t('ophthPreset.bilateral.missingOd') }}
                              </div>
                            </div>
                            <div>
                              <ItemEditor
                                v-if="sub.os"
                                :item="sub.os"
                                :sections="sectionList"
                                :available-response-sets="store.responseSetCatalog"
                                :id-prefix="`crf-author-${sIdx}-os-${row.key}-${sub.subKey}`"
                                @remove="onRemoveItemByUid(sIdx, sub.os.uid)"
                              />
                              <div v-else class="text-[11px] italic text-slate-400">
                                {{ t('ophthPreset.bilateral.missingOs') }}
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                      </template>
                    </draggable>
                  </div>

                  <draggable
                    v-else
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

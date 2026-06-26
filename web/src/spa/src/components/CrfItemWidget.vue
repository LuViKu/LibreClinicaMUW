<script setup lang="ts">
/**
 * Per-item widget renderer. Renders inside a single-column row AND inside a
 * bilateral 3-column row (each eye's cell hosts an independent widget).
 * Deliberately dumb: reads modelValue from the caller, emits
 * {@code update:modelValue} on edit; the parent funnels it into the store.
 *
 * Boolean (legacy 'BL', dataType=11) renders as a Ja/Nein radio pair — a
 * single checkbox would conflate "Nein" with "unanswered", and the explicit
 * answer drives downstream show-when rules. Wire contract: `'1'` = Yes,
 * `'0'` = No, empty/null/undefined = unanswered.
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from './FieldLabel.vue'
import TextInput from './TextInput.vue'
import DateInput from './DateInput.vue'
import SelectInput from './SelectInput.vue'
import HelperText from './HelperText.vue'
import ErrorText from './ErrorText.vue'
import CheckboxArrayInput from './CheckboxArrayInput.vue'
import FileUploadInput from './FileUploadInput.vue'

import type { CrfItem } from '@/types/crf'
import { useOphthFieldCatalogStore } from '@/stores/ophthFieldCatalog'

interface Props {
  item: CrfItem
  modelValue: unknown
  errorMessage?: string | null
  disabled?: boolean
  fileBusy?: boolean
  maxFileBytes?: number
  fileExtensions?: string
  /**
   * When true, the surrounding layout already owns the row label
   * (e.g. the bilateral 3-column row prints the shared label in
   * its first column). Suppresses the per-widget {@code FieldLabel}
   * so the cell doesn't render a duplicate "OD …" line.
   */
  suppressLabel?: boolean
  /**
   * Compact 56×42 mini-input chrome for compound-bilateral sub-fields
   * (refraction Sph/Cyl/Axis/Vis). Numeric inputs drop the unit suffix +
   * stepper buttons so 4 sub-inputs fit on one eye-cell line.
   */
  compact?: boolean
  /**
   * For a conditional-reason input (catalog widget {@code text} + non-blank
   * {@code conditional_on_code}), the parent's current value drives three
   * visual states: parent matches show-when + reason empty → coral
   * "Grund erforderlich"; matches + filled → standard; doesn't match /
   * unanswered → grayed-out disabled with "Aktiv, sobald X gewählt ist".
   * Undefined leaves the widget inactive.
   */
  parentValue?: unknown
}

const props = withDefaults(defineProps<Props>(), {
  errorMessage: null,
  disabled: false,
  fileBusy: false,
  maxFileBytes: 0,
  fileExtensions: '',
  suppressLabel: false,
  compact: false,
  parentValue: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: unknown]
  /**
   * App-feedback Wave 1D (2026-06-19) — emitted by the TRISTATE_REASON
   * widget when the operator types into the conditional reason
   * textarea. The parent ({@link CrfEntryView}) routes the text into
   * the sibling reason item (resolved via the show-when discovery on
   * the schema side). Separate from {@code update:modelValue} because
   * the parent radio's wire value (Ja / Nein / Unbekannt token) is
   * persisted to a different item_data row than the reason text.
   */
  'update:tristate-reason': [value: string]
  'upload-file': [file: File]
  'clear-file': []
  'report-validation': [payload: { itemOid: string; errorMessage: string }]
}>()

const { t } = useI18n()

const inputId = computed(() => `item-${props.item.oid}`)
const hasError = computed(() => props.errorMessage != null)

/**
 * Phase E.6 ophth-field-catalog (2026-06-11): pull the matching
 * catalog entry for this item. The store is loaded once per session
 * via {@code useOphthFieldCatalogStore().load()} (mounted in main.ts
 * + replayed by CrfEntryView on first mount); the resolver matches
 * the item OID's de-lateralised tail against each catalog code.
 * Returns null when no match — render path falls back to the
 * OID-suffix heuristic below.
 */
const catalogStore = useOphthFieldCatalogStore()
const catalogEntry = computed(() => catalogStore.entryForOid(props.item.oid))

const textBindings = computed(() => ({
  id: inputId.value,
  modelValue: (props.modelValue == null ? '' : String(props.modelValue)) as string,
  error: hasError.value,
  'onUpdate:modelValue': (v: string) => emit('update:modelValue', v),
}))

function onNumberInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value
  emit('update:modelValue', raw === '' ? null : Number(raw))
}

const booleanRadioName = computed(() => `bl-radio-${props.item.oid}`)
const isBooleanYes = computed(() => props.modelValue === '1')
const isBooleanNo = computed(() => props.modelValue === '0')

/**
 * Tokens for the segmented yes/no on a non-boolean (select-one) item. Reads
 * the item's option codes so the wire value matches what the CRF persists
 * (e.g. {@code ja|Ja,nein|Nein}); falls back to canonical {@code '1'} /
 * {@code '0'} when no options are present.
 */
const yesNoYesToken = computed<string>(() => {
  const opts = props.item.options
  if (opts && opts.length > 0) return opts[0].code
  return '1'
})
const yesNoNoToken = computed<string>(() => {
  const opts = props.item.options
  if (opts && opts.length > 1) return opts[1].code
  return '0'
})
const isYesNoYes = computed(() => {
  if (props.modelValue == null || props.modelValue === '') return false
  return String(props.modelValue) === yesNoYesToken.value
})
const isYesNoNo = computed(() => {
  if (props.modelValue == null || props.modelValue === '') return false
  return String(props.modelValue) === yesNoNoToken.value
})

/**
 * Derive the ophthalmology presentation hint. Catalog-driven: a matching
 * entry's {@code widget} + {@code unit} drive rendering. Falls back to the
 * OID-suffix heuristic for non-catalogued items: {@code *_BCVA_LETTERS}
 * (Buchst.), {@code *_BCVA_LOGMAR}, {@code *_BCVA_SNELLEN}, {@code *_IOP}
 * (mmHg), {@code *_CRT} (µm), {@code *_ACD} (mm) → number-stepper/snellen;
 * {@code *_DONE} → segmented Ja/Nein; {@code *_DONE_REASON} → conditional.
 */
type OphthPresentation = {
  widget:
    | 'standard'
    | 'number-stepper'
    | 'snellen'
    | 'segmented-yesno'
    | 'conditional-reason'
    /**
     * Tri-state radio chip set (Ja/Nein/Unbekannt) backing the
     * {@code TRISTATE_REASON} authoring type; detected via the
     * {@code *_TRISTATE} OID suffix. Parent wire shape: select-one with
     * three options ({@code ja}/{@code nein}/{@code unbekannt} OR canonical
     * {@code 1}/{@code 0}/{@code 2}).
     */
    | 'tristate-radio'
  unit?: string
}
const ophthPresentation = computed<OphthPresentation>(() => {
  // Catalog-driven path (preferred). The catalog entry's widget value
  // maps 1:1 to our local presentation taxonomy:
  //   number-stepper → number-stepper (with unit)
  //   snellen        → snellen
  //   yesno          → segmented-yesno
  //   text + conditional_on_code → conditional-reason
  //   refraction / text / select-one → standard fall-through
  const entry = catalogEntry.value
  if (entry != null) {
    if (entry.widget === 'number-stepper') {
      return { widget: 'number-stepper', unit: entry.unit ?? undefined }
    }
    if (entry.widget === 'snellen') return { widget: 'snellen' }
    if (entry.widget === 'yesno') return { widget: 'segmented-yesno' }
    if (entry.widget === 'text' && entry.conditionalOnCode) {
      return { widget: 'conditional-reason' }
    }
    return { widget: 'standard' }
  }

  // Heuristic fallback — only used when the catalog is offline OR the
  // item's OID doesn't map to a catalog code (legacy CRFs, ad-hoc
  // items, non-ophth sections).
  const tail = (props.item.oid || '').toUpperCase()
  if (tail.endsWith('_BCVA_LETTERS') || tail.endsWith('BCVA_LETTERS')) {
    return { widget: 'number-stepper', unit: 'Buchst.' }
  }
  if (tail.endsWith('_BCVA_LOGMAR') || tail.endsWith('BCVA_LOGMAR')) {
    return { widget: 'number-stepper', unit: 'logMAR' }
  }
  if (tail.endsWith('_BCVA_SNELLEN') || tail.endsWith('BCVA_SNELLEN')) {
    return { widget: 'snellen' }
  }
  if (tail.endsWith('_IOP') || tail.includes('_IOP_')) {
    return { widget: 'number-stepper', unit: 'mmHg' }
  }
  if (tail.endsWith('_CRT') || tail.includes('_CRT_')) {
    return { widget: 'number-stepper', unit: 'µm' }
  }
  if (tail.endsWith('_ACD') || tail.includes('_ACD_')) {
    return { widget: 'number-stepper', unit: 'mm' }
  }
  if (tail.endsWith('_DONE_REASON')) {
    return { widget: 'conditional-reason' }
  }
  // TRISTATE_REASON parent: detected via the *_TRISTATE OID suffix OR by
  // three options, one matching "unbekannt"/"unknown". Same select-one wire
  // shape as segmented-yesno (legacy XLS uploads stay compatible).
  if (tail.endsWith('_TRISTATE')) {
    return { widget: 'tristate-radio' }
  }
  if (
    props.item.dataType === 'select-one' &&
    props.item.options &&
    props.item.options.length === 3 &&
    props.item.options.some((o) => {
      const c = String(o.code).toLowerCase()
      return c === 'unbekannt' || c === 'unknown' || c === '2'
    })
  ) {
    return { widget: 'tristate-radio' }
  }
  if (tail.endsWith('_DONE') || tail.endsWith('_DURCHGEFUEHRT')) {
    return { widget: 'segmented-yesno' }
  }
  return { widget: 'standard' }
})

/**
 * Step the numeric input value by `delta`. Clamps to item.min/max
 * when those are present in the schema. The MUW design's stepper
 * pattern: vertical chevron buttons riding inside the input frame
 * on the right edge.
 */
function step(delta: number) {
  if (props.disabled) return
  const raw = props.modelValue == null ? '' : String(props.modelValue)
  const parsed = raw === '' ? 0 : Number(raw.replace(',', '.'))
  let next = isNaN(parsed) ? 0 : parsed + delta
  if (props.item.min != null && next < Number(props.item.min)) next = Number(props.item.min)
  if (props.item.max != null && next > Number(props.item.max)) next = Number(props.item.max)
  emit('update:modelValue', next)
}

/**
 * Phase E.6 ophth-field-catalog (2026-06-12): three-state for the
 * conditional-reason widget driven by the catalog entry's
 * {@code conditional_show_when_value} + the parent item's current
 * value (passed in via {@code parentValue} from CrfEntryView).
 *
 *  - {@code active-empty}  — parent matches the show-when value AND
 *    this input is empty. Red coral border + "Grund erforderlich"
 *    tag below.
 *  - {@code active-filled} — parent matches but a reason has been
 *    typed. Standard slate border.
 *  - {@code inactive}      — parent value doesn't match (yet).
 *    Input disabled, grey background, "Aktiv, sobald X gewählt ist"
 *    hint.
 *
 * Falls back to {@code inactive} when no catalog entry is bound OR
 * the entry doesn't declare a show-when — keeps the widget safe to
 * mount on items that aren't actually conditional.
 */
type ConditionalReasonState = 'active-empty' | 'active-filled' | 'inactive'
const conditionalReasonState = computed<ConditionalReasonState>(() => {
  if (ophthPresentation.value.widget !== 'conditional-reason') return 'inactive'
  const entry = catalogEntry.value
  const expected = entry?.conditionalShowWhenValue
  if (expected == null || expected === '') return 'inactive'
  const parent = props.parentValue
  if (parent == null) return 'inactive'
  const parentMatches = String(parent) === expected
  if (!parentMatches) return 'inactive'
  const own = props.modelValue
  const ownIsEmpty = own == null || String(own).trim() === ''
  return ownIsEmpty ? 'active-empty' : 'active-filled'
})

/**
 * Display value for the "Aktiv, sobald {value} gewählt ist" hint.
 * When the catalog defines a yesno parent with a localized label
 * (e.g. "nein" → "Nein"), look it up in the parent's options. Falls
 * back to the raw token if no options are present (defensive only —
 * the catalog seed always carries options for yesno widgets).
 */
const conditionalActivationLabel = computed<string>(() => {
  const entry = catalogEntry.value
  if (entry?.conditionalShowWhenValue == null) return ''
  // Localized label is hosted on the parent's catalog row, not this
  // item's. CrfEntryView passes both via the parentValue helper, but
  // we don't have the parent's catalog entry here — fall back to the
  // raw token, capitalised so "nein" reads as "Nein". German-style
  // ASCII labels only at this layer.
  const raw = entry.conditionalShowWhenValue
  return raw.charAt(0).toUpperCase() + raw.slice(1)
})

/**
 * App-feedback Wave 1D (2026-06-19) — TRISTATE_REASON widget. Three
 * radio chips with wire tokens {@code 1} / {@code 0} / {@code 2}
 * (matching the canonical BL contract for Ja / Nein, plus an
 * Unbekannt sentinel that the runtime treats as "answered but no
 * follow-up needed"). Falls back to operator-authored option codes
 * when the item carries explicit {@code item.options} so a legacy
 * XLS-uploaded CRF with German-coded options ({@code ja|Ja,nein|Nein,
 * unbekannt|Unbekannt}) round-trips cleanly.
 */
const tristateYesToken = computed<string>(() => {
  const opts = props.item.options
  if (opts && opts.length >= 1) return opts[0].code
  return '1'
})
const tristateNoToken = computed<string>(() => {
  const opts = props.item.options
  if (opts && opts.length >= 2) return opts[1].code
  return '0'
})
const tristateUnknownToken = computed<string>(() => {
  const opts = props.item.options
  if (opts && opts.length >= 3) return opts[2].code
  return '2'
})
const isTristateYes = computed(() => {
  if (props.modelValue == null || props.modelValue === '') return false
  return String(props.modelValue) === tristateYesToken.value
})
const isTristateNo = computed(() => {
  if (props.modelValue == null || props.modelValue === '') return false
  return String(props.modelValue) === tristateNoToken.value
})
const isTristateUnknown = computed(() => {
  if (props.modelValue == null || props.modelValue === '') return false
  return String(props.modelValue) === tristateUnknownToken.value
})
const tristateRadioName = computed(() => `tristate-radio-${props.item.oid}`)

/**
 * App-feedback Wave 1D (2026-06-19) — synthetic child-reason value
 * for the TRISTATE_REASON parent. The reason text lives on a SEPARATE
 * item_data row (the sibling reason item authored alongside the
 * parent — see the {@code AuthoringDataType.TRISTATE_REASON} contract).
 * For the runtime renderer we store the reason in the parent widget
 * state via a ref so the caller's modelValue stays the canonical
 * Ja/Nein/Unbekannt token; the parent emits an additional
 * {@code update:tristate-reason} event whenever the reason text
 * changes so the entry view can persist it on the sibling item.
 *
 * <p>When the parent is NOT 'Nein', the reason field is hidden and
 * its text is preserved in-memory (matches the {@code hiddenValues}
 * spec — the show-when machinery already guards persistence).
 */
const tristateReason = ref<string>('')

/** Tracks the reveal so we can autofocus the textarea exactly once. */
const tristateReasonInput = ref<HTMLTextAreaElement | null>(null)
watch(isTristateNo, async (now, prev) => {
  if (now && !prev) {
    await nextTick()
    tristateReasonInput.value?.focus()
  }
})

function onTristateSelect(token: string): void {
  emit('update:modelValue', token)
}

function onTristateReasonInput(event: Event): void {
  const v = (event.target as HTMLTextAreaElement).value
  tristateReason.value = v
  // The parent emits the same {@code update:modelValue} event with a
  // composite payload when the reason changes so the entry store can
  // route the text into the sibling reason item via the show-when
  // discovery on the schema side. The caller passes a sibling-OID
  // resolver into the widget so the dispatch lands on the right item.
  emit('update:tristate-reason', v)
}

/**
 * App-feedback Wave 1D (2026-06-19) — the conditional-field
 * interactivity bug fix. Resolve a parent value for the
 * conditional-reason widget that does NOT depend on a catalog entry.
 *
 * <p><b>Root cause:</b> previously the widget required a non-blank
 * {@code catalogEntry.conditionalShowWhenValue} before the textarea
 * would enable itself (see {@link conditionalReasonState} above).
 * For items routed into the conditional-reason branch via the
 * OID-suffix heuristic ({@code *_DONE_REASON}), no catalog entry
 * exists — so the textarea stayed permanently disabled even after
 * the parent flipped to "Nein".
 *
 * <p>The fix: derive the show-when literal + parent value from
 * (a) the catalog when available, (b) the item's {@code showWhen}
 * rule when present, OR (c) the caller-supplied {@code parentValue}
 * prop as a last resort. Whichever source produces a non-null
 * literal + the parent matches it, the textarea activates.
 */
const conditionalReasonStateFallback = computed<ConditionalReasonState>(() => {
  if (ophthPresentation.value.widget !== 'conditional-reason') return 'inactive'
  // Catalog path already handled by conditionalReasonState above; this
  // fallback only fires when the catalog returns null but the item
  // still routes into the conditional-reason widget via the OID
  // heuristic.
  if (catalogEntry.value != null) return conditionalReasonState.value
  // Heuristic-only branch: the parent's value is provided via the
  // parentValue prop (CrfEntryView's parentValueFor helper now
  // resolves it from the item's show-when rule). If no parent value is
  // supplied OR the value is blank, treat as inactive.
  const parent = props.parentValue
  if (parent == null || String(parent).trim() === '') return 'inactive'
  // Without a catalog entry we don't know which literal value should
  // unlock the textarea. The simplest robust contract: any non-blank
  // parent value unlocks editing (the show-when machinery already
  // guards the widget's visibility — if the operator can see the
  // reason field, they should be able to type into it).
  const own = props.modelValue
  const ownIsEmpty = own == null || String(own).trim() === ''
  return ownIsEmpty ? 'active-empty' : 'active-filled'
})

/**
 * The effective conditional-reason state used by the template. Falls
 * back to {@link conditionalReasonStateFallback} when the catalog
 * resolver returns 'inactive' so heuristic-only items still activate.
 */
const conditionalReasonStateEffective = computed<ConditionalReasonState>(() => {
  const catalogState = conditionalReasonState.value
  if (catalogState !== 'inactive') return catalogState
  return conditionalReasonStateFallback.value
})

/**
 * App-feedback Wave 1D (2026-06-19) — autofocus the conditional
 * reason input on first reveal. Mirrors the tri-state reason
 * textarea behaviour so the operator's cursor lands directly in the
 * field that just appeared (no second click required).
 */
const conditionalReasonInputRef = ref<HTMLInputElement | null>(null)
watch(
  () => conditionalReasonStateEffective.value,
  async (now, prev) => {
    if (
      (now === 'active-empty' || now === 'active-filled') &&
      (prev === 'inactive' || prev === undefined)
    ) {
      await nextTick()
      conditionalReasonInputRef.value?.focus()
    }
  },
)

/**
 * Snellen widget state — model-value is stored as `"20/40"`. Two
 * controlled mini-inputs read the numerator/denominator halves and
 * re-join on every edit. Empty halves serialise as `null` so the
 * dirty map doesn't churn on a blank widget.
 */
const snellenN = computed(() => {
  const v = props.modelValue == null ? '' : String(props.modelValue)
  const slash = v.indexOf('/')
  return slash < 0 ? v : v.slice(0, slash)
})
const snellenD = computed(() => {
  const v = props.modelValue == null ? '' : String(props.modelValue)
  const slash = v.indexOf('/')
  return slash < 0 ? '' : v.slice(slash + 1)
})
function onSnellenInput(part: 'n' | 'd', event: Event) {
  const raw = (event.target as HTMLInputElement).value
  const n = part === 'n' ? raw : snellenN.value
  const d = part === 'd' ? raw : snellenD.value
  const combined = (n || '') + '/' + (d || '')
  emit('update:modelValue', combined === '/' ? null : combined)
}

function fileRef(): { filename: string; bytes: number } | null {
  const v = props.modelValue
  if (v && typeof v === 'object' && 'filename' in v && 'bytes' in v) {
    return v as { filename: string; bytes: number }
  }
  return null
}
</script>

<template>
  <!-- notes-deeplink (2026-06-11) — every widget root carries an
       id="item-<oid>" so NotesDiscrepanciesView's deep-link (eye route
       /event-crfs/<id>?item=<oid>) can scrollIntoView + flash-highlight
       the right item without a per-CRF schema lookup. -->
  <div :id="`item-${item.oid}`">
    <FieldLabel v-if="!suppressLabel" :for="inputId" :required="item.required">
      {{ item.label }}
      <slot name="label-extras" />
    </FieldLabel>

    <!-- App-feedback Wave 1D (2026-06-19) — TRISTATE_REASON parent
         widget. Three radio chips (Ja / Nein / Unbekannt) plus an
         inline reason textarea that reveals when the operator picks
         "Nein". Detected via the OID-suffix {@code *_TRISTATE} OR an
         explicit three-option select-one item carrying an "unbekannt"
         token (see {@link ophthPresentation} above). Goes BEFORE the
         segmented-yesno branch so a {@code *_TRISTATE_DONE} suffix
         wins over the generic {@code *_DONE} heuristic. -->
    <template v-if="ophthPresentation.widget === 'tristate-radio'">
      <div
        role="radiogroup"
        :aria-invalid="hasError || undefined"
        :aria-labelledby="suppressLabel ? undefined : `${inputId}-label`"
        class="inline-flex gap-1 p-1 bg-slate-100 border border-slate-200 rounded-[13px]"
        :class="{ 'opacity-60': disabled }"
        data-testid="tristate-radiogroup"
      >
        <button
          :id="`${inputId}-ja`"
          type="button"
          :name="tristateRadioName"
          :disabled="disabled"
          class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-[9px] text-[14px] font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-muw-blue-500"
          :class="isTristateYes
            ? 'bg-white text-muw-teal-700 shadow-[0_1px_2px_rgba(17,29,78,0.14),0_0_0_1px_rgba(17,29,78,0.03)]'
            : 'text-slate-600 hover:text-slate-900'"
          :data-testid="`tristate-radio-Ja`"
          @click="onTristateSelect(tristateYesToken)"
        >
          <span
            class="w-1.5 h-1.5 rounded-full bg-muw-teal-700 transition-opacity"
            :class="isTristateYes ? 'opacity-100' : 'opacity-0'"
          ></span>
          {{ t('crfItem.tristate.tristateYes') }}
        </button>
        <button
          :id="`${inputId}-nein`"
          type="button"
          :name="tristateRadioName"
          :disabled="disabled"
          class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-[9px] text-[14px] font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-muw-blue-500"
          :class="isTristateNo
            ? 'bg-white text-muw-coral-700 shadow-[0_1px_2px_rgba(17,29,78,0.14),0_0_0_1px_rgba(17,29,78,0.03)]'
            : 'text-slate-600 hover:text-slate-900'"
          :data-testid="`tristate-radio-Nein`"
          @click="onTristateSelect(tristateNoToken)"
        >
          <span
            class="w-1.5 h-1.5 rounded-full bg-muw-coral-700 transition-opacity"
            :class="isTristateNo ? 'opacity-100' : 'opacity-0'"
          ></span>
          {{ t('crfItem.tristate.tristateNo') }}
        </button>
        <button
          :id="`${inputId}-unbekannt`"
          type="button"
          :name="tristateRadioName"
          :disabled="disabled"
          class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-[9px] text-[14px] font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-muw-blue-500"
          :class="isTristateUnknown
            ? 'bg-white text-slate-700 shadow-[0_1px_2px_rgba(17,29,78,0.14),0_0_0_1px_rgba(17,29,78,0.03)]'
            : 'text-slate-600 hover:text-slate-900'"
          :data-testid="`tristate-radio-Unbekannt`"
          @click="onTristateSelect(tristateUnknownToken)"
        >
          <span
            class="w-1.5 h-1.5 rounded-full bg-slate-500 transition-opacity"
            :class="isTristateUnknown ? 'opacity-100' : 'opacity-0'"
          ></span>
          {{ t('crfItem.tristate.tristateUnknown') }}
        </button>
      </div>
      <!-- Conditional reason textarea — appears only when the operator
           picks "Nein". The textarea auto-focuses on first reveal so
           the cursor lands in the field. Wire contract: parent's
           {@code update:modelValue} carries the tri-state token; the
           sibling reason text is emitted via
           {@code update:tristate-reason} for the entry view to route
           into the matching reason item_data row. -->
      <div v-if="isTristateNo" class="mt-2">
        <label
          :for="`${inputId}-reason`"
          class="block text-[12px] font-medium text-slate-600 mb-1"
        >
          {{ t('crfItem.tristate.reasonLabel') }}
        </label>
        <textarea
          :id="`${inputId}-reason`"
          ref="tristateReasonInput"
          :value="tristateReason"
          :disabled="disabled"
          :placeholder="t('crfItem.tristate.reasonPlaceholder')"
          rows="2"
          autofocus
          class="w-full px-3 py-2 text-[14px] text-slate-900 bg-white border border-slate-300 rounded-md outline-none transition-colors muw-focus hover:border-slate-400 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-500 focus:shadow-[0_0_0_3px_rgba(17,29,78,0.13)]"
          data-testid="tristate-reason-textarea"
          @input="onTristateReasonInput"
        ></textarea>
      </div>
    </template>

    <!-- Phase E.6 ophth-bilateral-design (2026-06-11): ophthalmology
         specialist branches go FIRST so the heuristic detector wins
         even when the underlying dataType would otherwise route to
         the generic select-one dropdown. Spectralis-OCT "durchgeführt"
         is authored in v2.0 as select-one + ja/nein options, but the
         OID's _DONE suffix should still produce the segmented Ja/Nein
         pill — clinician-facing convention. -->
    <template v-else-if="ophthPresentation.widget === 'segmented-yesno' && item.dataType !== 'boolean'">
      <!-- MUW segmented Ja/Nein. Wire contract: value-of-Ja-option
           token = 'ja' (or '1' if the item is canonical boolean,
           handled by the dataType==='boolean' branch below). Reads
           the first two options of a select-one item to decide which
           token to emit; falls back to the legacy '1' / '0' boolean
           tokens when no options are present. -->
      <div
        role="radiogroup"
        :aria-invalid="hasError || undefined"
        :aria-labelledby="suppressLabel ? undefined : `${inputId}-label`"
        class="inline-flex gap-1 p-1 bg-slate-100 border border-slate-200 rounded-[13px]"
        :class="{ 'opacity-60': disabled }"
      >
        <button
          :id="`${inputId}-yes`"
          type="button"
          :name="booleanRadioName"
          :disabled="disabled"
          class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-[9px] text-[14px] font-medium transition-colors"
          :class="isYesNoYes
            ? 'bg-white text-muw-teal-700 shadow-[0_1px_2px_rgba(17,29,78,0.14),0_0_0_1px_rgba(17,29,78,0.03)]'
            : 'text-slate-600 hover:text-slate-900'"
          @click="emit('update:modelValue', yesNoYesToken)"
        >
          <span
            class="w-1.5 h-1.5 rounded-full bg-muw-teal-700 transition-opacity"
            :class="isYesNoYes ? 'opacity-100' : 'opacity-0'"
          ></span>
          {{ t('crfEntry.boolean.yes') }}
        </button>
        <button
          :id="`${inputId}-no`"
          type="button"
          :name="booleanRadioName"
          :disabled="disabled"
          class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-[9px] text-[14px] font-medium transition-colors"
          :class="isYesNoNo
            ? 'bg-white text-muw-coral-700 shadow-[0_1px_2px_rgba(17,29,78,0.14),0_0_0_1px_rgba(17,29,78,0.03)]'
            : 'text-slate-600 hover:text-slate-900'"
          @click="emit('update:modelValue', yesNoNoToken)"
        >
          <span
            class="w-1.5 h-1.5 rounded-full bg-muw-coral-700 transition-opacity"
            :class="isYesNoNo ? 'opacity-100' : 'opacity-0'"
          ></span>
          {{ t('crfEntry.boolean.no') }}
        </button>
      </div>
    </template>

    <template v-else-if="item.dataType === 'select-one' && item.options">
      <SelectInput v-bind="textBindings">
        <option :value="undefined">— {{ t('common.search') }} —</option>
        <option v-for="opt in item.options" :key="opt.code" :value="opt.code">{{ opt.label }}</option>
      </SelectInput>
    </template>

    <template v-else-if="item.dataType === 'select-multi' && item.options">
      <CheckboxArrayInput
        :id-prefix="inputId"
        :model-value="(modelValue as string[] | null | undefined) ?? []"
        :options="item.options"
        :error="hasError"
        :disabled="disabled"
        @update:model-value="(v: string[]) => emit('update:modelValue', v)"
      />
    </template>

    <template v-else-if="item.dataType === 'file'">
      <FileUploadInput
        :id-prefix="inputId"
        :model-value="fileRef()"
        :max-bytes="maxFileBytes"
        :allowed-extensions="fileExtensions"
        :drop-prompt-label="t('crfEntry.file.dropPrompt')"
        :browse-label="t('crfEntry.file.browse')"
        :uploading-label="t('crfEntry.file.uploading')"
        :remove-label="t('crfEntry.file.remove')"
        :replace-label="t('crfEntry.file.replace')"
        :too-big-message="t('crfEntry.file.tooBig')"
        :bad-extension-message="t('crfEntry.file.badExtension')"
        :busy="fileBusy"
        :disabled="disabled"
        :error="hasError"
        @upload="(f: File) => emit('upload-file', f)"
        @clear="emit('clear-file')"
      />
    </template>

    <template v-else-if="compact && (item.dataType === 'integer' || item.dataType === 'real' || item.dataType === 'string')">
      <!-- Phase E.6 ophth-bilateral-design (2026-06-11): compact mini
           input used by compound-bilateral sub-fields (refraction
           Sph/Cyl/Axis/Vis). 56×42 centred input, no stepper, no
           unit suffix — the compound row's sub-label header already
           encodes which value the operator is entering. -->
      <input
        :id="inputId"
        :value="modelValue ?? ''"
        :aria-invalid="hasError || undefined"
        :type="item.dataType === 'integer' || item.dataType === 'real' ? 'number' : 'text'"
        :inputmode="item.dataType === 'integer' ? 'numeric' : (item.dataType === 'real' ? 'decimal' : undefined)"
        :step="item.dataType === 'integer' ? 1 : 0.1"
        :disabled="disabled"
        class="w-14 h-[42px] text-center bg-white border rounded-[10px] outline-none text-[14px] text-slate-900 tabular-nums [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none transition-colors"
        :class="hasError
          ? 'border-rose-400 focus:border-rose-500 focus:shadow-[0_0_0_3px_rgba(244,63,94,0.12)]'
          : 'border-slate-300 hover:border-slate-400 focus:border-muw-blue focus:shadow-[0_0_0_3px_rgba(17,29,78,0.13)]'"
        @input="item.dataType === 'string' ? emit('update:modelValue', ($event.target as HTMLInputElement).value) : onNumberInput($event)"
      />
    </template>

    <template v-else-if="(item.dataType === 'integer' || item.dataType === 'real') && ophthPresentation.widget === 'number-stepper'">
      <!-- MUW number-stepper. Rounded 12px frame, inline unit suffix,
           vertical stepper buttons on the right edge — mirrors the
           ophthalmology-visit-bilateral.html design's .fld pattern. -->
      <div
        class="flex items-stretch h-[46px] max-w-[260px] bg-white border rounded-xl transition-colors"
        :class="hasError
          ? 'border-rose-400 focus-within:border-rose-500 focus-within:shadow-[0_0_0_3px_rgba(244,63,94,0.12)]'
          : 'border-slate-300 hover:border-slate-400 focus-within:border-muw-blue focus-within:shadow-[0_0_0_3px_rgba(17,29,78,0.13)]'"
      >
        <input
          :id="inputId"
          :value="modelValue ?? ''"
          :aria-invalid="hasError || undefined"
          type="number"
          :min="item.min"
          :max="item.max"
          :step="item.dataType === 'integer' ? 1 : 0.1"
          :disabled="disabled"
          class="flex-1 min-w-0 bg-transparent border-0 outline-none px-3.5 text-[15px] text-slate-900 tabular-nums [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
          @input="onNumberInput"
        />
        <span v-if="ophthPresentation.unit" class="flex items-center px-1.5 text-[12px] font-medium text-slate-500 whitespace-nowrap">{{ ophthPresentation.unit }}</span>
        <div class="flex flex-col w-[30px] border-l border-slate-200">
          <button
            type="button"
            tabindex="-1"
            :disabled="disabled"
            class="flex-1 flex items-center justify-center text-slate-400 hover:bg-muw-blue-50 hover:text-muw-blue border-b border-slate-200 disabled:cursor-not-allowed"
            :aria-label="t('crfEntry.stepper.increment')"
            @click="step(item.dataType === 'integer' ? 1 : 0.1)"
          >
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15" /></svg>
          </button>
          <button
            type="button"
            tabindex="-1"
            :disabled="disabled"
            class="flex-1 flex items-center justify-center text-slate-400 hover:bg-muw-blue-50 hover:text-muw-blue disabled:cursor-not-allowed"
            :aria-label="t('crfEntry.stepper.decrement')"
            @click="step(item.dataType === 'integer' ? -1 : -0.1)"
          >
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9" /></svg>
          </button>
        </div>
      </div>
    </template>

    <template v-else-if="item.dataType === 'string' && ophthPresentation.widget === 'conditional-reason'">
      <!-- Phase E.6 ophth-field-catalog (2026-06-12): conditional
           reason input. Three visual states driven by the catalog
           entry's conditional_show_when_value + the parent item's
           current value (parentValue prop). Wire contract on the
           model-value is plain string — the input is disabled in the
           inactive state, so the empty value naturally stays empty.
           Caller (CrfEntryView) is responsible for clearing the
           value when the parent flips from active → inactive.

           App-feedback Wave 1D (2026-06-19) — bug fix: previously
           the input stayed disabled when the item routed into this
           branch via the {@code *_DONE_REASON} OID heuristic (no
           catalog entry). The {@link conditionalReasonStateEffective}
           computed falls back to the show-when rule's parent value so
           the textarea unlocks when the parent flips. {@code ref}
           binds the input so the watch above can autofocus it on
           first reveal. -->
      <div data-conditional-reason-state="state">
        <input
          :id="inputId"
          ref="conditionalReasonInputRef"
          :value="(modelValue == null ? '' : String(modelValue))"
          :aria-invalid="hasError || conditionalReasonStateEffective === 'active-empty' || undefined"
          type="text"
          :placeholder="t('crfEntry.conditionalReason.placeholder')"
          :disabled="disabled || conditionalReasonStateEffective === 'inactive'"
          class="w-full h-[46px] px-3.5 text-[15px] text-slate-900 border rounded-xl outline-none transition-colors muw-focus"
          :class="conditionalReasonStateEffective === 'active-empty'
            ? 'border-muw-coral-600 bg-[#fffdfc] focus:border-muw-coral-600 focus:ring-2 focus:ring-muw-blue-500 focus:shadow-[0_0_0_3px_rgba(217,104,73,0.16)]'
            : conditionalReasonStateEffective === 'active-filled'
              ? 'border-slate-300 hover:border-slate-400 bg-white focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-500 focus:shadow-[0_0_0_3px_rgba(17,29,78,0.13)]'
              : 'border-slate-200 bg-slate-50 text-slate-400 cursor-not-allowed'"
          data-testid="conditional-reason-input"
          @input="(e) => emit('update:modelValue', (e.target as HTMLInputElement).value)"
        />
        <div
          v-if="conditionalReasonStateEffective === 'active-empty'"
          class="inline-flex items-center gap-1.5 mt-1.5 text-[11.5px] font-medium text-muw-coral-700"
          data-testid="conditional-reason-required-tag"
        >
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.75"><circle cx="12" cy="12" r="10" /><path d="M12 8v5M12 16h.01" /></svg>
          {{ t('crfEntry.conditionalReason.requiredTag') }}
        </div>
        <div
          v-else-if="conditionalReasonStateEffective === 'inactive' && conditionalActivationLabel"
          class="mt-1.5 text-[11.5px] text-slate-400"
          data-testid="conditional-reason-inactive-hint"
        >
          {{ t('crfEntry.conditionalReason.inactiveHint', { value: conditionalActivationLabel }) }}
        </div>
      </div>
    </template>

    <template v-else-if="item.dataType === 'string' && ophthPresentation.widget === 'snellen'">
      <!-- MUW Snellen fraction widget. Two centered mini-inputs joined
           by a stylised slash; the model-value serialises as "20/40".
           Width 200px matches the number-stepper's footprint so the
           bilateral table stays grid-aligned. -->
      <div
        class="inline-flex items-center gap-2 h-[46px] w-[200px] justify-center px-5 bg-white border rounded-xl transition-colors"
        :class="hasError
          ? 'border-rose-400 focus-within:border-rose-500 focus-within:shadow-[0_0_0_3px_rgba(244,63,94,0.12)]'
          : 'border-slate-300 hover:border-slate-400 focus-within:border-muw-blue focus-within:shadow-[0_0_0_3px_rgba(17,29,78,0.13)]'"
      >
        <input
          :id="inputId"
          :value="snellenN"
          type="text"
          inputmode="numeric"
          placeholder="20"
          :disabled="disabled"
          class="w-14 text-center bg-transparent border-0 outline-none text-[16px] text-slate-900 tabular-nums placeholder:text-slate-300"
          @input="(e) => onSnellenInput('n', e)"
        />
        <span class="text-[24px] leading-none text-slate-300 font-light -translate-y-px select-none">/</span>
        <input
          :value="snellenD"
          type="text"
          inputmode="numeric"
          placeholder="40"
          :disabled="disabled"
          class="w-14 text-center bg-transparent border-0 outline-none text-[16px] text-slate-900 tabular-nums placeholder:text-slate-300"
          @input="(e) => onSnellenInput('d', e)"
        />
      </div>
    </template>

    <template v-else-if="item.dataType === 'integer' || item.dataType === 'real'">
      <input
        :id="inputId"
        :value="modelValue ?? ''"
        :aria-invalid="hasError || undefined"
        type="number"
        :min="item.min"
        :max="item.max"
        :step="item.dataType === 'integer' ? 1 : 0.1"
        :disabled="disabled"
        class="w-full px-3 py-2 border rounded-md focus:outline-none transition-colors muw-focus"
        :class="hasError
          ? 'border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100'
          : 'border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100'"
        @input="onNumberInput"
      />
    </template>

    <template v-else-if="item.dataType === 'date'">
      <!-- DATE — DateInput primitive renders a native picker pinned to
           de-AT so the display is TT.MM.JJJJ. The store already
           round-trips values as ISO 'YYYY-MM-DD' (see {@link validateOne}
           in crfEntry.ts), which is exactly the wire format
           `input[type=date]` consumes. -->
      <DateInput
        :id="inputId"
        :model-value="modelValue == null ? '' : String(modelValue)"
        :error="hasError"
        :disabled="disabled"
        @update:model-value="(v) => emit('update:modelValue', v)"
      />
    </template>

    <template v-else-if="item.dataType === 'partial-date'">
      <!-- PDATE — partial date is either YYYY or YYYY-MM. No native
           HTML control covers both (input[type=month] forces month);
           render a plain text input with pattern + inputmode so mobile
           keyboards default to numeric and the browser flags invalid
           shapes on submit. -->
      <input
        :id="inputId"
        :value="(modelValue == null ? '' : String(modelValue))"
        :aria-invalid="hasError || undefined"
        type="text"
        inputmode="numeric"
        pattern="\d{4}(-\d{2})?"
        placeholder="YYYY or YYYY-MM"
        :disabled="disabled"
        class="w-full px-3 py-2 border rounded-md focus:outline-none transition-colors muw-focus"
        :class="hasError
          ? 'border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100'
          : 'border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100'"
        @input="(e) => emit('update:modelValue', (e.target as HTMLInputElement).value)"
      />
    </template>

    <template v-else-if="item.dataType === 'boolean' || ophthPresentation.widget === 'segmented-yesno'">
      <!-- MUW segmented Ja/Nein control. Design pattern from
           ophthalmology-visit-bilateral.html: pill-shaped wrapper, the
           selected pill gets a white card with subtle elevation and a
           coloured dot — teal for Ja, coral for Nein — to match the
           clinical convention of green=present, coral=absent.
           Wire contract: '1' = Yes/Ja, '0' = No/Nein, empty = unanswered.
           The widget activates on either {@code dataType === 'boolean'}
           OR when the {@code ophthPresentation} heuristic flags a
           Ja/Nein item (e.g. *_DONE suffix with no explicit boolean
           type). -->
      <div
        role="radiogroup"
        :aria-invalid="hasError || undefined"
        :aria-labelledby="suppressLabel ? undefined : `${inputId}-label`"
        class="inline-flex gap-1 p-1 bg-slate-100 border border-slate-200 rounded-[13px]"
        :class="{ 'opacity-60': disabled }"
      >
        <button
          :id="`${inputId}-yes`"
          type="button"
          :name="booleanRadioName"
          :disabled="disabled"
          class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-[9px] text-[14px] font-medium transition-colors"
          :class="isBooleanYes
            ? 'bg-white text-muw-teal-700 shadow-[0_1px_2px_rgba(17,29,78,0.14),0_0_0_1px_rgba(17,29,78,0.03)]'
            : 'text-slate-600 hover:text-slate-900'"
          @click="emit('update:modelValue', '1')"
        >
          <span
            class="w-1.5 h-1.5 rounded-full bg-muw-teal-700 transition-opacity"
            :class="isBooleanYes ? 'opacity-100' : 'opacity-0'"
          ></span>
          {{ t('crfEntry.boolean.yes') }}
        </button>
        <button
          :id="`${inputId}-no`"
          type="button"
          :name="booleanRadioName"
          :disabled="disabled"
          class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-[9px] text-[14px] font-medium transition-colors"
          :class="isBooleanNo
            ? 'bg-white text-muw-coral-700 shadow-[0_1px_2px_rgba(17,29,78,0.14),0_0_0_1px_rgba(17,29,78,0.03)]'
            : 'text-slate-600 hover:text-slate-900'"
          @click="emit('update:modelValue', '0')"
        >
          <span
            class="w-1.5 h-1.5 rounded-full bg-muw-coral-700 transition-opacity"
            :class="isBooleanNo ? 'opacity-100' : 'opacity-0'"
          ></span>
          {{ t('crfEntry.boolean.no') }}
        </button>
      </div>
    </template>

    <template v-else>
      <TextInput v-bind="textBindings" type="text" />
    </template>

    <!-- Phase E.6 ophth-bilateral-design (2026-06-11): suppress the
         per-item helper sentence whenever the ophth widget already
         renders a unit suffix inline (number-stepper / snellen).
         The seeded helper for v2.0 OPHTH items duplicates the unit
         ("letters" / "mmHg") that the inline suffix already prints. -->
    <HelperText
      v-if="item.helper && ophthPresentation.widget !== 'number-stepper' && ophthPresentation.widget !== 'snellen'"
    >
      {{ item.helper }}
    </HelperText>
    <ErrorText v-if="errorMessage">
      {{ errorMessage }}
      <button
        type="button"
        class="ml-2 inline-flex items-center text-[11px] text-muw-blue underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-muw-blue"
        :data-testid="`crf-item-report-validation-${item.oid}`"
        @click="$emit('report-validation', { itemOid: item.oid, errorMessage: errorMessage as string })"
      >
        {{ t('crfEntry.itemNote.reportValidation') }}
      </button>
    </ErrorText>
  </div>
</template>

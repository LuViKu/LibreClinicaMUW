<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiGet } from '@/api/client'

/**
 * #26 — Terminology autocomplete (combobox).
 *
 * A free-text input with a debounced typeahead against
 * {@code GET /terminology/search?system=&q=}. Picking a suggestion writes a
 * human-readable, code-carrying value ("H40 — Glaukom") back through v-model,
 * so the stored eCRF value keeps both the code and its display. Free text is
 * always allowed — the catalogue assists, it doesn't gate.
 *
 * Used by the repeating-table item's terminology-bound columns (ICD-10-GM
 * diagnoses, ATC/drug), and reusable anywhere a coded free-text field helps.
 */
interface TermHit { code: string; display: string; properties?: string }

/**
 * A picked concept, surfaced to the parent so it can fan properties out
 * into sibling fields (the medication "strength → Dosis" fill map). The
 * {@code properties} string is the raw JSONB the search endpoint returns;
 * parsed here into a flat string map, best-effort.
 */
export interface TermPick { code: string; display: string; value: string; properties: Record<string, string> }

function parseProperties(raw: string | undefined): Record<string, string> {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const out: Record<string, string> = {}
      for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
        if (v == null) continue
        out[k] = typeof v === 'object' ? JSON.stringify(v) : String(v)
      }
      return out
    }
  } catch {
    /* non-JSON or FHIR array shape — no flat properties to fan out */
  }
  return {}
}

interface Props {
  modelValue: string | null
  /** Code system to search: 'icd10gm' | 'atc' | 'drug' | … */
  system: string
  id?: string
  disabled?: boolean
  error?: boolean
  placeholder?: string
}
const props = withDefaults(defineProps<Props>(), {
  id: undefined,
  disabled: false,
  error: false,
  placeholder: undefined,
})
const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** Fired when a catalogue suggestion is chosen (not on free text). */
  pick: [pick: TermPick]
}>()

const { t } = useI18n()
const MAX = 10
const DEBOUNCE_MS = 200

const query = ref(props.modelValue ?? '')
const hits = ref<TermHit[]>([])
const open = ref(false)
const loading = ref(false)
const highlighted = ref(0)
let debounce: ReturnType<typeof setTimeout> | null = null
let seq = 0 // guards against out-of-order async responses

watch(() => props.modelValue, (v) => { if ((v ?? '') !== query.value) query.value = v ?? '' })

function onInput(value: string) {
  query.value = value
  emit('update:modelValue', value) // free text is a valid value
  scheduleSearch(value)
}

function scheduleSearch(value: string) {
  if (debounce) clearTimeout(debounce)
  const term = value.trim()
  if (term.length < 2) { hits.value = []; open.value = false; return }
  debounce = setTimeout(() => void runSearch(term), DEBOUNCE_MS)
}

async function runSearch(term: string) {
  const mine = ++seq
  loading.value = true
  try {
    const res = await apiGet<TermHit[]>(
      `/pages/api/v1/terminology/search?system=${encodeURIComponent(props.system)}&q=${encodeURIComponent(term)}&limit=${MAX}`,
    )
    if (mine !== seq) return // a newer keystroke superseded this response
    hits.value = res ?? []
    highlighted.value = 0
    open.value = hits.value.length > 0
  } catch {
    if (mine === seq) { hits.value = []; open.value = false }
  } finally {
    if (mine === seq) loading.value = false
  }
}

function pick(h: TermHit) {
  const value = `${h.code} — ${h.display}`
  query.value = value
  emit('update:modelValue', value)
  emit('pick', { code: h.code, display: h.display, value, properties: parseProperties(h.properties) })
  open.value = false
  hits.value = []
}

function onKeydown(e: KeyboardEvent) {
  if (!open.value || hits.value.length === 0) return
  if (e.key === 'ArrowDown') { e.preventDefault(); highlighted.value = (highlighted.value + 1) % hits.value.length }
  else if (e.key === 'ArrowUp') { e.preventDefault(); highlighted.value = (highlighted.value - 1 + hits.value.length) % hits.value.length }
  else if (e.key === 'Enter') { e.preventDefault(); pick(hits.value[highlighted.value]!) }
  else if (e.key === 'Escape') { open.value = false }
}

// Close on click-outside.
const vClickOutside = {
  mounted(el: HTMLElement & { __co?: (ev: Event) => void }) {
    el.__co = (ev: Event) => { if (!el.contains(ev.target as Node)) open.value = false }
    document.addEventListener('pointerdown', el.__co, true)
  },
  unmounted(el: HTMLElement & { __co?: (ev: Event) => void }) {
    if (el.__co) document.removeEventListener('pointerdown', el.__co, true)
  },
}

function onFocus() { if (hits.value.length > 0) open.value = true }

const inputClasses = computed(() => {
  const base = 'w-full px-3 py-2 rounded-md focus:outline-none transition-colors muw-focus'
  if (props.error) return `${base} border border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100`
  if (props.disabled) return `${base} border border-slate-200 bg-slate-100 text-slate-500 cursor-not-allowed`
  return `${base} border border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100`
})
</script>

<template>
  <div v-click-outside class="relative">
    <input
      :id="id"
      :value="query"
      :disabled="disabled"
      :placeholder="placeholder"
      :aria-invalid="error || undefined"
      :class="inputClasses"
      type="text"
      autocomplete="off"
      role="combobox"
      :aria-expanded="open"
      aria-autocomplete="list"
      data-testid="terminology-autocomplete-input"
      @input="onInput(($event.target as HTMLInputElement).value)"
      @keydown="onKeydown"
      @focus="onFocus"
    />
    <ul
      v-if="open"
      class="absolute z-30 mt-1 w-full max-h-64 overflow-auto rounded-md border border-slate-200 bg-white shadow-lg text-sm"
      role="listbox"
      data-testid="terminology-autocomplete-list"
    >
      <li
        v-for="(h, i) in hits"
        :key="h.code"
        :class="['px-3 py-1.5 cursor-pointer flex gap-2', i === highlighted ? 'bg-muw-blue-50' : 'hover:bg-slate-50']"
        role="option"
        :aria-selected="i === highlighted"
        @mousedown.prevent="pick(h)"
        @mouseenter="highlighted = i"
      >
        <span class="font-mono text-[11px] text-slate-500 shrink-0">{{ h.code }}</span>
        <span class="text-slate-800 truncate">{{ h.display }}</span>
      </li>
    </ul>
    <p v-if="loading" class="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-slate-400">{{ t('common.loading') }}</p>
  </div>
</template>

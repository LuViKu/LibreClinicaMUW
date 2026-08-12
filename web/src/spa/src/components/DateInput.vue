<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * Phase E SPA primitive — Date input.
 *
 * One canonical date control for the entire SPA. Displays and accepts the
 * day-first **DD/MM/YYYY** format the MUW clinical workflow expects, and a
 * calendar button opens the browser's native date picker. We deliberately do
 * NOT rely on a bare `<input type="date">` for display: Chromium renders that
 * in the *browser* locale (often ISO `yyyy-mm-dd`) and ignores the `lang`
 * attribute, so the format can't be pinned. Instead a masked text input owns
 * the display/typing and a visually-hidden `<input type="date">` provides the
 * calendar popup via {@link HTMLInputElement.showPicker}.
 *
 * Wire format is unchanged: the v-model is ISO `YYYY-MM-DD` (or '' when
 * empty/incomplete), so no store or backend change is needed.
 */
interface Props {
  id?: string
  /** ISO `YYYY-MM-DD`. Accepts `null` so v-model on nullable fields doesn't need coercion. */
  modelValue?: string | null
  required?: boolean
  error?: boolean
  disabled?: boolean
  /** ISO `YYYY-MM-DD` lower bound (applied to the calendar popup). */
  min?: string
  /** ISO `YYYY-MM-DD` upper bound (applied to the calendar popup). */
  max?: string
  ariaLabel?: string
  ariaDescribedby?: string
  autocomplete?: string
}

const props = withDefaults(defineProps<Props>(), {
  id: undefined,
  modelValue: '',
  required: false,
  error: false,
  disabled: false,
  min: undefined,
  max: undefined,
  ariaLabel: undefined,
  ariaDescribedby: undefined,
  autocomplete: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: [event: FocusEvent]
  focus: [event: FocusEvent]
}>()

/** ISO 'YYYY-MM-DD' → display 'DD/MM/YYYY' (or '' if not a full ISO date). */
function isoToDisplay(iso: string | null | undefined): string {
  if (!iso) return ''
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  return m ? `${m[3]}/${m[2]}/${m[1]}` : ''
}

/** Display 'DD/MM/YYYY' → ISO 'YYYY-MM-DD', or null when incomplete/invalid. */
function displayToIso(display: string): string | null {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(display)
  if (!m) return null
  const dd = Number(m[1]), mm = Number(m[2]), yyyy = Number(m[3])
  if (mm < 1 || mm > 12 || dd < 1) return null
  // Reject impossible days (e.g. 31/02) arithmetically — a Date round-trip
  // would mix local parsing with a UTC read and misbehave in UTC+ zones.
  const leap = (yyyy % 4 === 0 && yyyy % 100 !== 0) || yyyy % 400 === 0
  const daysInMonth = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  if (dd > daysInMonth[mm - 1]) return null
  return `${m[3]}-${m[2]}-${m[1]}`
}

/** Format a raw typed string into the DD/MM/YYYY mask as the user types. */
function mask(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 8)
  const parts = [digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 8)].filter((p) => p.length)
  return parts.join('/')
}

const text = ref(isoToDisplay(props.modelValue))
watch(
  () => props.modelValue,
  (v) => {
    // Keep the field in sync when the value changes externally, but don't
    // clobber a partial entry the user is mid-typing that already maps to it.
    if (displayToIso(text.value) !== (v ?? '') && isoToDisplay(v) !== text.value) {
      text.value = isoToDisplay(v)
    }
  },
)

function onTextInput(e: Event) {
  const masked = mask((e.target as HTMLInputElement).value)
  text.value = masked
  const iso = displayToIso(masked)
  if (iso) emit('update:modelValue', iso)
  else if (masked === '') emit('update:modelValue', '')
}

const pickerEl = ref<HTMLInputElement | null>(null)
function openPicker() {
  if (props.disabled) return
  const el = pickerEl.value
  if (!el) return
  try {
    el.showPicker()
  } catch {
    el.focus()
    el.click()
  }
}
function onPickerChange(e: Event) {
  const iso = (e.target as HTMLInputElement).value // native type=date → ISO
  text.value = isoToDisplay(iso)
  emit('update:modelValue', iso)
}

const inputClasses = computed(() => {
  const base = 'w-full pl-3 pr-9 py-2 rounded-md focus:outline-none transition-colors muw-focus'
  if (props.error) {
    return `${base} border border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100`
  }
  if (props.disabled) {
    return `${base} border border-slate-200 bg-slate-100 text-slate-500 cursor-not-allowed`
  }
  return `${base} border border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100`
})
</script>

<template>
  <div class="relative">
    <input
      :id="id"
      type="text"
      inputmode="numeric"
      :value="text"
      :required="required || undefined"
      :disabled="disabled"
      :autocomplete="autocomplete"
      :aria-label="ariaLabel"
      :aria-describedby="ariaDescribedby"
      :aria-invalid="error || undefined"
      :aria-required="required || undefined"
      placeholder="TT/MM/JJJJ"
      maxlength="10"
      :class="inputClasses"
      @input="onTextInput"
      @blur="$emit('blur', $event)"
      @focus="$emit('focus', $event)"
    />
    <!-- Calendar button opens the browser's native date picker. -->
    <button
      type="button"
      class="absolute inset-y-0 right-0 flex items-center px-2.5 text-slate-500 hover:text-muw-blue disabled:text-slate-500 disabled:cursor-not-allowed"
      :disabled="disabled"
      :aria-label="ariaLabel ? `${ariaLabel} — Kalender öffnen` : 'Kalender öffnen'"
      tabindex="-1"
      @click="openPicker"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <rect x="3" y="4" width="18" height="18" rx="2" /><path d="M16 2v4M8 2v4M3 10h18" />
      </svg>
    </button>
    <!-- Visually-hidden native date input drives the calendar popup only. -->
    <input
      ref="pickerEl"
      type="date"
      class="sr-only"
      tabindex="-1"
      aria-hidden="true"
      :value="modelValue ?? ''"
      :min="min"
      :max="max"
      :disabled="disabled"
      @change="onPickerChange"
    />
  </div>
</template>

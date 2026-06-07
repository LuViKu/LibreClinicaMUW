<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import TextInput from '@/components/TextInput.vue'
import { useUsersStore } from '@/stores/users'
import type { StudyUser } from '@/types/user'

/**
 * Phase E.6 DN — Assignee picker.
 *
 * Combobox over the study-users matrix: filters in-memory by
 * displayName / username / email (case-insensitive substring), caps
 * the dropdown at the first 10 matches, exposes ARIA combobox
 * semantics, and emits the picked `username` (or `''` on clear) so
 * callers can persist a stable identifier rather than a display string.
 *
 * Load gating: `users.load()` is invoked at most once per session by
 * checking the store's `rows` before the call — the parent view
 * (ManageUsersView) uses the same pattern. The store itself doesn't
 * dedup, so co-locating the gate here keeps the autocomplete usable
 * outside the manage-users surface (CRF assignee fields, DN reviewer
 * pickers) without burning a refetch on every mount.
 */
interface Props {
  modelValue: string | null
  placeholder?: string
  disabled?: boolean
  id?: string
  inputClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: undefined,
  disabled: false,
  id: 'user-autocomplete',
  inputClass: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()
const users = useUsersStore()

const MAX_RESULTS = 10

const query = ref('')
const open = ref(false)
const highlightedIndex = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

onMounted(() => {
  if (users.rows.length === 0) users.load()
})

watch(
  () => props.modelValue,
  (next) => {
    query.value = next ?? ''
  },
  { immediate: true },
)

const matches = computed<StudyUser[]>(() => {
  const q = query.value.trim().toLowerCase()
  const rows = users.rows
  const filtered = q
    ? rows.filter((u) => {
        const blob = `${u.username} ${u.displayName} ${u.email ?? ''}`.toLowerCase()
        return blob.includes(q)
      })
    : rows
  return filtered.slice(0, MAX_RESULTS)
})

const selectedUser = computed<StudyUser | null>(() => {
  const v = props.modelValue
  if (!v) return null
  return users.rows.find((u) => u.username === v) ?? null
})

const showGhost = computed(() => {
  if (open.value) return false
  if (!selectedUser.value) return false
  return query.value === selectedUser.value.username
})

const placeholderText = computed(() => props.placeholder ?? t('crfEntry.userAutocomplete.placeholder'))

const listboxId = computed(() => `${props.id}-listbox`)
const optionId = (i: number) => `${props.id}-opt-${i}`

function onFocus() {
  if (props.disabled) return
  open.value = true
  highlightedIndex.value = 0
}

function onInput(value: string) {
  query.value = value
  open.value = true
  highlightedIndex.value = 0
  if (value === '') emit('update:modelValue', '')
}

function pick(u: StudyUser) {
  emit('update:modelValue', u.username)
  query.value = u.username
  open.value = false
  highlightedIndex.value = 0
}

function closeOnly() {
  open.value = false
}

function onBlur() {
  // Defer so a click on a dropdown row can fire before the listbox unmounts.
  setTimeout(() => {
    open.value = false
    if (props.modelValue && query.value !== props.modelValue) {
      query.value = props.modelValue
    }
  }, 120)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    if (open.value) {
      e.preventDefault()
      closeOnly()
    }
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!open.value) {
      open.value = true
      highlightedIndex.value = 0
      return
    }
    const max = matches.value.length
    if (max === 0) return
    highlightedIndex.value = (highlightedIndex.value + 1) % max
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (!open.value) {
      open.value = true
      highlightedIndex.value = 0
      return
    }
    const max = matches.value.length
    if (max === 0) return
    highlightedIndex.value = (highlightedIndex.value - 1 + max) % max
    return
  }
  if (e.key === 'Enter') {
    if (!open.value) return
    const target = matches.value[highlightedIndex.value]
    if (target) {
      e.preventDefault()
      pick(target)
    }
  }
}

// Reset the highlighted index when the matches list shrinks below it.
watch(matches, (next) => {
  if (highlightedIndex.value >= next.length) highlightedIndex.value = 0
})

defineExpose({
  focus: async () => {
    await nextTick()
    inputEl.value?.focus()
  },
})
</script>

<template>
  <div class="relative" @keydown="onKeydown">
    <div
      role="combobox"
      :aria-expanded="open"
      :aria-controls="listboxId"
      aria-haspopup="listbox"
      :aria-owns="listboxId"
    >
      <TextInput
        :id="props.id"
        :model-value="query"
        :placeholder="placeholderText"
        :disabled="props.disabled"
        autocomplete="off"
        :class="props.inputClass"
        @update:model-value="onInput"
        @focus="onFocus"
        @blur="onBlur"
      />
      <span
        v-if="showGhost && selectedUser"
        class="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-slate-400 truncate max-w-[55%] text-right"
        aria-hidden="true"
      >
        {{ selectedUser.displayName }}
      </span>
    </div>

    <ul
      v-if="open && !props.disabled"
      :id="listboxId"
      role="listbox"
      class="absolute z-30 mt-1 w-full max-h-72 overflow-y-auto rounded-md border border-slate-200 bg-white shadow-lg text-xs"
    >
      <li
        v-if="matches.length === 0"
        class="px-3 py-2 text-slate-500 italic"
        role="presentation"
      >
        {{ t('crfEntry.userAutocomplete.noMatches') }}
      </li>
      <li
        v-for="(u, i) in matches"
        :id="optionId(i)"
        :key="u.username"
        role="option"
        :aria-selected="i === highlightedIndex"
        :class="[
          'px-3 py-2 flex items-center gap-2 cursor-pointer',
          i === highlightedIndex ? 'bg-muw-blue-50 text-muw-blue' : 'text-slate-800 hover:bg-slate-50',
        ]"
        @mousedown.prevent="pick(u)"
        @mouseenter="highlightedIndex = i"
      >
        <span class="font-medium truncate">{{ u.displayName }}</span>
        <span class="font-mono text-slate-500 truncate">{{ u.username }}</span>
      </li>
    </ul>
  </div>
</template>

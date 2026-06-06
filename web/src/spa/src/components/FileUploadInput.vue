<script setup lang="ts">
/**
 * Phase E.6 — file-upload primitive for CRF items whose schema declares
 * {@code dataType: 'file'}.
 *
 * <p>Three states:
 *   1. Empty — show the dropzone + a hidden file picker triggered by
 *      the "Browse" button.
 *   2. Selected (pre-upload) — show filename + size + Upload / Clear.
 *   3. Persisted — show the stored filename + size + Replace / Remove.
 *
 * <p>Validation: the parent passes {@code maxBytes} and
 * {@code allowedExtensions} (csv) so the widget can give immediate
 * feedback before the multipart POST lands. The backend re-checks both.
 *
 * <p>The widget never calls the network itself — it only emits
 * {@code update:file} (a pre-upload File) or {@code clear} for
 * already-stored refs. The parent (typically the CRF entry view) calls
 * {@code store.uploadFile} or {@code store.deleteFile}.
 */

import { computed, ref } from 'vue'

interface StoredFileRef {
  filename: string
  bytes: number
  contentType?: string | null
  storedPath?: string
}

interface Props {
  /** When set, the widget renders the persisted-file state. */
  modelValue: StoredFileRef | null | undefined
  idPrefix: string
  /** Hard upper bound the server enforces; 0 disables the SPA pre-check. */
  maxBytes: number
  /** Comma-joined allowlist (e.g. "pdf,jpg,jpeg,png,tif"). */
  allowedExtensions: string
  /** i18n keys; rendered by parent — keeps the widget i18n-agnostic. */
  dropPromptLabel: string
  browseLabel: string
  uploadingLabel: string
  removeLabel: string
  replaceLabel: string
  tooBigMessage: string
  badExtensionMessage: string
  busy?: boolean
  disabled?: boolean
  error?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  busy: false,
  disabled: false,
  error: false,
})

const emit = defineEmits<{
  /** A File picked by the user; parent should call store.uploadFile. */
  (e: 'upload', file: File): void
  /** Remove a previously-uploaded file (or cancel pre-upload selection). */
  (e: 'clear'): void
}>()

const inputRef = ref<HTMLInputElement | null>(null)
const localError = ref<string | null>(null)
const isDragOver = ref<boolean>(false)

const allowList = computed<string[]>(() =>
  props.allowedExtensions
    ? props.allowedExtensions
        .split(',')
        .map((s) => s.trim().toLowerCase())
        .filter(Boolean)
    : [],
)

const acceptAttr = computed<string>(() =>
  allowList.value.map((ext) => `.${ext}`).join(','),
)

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

function pickFile(): void {
  inputRef.value?.click()
}

function validateAndEmit(file: File): boolean {
  localError.value = null
  if (props.maxBytes > 0 && file.size > props.maxBytes) {
    localError.value = props.tooBigMessage
    return false
  }
  if (allowList.value.length > 0) {
    const ext = (file.name.split('.').pop() ?? '').toLowerCase()
    if (!ext || !allowList.value.includes(ext)) {
      localError.value = props.badExtensionMessage
      return false
    }
  }
  emit('upload', file)
  return true
}

function onPicked(ev: Event): void {
  const target = ev.target as HTMLInputElement
  const f = target.files?.[0]
  if (f) validateAndEmit(f)
  // Reset so picking the same filename twice re-fires.
  target.value = ''
}

function onDrop(ev: DragEvent): void {
  ev.preventDefault()
  isDragOver.value = false
  if (props.disabled || props.busy) return
  const f = ev.dataTransfer?.files?.[0]
  if (f) validateAndEmit(f)
}

function onDragOver(ev: DragEvent): void {
  ev.preventDefault()
  if (props.disabled || props.busy) return
  isDragOver.value = true
}

function onDragLeave(): void {
  isDragOver.value = false
}

function onClear(): void {
  localError.value = null
  emit('clear')
}
</script>

<template>
  <div class="space-y-1.5">
    <!-- Persisted state — show the stored ref + Replace / Remove. -->
    <div
      v-if="modelValue"
      class="flex items-center justify-between gap-3 rounded-md border border-slate-200 bg-slate-50 px-3 py-2"
    >
      <div class="flex items-center gap-2 min-w-0">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="text-slate-500" aria-hidden="true">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
        </svg>
        <span class="text-xs text-slate-700 truncate" :title="modelValue.filename">{{ modelValue.filename }}</span>
        <span class="text-[11px] text-slate-500 shrink-0">({{ formatBytes(modelValue.bytes) }})</span>
      </div>
      <div class="flex items-center gap-2 shrink-0">
        <button
          type="button"
          class="text-[11px] text-muw-blue hover:underline disabled:text-slate-300 disabled:no-underline"
          :disabled="disabled || busy"
          @click="pickFile"
        >{{ replaceLabel }}</button>
        <button
          type="button"
          class="text-[11px] text-rose-700 hover:underline disabled:text-slate-300 disabled:no-underline"
          :disabled="disabled || busy"
          @click="onClear"
        >{{ removeLabel }}</button>
      </div>
    </div>

    <!-- Empty state — dropzone + Browse trigger. -->
    <div
      v-else
      class="flex items-center justify-between gap-3 rounded-md border-2 border-dashed px-3 py-3 transition-colors"
      :class="[
        isDragOver ? 'border-muw-blue bg-muw-blue-50/40' : 'border-slate-300 bg-white',
        error || localError ? 'border-rose-300 bg-rose-50/40' : '',
        disabled || busy ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer',
      ]"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
      @click.self="pickFile"
    >
      <span class="text-xs text-slate-600">{{ dropPromptLabel }}</span>
      <button
        type="button"
        class="text-[11px] px-2.5 py-1 border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700 disabled:text-slate-300"
        :disabled="disabled || busy"
        @click.stop="pickFile"
      >
        {{ busy ? uploadingLabel : browseLabel }}
      </button>
    </div>

    <input
      :id="idPrefix"
      ref="inputRef"
      type="file"
      class="sr-only"
      :accept="acceptAttr || undefined"
      :disabled="disabled || busy"
      @change="onPicked"
    />

    <p v-if="localError" class="text-[11px] text-rose-700">{{ localError }}</p>
  </div>
</template>

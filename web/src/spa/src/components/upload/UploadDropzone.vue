<script setup lang="ts">
/**
 * DR-029 — the combined uploader's dropzone.
 *
 * The OCT dropzone in both of its modes (hero on first paint, slim strip once
 * files are on the page), taking every kind the page handles. The browse
 * input's {@code accept} is a hint for the file picker, not a gate: the
 * store reads the bytes and says what a file is, and a file it cannot name is
 * shown refused rather than silently dropped. On a phone the {@code capture}
 * -less input lets the browser offer the camera roll.
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

interface Props {
  mode: 'hero' | 'slim'
}

const props = defineProps<Props>()
const emit = defineEmits<{ 'files-added': [files: File[]] }>()

const ACCEPT = '.e2e,.dcm,.dicom,image/jpeg,image/png'

const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)

function emitFiles(files: FileList | File[] | null): void {
  if (!files) return
  const list = Array.from(files)
  if (list.length === 0) return
  emit('files-added', list)
}

function onClickBrowse(): void {
  fileInput.value?.click()
}

function onInputChange(e: Event): void {
  const target = e.target as HTMLInputElement
  emitFiles(target.files)
  target.value = ''
}

function onDrop(e: DragEvent): void {
  e.preventDefault()
  isDragging.value = false
  emitFiles(e.dataTransfer?.files ?? null)
}

function onDragOver(e: DragEvent): void {
  e.preventDefault()
  isDragging.value = true
}

function onDragLeave(): void {
  isDragging.value = false
}
</script>

<template>
  <div
    v-if="props.mode === 'slim'"
    class="flex items-center gap-3 rounded-xl border border-dashed border-muw-blue-200 bg-muw-blue-50/50 px-4 py-3 mb-5"
    :class="{ 'ring-2 ring-muw-blue-300': isDragging }"
    data-testid="upload-dropzone-slim"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <span class="text-muw-blue-400">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="17 8 12 3 7 8" />
        <line x1="12" x2="12" y1="3" y2="15" />
      </svg>
    </span>
    <div class="text-[13px] text-slate-600">{{ t('uploadPortal.dropzone.slimDropMore') }}</div>
    <button
      type="button"
      class="ml-auto px-3 py-1.5 text-[12px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 min-h-8"
      @click="onClickBrowse"
    >{{ t('uploadPortal.dropzone.browse') }}</button>
    <input
      ref="fileInput"
      type="file"
      class="hidden"
      multiple
      :accept="ACCEPT"
      data-testid="upload-dropzone-input"
      @change="onInputChange"
    />
  </div>

  <div
    v-else
    class="rounded-2xl border-2 border-dashed border-slate-300 bg-slate-50/60 flex flex-col items-center justify-center text-center px-6 py-10"
    :class="{ 'border-muw-blue-400 bg-muw-blue-50/40': isDragging }"
    data-testid="upload-dropzone-hero"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div class="w-16 h-16 rounded-2xl flex items-center justify-center mb-4 bg-white text-muw-blue ring-1 ring-slate-200">
      <svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M12 13v8M8 17l4-4 4 4" />
        <path d="M20 16.58A5 5 0 0 0 18 7h-1.26A8 8 0 1 0 4 15.25" />
      </svg>
    </div>
    <div class="text-[17px] font-semibold text-slate-800">{{ t('uploadPortal.dropzone.heroTitle') }}</div>
    <div class="text-[13px] text-slate-500 mt-1.5">
      {{ t('uploadPortal.dropzone.heroBrowsePrefix') }}
      <button
        type="button"
        class="text-muw-blue font-medium underline underline-offset-2 hover:text-muw-blue-700 min-h-8 px-1"
        @click="onClickBrowse"
      >{{ t('uploadPortal.dropzone.heroBrowse') }}</button>
    </div>
    <div class="flex flex-wrap items-center justify-center gap-2 mt-5 text-[12px] text-slate-400">
      <span class="inline-flex items-center px-2.5 py-1 rounded-full bg-white ring-1 ring-slate-200 font-mono">.e2e</span>
      <span class="inline-flex items-center px-2.5 py-1 rounded-full bg-white ring-1 ring-slate-200 font-mono">.dcm</span>
      <span class="inline-flex items-center px-2.5 py-1 rounded-full bg-white ring-1 ring-slate-200 font-mono">JPEG · PNG</span>
      <span>{{ t('uploadPortal.dropzone.heroNote') }}</span>
    </div>
    <input
      ref="fileInput"
      type="file"
      class="hidden"
      multiple
      :accept="ACCEPT"
      data-testid="upload-dropzone-input"
      @change="onInputChange"
    />
  </div>
</template>

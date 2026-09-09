<script setup lang="ts">
/**
 * DR-025 — public image-upload portal dropzone (Remidio FOP).
 *
 * Single-file variant of the OCT portal's E2eDropzone: drag-and-drop +
 * click-to-browse a JPEG/PNG, surfaced through one `file-selected` emit. On a
 * phone the hidden <input accept="image/*" capture> also lets the browser offer
 * the camera roll / camera directly.
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const emit = defineEmits<{ 'file-selected': [file: File] }>()

const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)

function emitFirst(files: FileList | File[] | null): void {
  if (!files) return
  const list = Array.from(files)
  if (list.length === 0) return
  emit('file-selected', list[0])
}

function onClickBrowse(): void {
  fileInput.value?.click()
}

function onInputChange(e: Event): void {
  const target = e.target as HTMLInputElement
  emitFirst(target.files)
  target.value = ''
}

function onDrop(e: DragEvent): void {
  e.preventDefault()
  isDragging.value = false
  emitFirst(e.dataTransfer?.files ?? null)
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
    class="rounded-2xl border-2 border-dashed border-slate-300 bg-slate-50/60 flex flex-col items-center justify-center text-center px-6"
    :class="{ 'border-muw-blue-400 bg-muw-blue-50/40': isDragging }"
    style="min-height: 220px"
    data-testid="image-dropzone"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div class="w-16 h-16 rounded-2xl flex items-center justify-center mb-4 bg-white text-muw-blue ring-1 ring-slate-200">
      <svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <rect x="3" y="3" width="18" height="18" rx="2" />
        <circle cx="9" cy="9" r="2" />
        <path d="m21 15-3.5-3.5a2 2 0 0 0-3 0L5 21" />
      </svg>
    </div>
    <div class="text-[17px] font-semibold text-slate-800">{{ t('imagePortal.heroTitle') }}</div>
    <div class="text-[13px] text-slate-500 mt-1.5">
      {{ t('imagePortal.heroSubtitle') }}
      <button
        type="button"
        class="text-muw-blue font-medium underline underline-offset-2 hover:text-muw-blue-700"
        @click="onClickBrowse"
      >{{ t('imagePortal.heroBrowse') }}</button>
    </div>
    <div class="mt-4 text-[12px] text-slate-400">{{ t('imagePortal.formatNote') }}</div>
    <input
      ref="fileInput"
      type="file"
      class="hidden"
      accept="image/jpeg,image/png"
      data-testid="image-dropzone-input"
      @change="onInputChange"
    />
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useConfirmStore } from '@/stores/confirm'

/**
 * UX sweep (#19, 2026-08-12) — singleton confirmation modal, mounted once
 * in {@link App.vue}. Renders whenever {@link useConfirmStore.current} is
 * set (driven by the {@code useConfirm()} composable) and resolves the
 * awaiting promise on confirm / cancel.
 *
 * Accessibility: {@code role="dialog"} + {@code aria-modal}, labelled by its
 * heading; the confirm button auto-focuses on open, Escape cancels, and a
 * backdrop click cancels. Enter on the focused confirm button confirms
 * (native button behaviour).
 */
const store = useConfirmStore()
const { t } = useI18n()

const confirmBtn = ref<HTMLButtonElement | null>(null)

watch(
  () => store.current?.id ?? null,
  async (id) => {
    if (id == null) return
    await nextTick()
    confirmBtn.value?.focus()
  },
)

function onConfirm(): void {
  store.respond(true)
}
function onCancel(): void {
  store.respond(false)
}
function onBackdrop(e: MouseEvent): void {
  if (e.target === e.currentTarget) onCancel()
}
function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.preventDefault()
    onCancel()
  }
}
</script>

<template>
  <div
    v-if="store.current"
    class="fixed inset-0 z-[70] flex items-center justify-center bg-slate-900/40 px-4"
    data-testid="confirm-dialog-backdrop"
    @click="onBackdrop"
    @keydown="onKeydown"
  >
    <div
      class="w-full max-w-md rounded-lg border border-slate-200 bg-white shadow-xl"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
      data-testid="confirm-dialog"
    >
      <div class="px-5 py-4">
        <h2 id="confirm-dialog-title" class="text-sm font-semibold text-slate-900">
          {{ store.current.title || t('common.confirmTitle') }}
        </h2>
        <p class="mt-2 text-sm text-slate-600 leading-relaxed" data-testid="confirm-dialog-message">
          {{ store.current.message }}
        </p>
      </div>
      <div class="flex items-center justify-end gap-2 border-t border-slate-200 px-5 py-3">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-muw-blue"
          data-testid="confirm-dialog-cancel"
          @click="onCancel"
        >
          {{ store.current.cancelLabel || t('common.cancel') }}
        </button>
        <button
          ref="confirmBtn"
          type="button"
          class="px-4 py-1.5 text-xs rounded-md font-medium text-white focus:outline-none focus-visible:ring-2"
          :class="store.current.danger
            ? 'bg-muw-coral-700 hover:bg-muw-coral-700/90 focus-visible:ring-muw-coral-700'
            : 'bg-muw-blue hover:bg-muw-blue-700 focus-visible:ring-muw-blue'"
          data-testid="confirm-dialog-confirm"
          @click="onConfirm"
        >
          {{ store.current.confirmLabel || t('common.confirm') }}
        </button>
      </div>
    </div>
  </div>
</template>

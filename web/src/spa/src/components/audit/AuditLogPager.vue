<script setup lang="ts">
import { useI18n } from 'vue-i18n'

/**
 * Page controls of an audit log. The log is newest first, so the previous
 * page holds newer rows and the next page older ones.
 */
defineProps<{
  /** 0-based. */
  page: number
  pageCount: number
  totalCount: number
  hasPrevious: boolean
  hasNext: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  previous: []
  next: []
}>()

const { t } = useI18n()
</script>

<template>
  <nav
    v-if="totalCount > 0"
    class="flex items-center justify-center gap-3 text-xs text-slate-600 mt-6"
    :aria-label="t('auditLog.pager.label')"
  >
    <button
      type="button"
      data-testid="audit-page-previous"
      class="px-3 py-1.5 border border-slate-200 rounded-md bg-white hover:bg-slate-50 disabled:opacity-50 disabled:cursor-not-allowed"
      :disabled="!hasPrevious || disabled"
      @click="emit('previous')"
    >{{ t('auditLog.pager.previous') }}</button>
    <span data-testid="audit-page-position">{{ t('auditLog.pager.position', { page: page + 1, pages: pageCount }) }}</span>
    <button
      type="button"
      data-testid="audit-page-next"
      class="px-3 py-1.5 border border-slate-200 rounded-md bg-white hover:bg-slate-50 disabled:opacity-50 disabled:cursor-not-allowed"
      :disabled="!hasNext || disabled"
      @click="emit('next')"
    >{{ t('auditLog.pager.next') }}</button>
  </nav>
</template>

<script setup lang="ts">
/**
 * An audit row's recorded values.
 *
 * Both values: the before/after diff card. One value: that value on its own.
 * A row with only an "after" value records something, such as a failure's
 * error, a sweep's summary or a first status, and is labelled "Value". A row
 * with only a "before" value records what was removed, such as a deleted
 * value or a reopened CRF's completion time, and is labelled "Before". Until
 * 2026-09-27 the audit views showed values only when a row had both, so
 * these rows showed nothing.
 */
import { useI18n } from 'vue-i18n'

import DiffCard from '@/components/DiffCard.vue'

defineProps<{
  before?: string | null
  after?: string | null
}>()

const { t } = useI18n()
</script>

<template>
  <DiffCard
    v-if="before != null && after != null"
    :before-label="t('auditLog.values.before')"
    :after-label="t('auditLog.values.after')"
  >
    <template #before>{{ before }}</template>
    <template #after>{{ after }}</template>
  </DiffCard>
  <div
    v-else-if="before != null || after != null"
    class="bg-slate-50 border border-slate-200 rounded p-2 text-xs"
    data-testid="audit-single-value"
  >
    <div class="text-[10px] uppercase tracking-wider text-slate-600 font-semibold">
      {{ before != null ? t('auditLog.values.before') : t('auditLog.values.value') }}
    </div>
    <div class="font-mono text-slate-900 mt-0.5 whitespace-pre-wrap break-words">{{ before ?? after }}</div>
  </div>
</template>

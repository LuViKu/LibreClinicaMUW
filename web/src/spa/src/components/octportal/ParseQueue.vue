<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — parse queue.
 *
 * Wraps a collection of {@link ParseRow}s in the mockup's bordered
 * card (oct-portal.jsx ~ line 310). Renders the "Verarbeitung · X
 * von Y Dateien gelesen" header + the file-type hint on the right.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import ParseRow from './ParseRow.vue'
import type { ReviewRow } from '@/stores/octPortal'

const { t } = useI18n()

interface Props {
  rows: ReviewRow[]
}

const props = defineProps<Props>()

const doneCount = computed(() => props.rows.filter((r) => r.scan !== undefined).length)
const totalCount = computed(() => props.rows.length)
</script>

<template>
  <div
    class="border border-slate-200 rounded-xl overflow-hidden bg-white shadow-[0_1px_2px_rgba(17,29,78,0.04)]"
    data-testid="parse-queue"
  >
    <div class="flex items-center justify-between px-5 py-2.5 bg-slate-50 border-b border-slate-100">
      <div class="text-[12px] font-medium text-slate-500">
        {{ t('octPortal.parseQueue.headerLabel', { done: doneCount, total: totalCount }) }}
      </div>
      <div class="text-[11px] text-slate-400">{{ t('octPortal.parseQueue.headerHint') }}</div>
    </div>
    <ParseRow v-for="row in props.rows" :key="row.rowId" :row="row" />
  </div>
</template>

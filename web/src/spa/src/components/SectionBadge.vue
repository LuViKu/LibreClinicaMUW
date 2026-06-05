<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * Phase E.6 crf-entry-advanced — TOC SideRail per-section badge.
 *
 * <p>Composes the server-side {@code SectionStatus} (required +
 * filled + openQueries) with the client-side {@code errorCount}
 * derived from the in-memory entry. Shows:
 *  - {filled}/{required} count
 *  - red error dot when errors > 0
 *  - amber query badge when openQueries > 0
 */

interface Props {
  requiredCount: number
  filledCount: number
  errorCount: number
  openQueries: number
}

const props = defineProps<Props>()
const { t } = useI18n()

const filledComplete = computed(() =>
  props.requiredCount > 0 && props.filledCount >= props.requiredCount && props.errorCount === 0,
)

const fillLabel = computed(() => `${props.filledCount}/${props.requiredCount}`)
</script>

<template>
  <span class="inline-flex items-center gap-1.5 ml-2 align-middle" :aria-label="t('crfEntry.sectionBadge.requiredFilled', { filled: filledCount, required: requiredCount })">
    <span
      v-if="requiredCount > 0"
      class="text-[10px] font-medium tabular-nums px-1 rounded"
      :class="filledComplete ? 'text-emerald-700 bg-emerald-50' : 'text-slate-600 bg-slate-100'"
    >
      {{ fillLabel }}
    </span>
    <span
      v-if="errorCount > 0"
      class="inline-flex items-center gap-0.5 text-[10px] text-rose-700"
      :title="t('crfEntry.sectionBadge.errors', { count: errorCount })"
      :aria-label="t('crfEntry.sectionBadge.errors', { count: errorCount })"
    >
      <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <circle cx="12" cy="12" r="10" />
      </svg>
    </span>
    <span
      v-if="openQueries > 0"
      class="text-[10px] font-medium tabular-nums px-1 rounded bg-amber-100 text-amber-800"
      :title="t('crfEntry.sectionBadge.openQueries', { count: openQueries })"
      :aria-label="t('crfEntry.sectionBadge.openQueries', { count: openQueries })"
    >
      ?{{ openQueries }}
    </span>
  </span>
</template>

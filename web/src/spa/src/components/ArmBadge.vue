<script setup lang="ts">
/**
 * nAMD treat-and-extend Slice 6 (2026-06-20) — visual indicator
 * that an AI panel was gated off by the subject's arm assignment.
 *
 * The platform's honour-system blinding hides the AI metrics on
 * `RetinalMetricsView` when the subject is in Arm B
 * (`AI_HIDDEN`); this badge is what we render in the panel's
 * place so the operator knows the data exists but is intentionally
 * not shown for this subject — not that the AI failed or the scan
 * didn't run.
 *
 * Used at the section level (where the KPI strip / en-face panel
 * would be). Inline use is OK too; the component is borderless and
 * size-agnostic.
 */
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

interface Props {
  /** Optional override of the arm token rendered alongside the label. */
  arm?: 'AI_SHOWN' | 'AI_HIDDEN' | null
}
defineProps<Props>()
</script>

<template>
  <div
    data-testid="arm-badge"
    class="inline-flex items-center gap-2 rounded-muw bg-slate-100 border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700"
  >
    <span
      class="inline-block w-2 h-2 rounded-full"
      :class="arm === 'AI_HIDDEN' ? 'bg-amber-500' : 'bg-emerald-500'"
      aria-hidden="true"
    />
    <span>{{ t('retinal.arm.blindedLabel') }}</span>
    <code
      v-if="arm"
      class="px-1.5 py-0.5 rounded bg-white border border-slate-200 text-[10px] tracking-wider text-slate-600"
    >
      {{ arm }}
    </code>
  </div>
</template>

<script setup lang="ts">
/**
 * Phase E.6 ophth-bilateral — bilateral 3-column row.
 *
 * Renders a single bilateral row inside a CRF section. The layout is
 * deliberately clinician-facing:
 *
 *   | Label                         | OD (right eye)   | OS (left eye) |
 *   | "BCVA letters"                | <widget od />    | <widget os /> |
 *
 * OD = oculus dexter = patient's RIGHT eye → renders LEFT, because the
 * examiner faces the patient. OS = oculus sinister = LEFT eye →
 * renders RIGHT. Deviating from this convention has been shown to
 * cause data-entry errors in chart-driven specialties; see
 * /docs/development/modernization/phase-e/ophth-conventions.md (the
 * convention doc lives in design-system/).
 *
 * The widget itself is owned by the caller: this component takes a
 * scoped slot {@code #widget="{ item, side }"} and renders it inside
 * the OD / OS / both-eyes cell. Single-eye rows (only OD_, only OS_)
 * fall back to a two-column layout with a "OD only" / "OS only"
 * tell in the empty cell, so a unilateral-disease study can still
 * use the bilateral preset without surfacing a missing-data warning.
 *
 * OU (oculus uterque) rows use the {@code both-eyes} variant and
 * render a single widget cell that spans both eye columns.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { BilateralRow } from './bilateral'
import type { CrfItem } from '@/types/crf'

interface Props {
  row: BilateralRow
  /**
   * Phase E.6 polish-runtime — per-eye hide flags driven by show-when.
   * Optional; when set, the corresponding eye renders the same "missing"
   * tell that the bilateral component already shows for un-paired
   * authoring (single OD, single OS) — the operator sees the row but
   * the value isn't editable while the show-when source disagrees.
   * When BOTH eyes are hidden the parent {@code rowsForSection} already
   * dropped the row from the render list, so this branch handles the
   * "one eye hidden, one eye visible" partial case.
   */
  hiddenOd?: boolean
  hiddenOs?: boolean
}

const props = defineProps<Props>()
const { t } = useI18n()

const isBilateral = computed(() => props.row.kind === 'bilateral')
const isBothEyes = computed(() => props.row.kind === 'both-eyes')

const od = computed<CrfItem | null>(() =>
  props.row.kind === 'bilateral' && !props.hiddenOd ? props.row.od : null,
)
const os = computed<CrfItem | null>(() =>
  props.row.kind === 'bilateral' && !props.hiddenOs ? props.row.os : null,
)
const bothEyesItem = computed<CrfItem | null>(() =>
  props.row.kind === 'both-eyes' ? props.row.item : null,
)
const label = computed<string>(() => {
  if (props.row.kind === 'single') return props.row.item.label
  return props.row.label
})

const required = computed<boolean>(() => {
  if (props.row.kind === 'bilateral') {
    return Boolean(props.row.od?.required || props.row.os?.required)
  }
  if (props.row.kind === 'both-eyes') return props.row.item.required
  return props.row.item.required
})

const odOnly = computed(() => isBilateral.value && od.value && !os.value)
const osOnly = computed(() => isBilateral.value && !od.value && os.value)
</script>

<template>
  <div
    class="bilateral-row grid items-start gap-3 py-3 border-t border-slate-100 first:border-t-0"
    :class="isBothEyes ? 'grid-cols-[1fr_2fr]' : 'grid-cols-[1fr_1fr_1fr]'"
    role="group"
    :aria-label="label"
  >
    <!-- Column 1 — row label -->
    <div class="text-xs font-medium text-slate-700 pt-2">
      <span :class="{ req: required }">{{ label }}</span>
      <div v-if="odOnly" class="mt-1 text-[10px] uppercase tracking-wide text-amber-700">
        {{ t('crfEntry.bilateral.odOnlyTell') }}
      </div>
      <div v-if="osOnly" class="mt-1 text-[10px] uppercase tracking-wide text-amber-700">
        {{ t('crfEntry.bilateral.osOnlyTell') }}
      </div>
      <div v-if="isBothEyes" class="mt-1 text-[10px] uppercase tracking-wide text-slate-500">
        {{ t('crfEntry.bilateral.bothEyesTell') }}
      </div>
    </div>

    <!-- Both-eyes row: a single cell that spans both eye columns -->
    <template v-if="isBothEyes && bothEyesItem">
      <div data-bilateral-cell="OU" class="bilateral-cell">
        <slot name="widget" :item="bothEyesItem" side="OU" />
      </div>
    </template>

    <!-- Bilateral row: OD (left column) + OS (right column) -->
    <template v-else>
      <!-- Column 2 — OD (right eye), renders on the LEFT -->
      <div data-bilateral-cell="OD" class="bilateral-cell">
        <template v-if="od">
          <slot name="widget" :item="od" side="OD" />
        </template>
        <div v-else class="text-[11px] text-slate-400 italic px-2 py-2">
          {{ t('crfEntry.bilateral.odMissing') }}
        </div>
      </div>

      <!-- Column 3 — OS (left eye), renders on the RIGHT -->
      <div data-bilateral-cell="OS" class="bilateral-cell">
        <template v-if="os">
          <slot name="widget" :item="os" side="OS" />
        </template>
        <div v-else class="text-[11px] text-slate-400 italic px-2 py-2">
          {{ t('crfEntry.bilateral.osMissing') }}
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — OCT-portal eye badge.
 *
 * Renders the per-row laterality chip in the review queue. Mirrors
 * the mockup's `Eye` primitive (oct-portal.jsx ~ line 28):
 *   - OD = teal-50 / teal-700, letter "R" (right eye)
 *   - OS = sky-50 / sky-700,   letter "L" (left eye)
 *
 * The "OD / R-on-the-left" convention is institutional — see the
 * [[reference_ophth_laterality]] memory note (clinician sits face-to-
 * face with the patient, so the patient's right eye appears on the
 * clinician's LEFT). The badge surfaces the medical short-code (OD/OS)
 * alongside the colloquial letter (R/L) so non-ophth operators don't
 * misread the row.
 */
interface Props {
  /** OD = right eye (teal), OS = left eye (sky). */
  laterality: 'OD' | 'OS' | null
}

const props = defineProps<Props>()
</script>

<template>
  <span v-if="props.laterality === null" class="text-slate-500">—</span>
  <span v-else class="inline-flex items-center gap-1.5">
    <span
      class="inline-flex items-center justify-center w-5 h-5 rounded-md text-[10px] font-bold"
      :class="props.laterality === 'OD'
        ? 'bg-muw-teal-50 text-muw-teal-700'
        : 'bg-muw-sky-50 text-muw-sky-700'"
      :data-testid="`eye-badge-letter-${props.laterality}`"
    >{{ props.laterality === 'OD' ? 'R' : 'L' }}</span>
    <span class="text-[13px] font-medium text-slate-700">{{ props.laterality }}</span>
  </span>
</template>

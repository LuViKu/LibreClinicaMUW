<script setup lang="ts">
/**
 * nAMD primitives — Pill.
 *
 * Port of {@code Pill({ tone, dot, children })} from namd-data.jsx. Six
 * tone variants drive the colour scheme — clinical conventions:
 *   - {@code active} → coral, "Exsudation aktiv" (rising fluid is bad)
 *   - {@code dry}    → teal, "Trocken" (good outcome)
 *   - {@code ai}     → blue, "KI-Segmentierung" (AI affordance)
 *   - {@code warn}   → amber, watchful waiting
 *   - {@code mute}   → slate, neutral metadata
 *   - {@code sky}    → sky blue, informational
 *
 * A leading dot is optional — disabled when the pill carries its own icon
 * (e.g. the AI sparkle on the segmentation card).
 */
type Tone = 'active' | 'dry' | 'ai' | 'warn' | 'mute' | 'sky'

interface Props {
  tone?: Tone
  /** Leading filled dot — default true; pass false when the pill carries an icon. */
  dot?: boolean
}

const props = withDefaults(defineProps<Props>(), { tone: 'mute', dot: true })

const TONE_CLASSES: Record<Tone, { wrap: string; dot: string }> = {
  active: { wrap: 'bg-muw-coral-50 text-muw-coral-700', dot: 'bg-muw-coral-600' },
  dry: { wrap: 'bg-muw-teal-50 text-muw-teal-700', dot: 'bg-muw-teal-600' },
  ai: { wrap: 'bg-muw-blue-50 text-muw-blue-700', dot: 'bg-muw-blue-600' },
  warn: { wrap: 'bg-amber-50 text-amber-700', dot: 'bg-amber-500' },
  mute: { wrap: 'bg-slate-100 text-slate-600', dot: 'bg-slate-400' },
  sky: { wrap: 'bg-muw-sky-50 text-muw-sky-700', dot: 'bg-muw-sky-600' },
}
</script>

<template>
  <span
    :data-testid="`namd-pill-${props.tone}`"
    class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-semibold"
    :class="TONE_CLASSES[props.tone].wrap"
  >
    <span
      v-if="props.dot"
      class="inline-block w-1.5 h-1.5 rounded-full"
      :class="TONE_CLASSES[props.tone].dot"
    />
    <slot />
  </span>
</template>

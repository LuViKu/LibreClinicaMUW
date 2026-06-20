<script setup lang="ts">
/**
 * nAMD primitives — DeltaChip.
 *
 * Visit-to-visit delta indicator. Port of {@code Delta({ value, direction })}
 * from namd-data.jsx. Direction expresses what the clinically "good"
 * direction is:
 *   - {@code badUp}  → rising is bad (fluid biomarkers, CRT) → red chip on rise.
 *   - {@code goodUp} → rising is good (BCVA letters) → teal chip on rise.
 *
 * A zero delta renders the neutral grey chip with a "◆" glyph; the
 * design uses the same chip across SegCards, CompareDeltaBar and the
 * Report tab summary row.
 */
type Direction = 'badUp' | 'goodUp'

interface Props {
  /** Δ value (current minus previous). */
  value: number | null | undefined
  /** Clinically "good" direction — drives the colour scheme. */
  direction?: Direction
  /** Optional formatter — default is {@code (n) => n.toFixed(0)}. */
  format?: (n: number) => string
  /** Optional unit suffix rendered after the formatted value. */
  unit?: string
}

const props = withDefaults(defineProps<Props>(), {
  direction: 'badUp',
  format: (n: number) => `${n >= 0 ? '+' : ''}${n.toFixed(0)}`,
  unit: '',
})

function tone(value: number | null | undefined, direction: Direction): string {
  if (value == null || Math.abs(value) < 1e-9) {
    return 'bg-slate-100 text-slate-500'
  }
  const rising = value > 0
  const bad = direction === 'badUp' ? rising : !rising
  return bad
    ? 'bg-muw-coral-50 text-muw-coral-700'
    : 'bg-muw-teal-50 text-muw-teal-700'
}

function arrow(value: number | null | undefined): string {
  if (value == null || Math.abs(value) < 1e-9) return '◆'
  return value > 0 ? '▲' : '▼'
}
</script>

<template>
  <span
    data-testid="namd-delta-chip"
    :data-direction="props.direction"
    :data-sign="
      props.value == null || Math.abs(props.value) < 1e-9
        ? 'zero'
        : props.value > 0
          ? 'up'
          : 'down'
    "
    class="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-semibold tabular-nums"
    :class="tone(props.value, props.direction)"
  >
    <span aria-hidden="true">{{ arrow(props.value) }}</span>
    <span v-if="props.value != null">{{ props.format(props.value) }}{{ props.unit }}</span>
    <span v-else>—</span>
  </span>
</template>

<script setup lang="ts">
/**
 * nAMD primitives — FluidDot.
 *
 * Coloured swatch keyed to the three fluid biomarkers IRF / SRF / PED.
 * Port of {@code FluidDot({ k, size })} from namd-data.jsx — driven by
 * the shared {@code FLUID} lookup that maps each biomarker to its design
 * colour (IRF cyan, SRF amber, PED purple).
 *
 * Used by:
 *   - {@code NamdFluidLegend} — three rows under the OCT-Viewer side panel.
 *   - {@code NamdSegCards} — per-card swatch row.
 *   - {@code NamdFluidTrendChart} — polygon fills + tooltip legend.
 */
import { FLUID, type FluidKey } from '../../fluid'

interface Props {
  /** Biomarker key — IRF / SRF / PED. */
  k: FluidKey
  /** Pixel size of the swatch (square). */
  size?: number
}

const props = withDefaults(defineProps<Props>(), { size: 12 })
</script>

<template>
  <span
    :data-testid="`namd-fluid-dot-${props.k}`"
    class="inline-block rounded-[3px]"
    :style="{
      width: `${props.size}px`,
      height: `${props.size}px`,
      background: FLUID[props.k].color,
    }"
  />
</template>

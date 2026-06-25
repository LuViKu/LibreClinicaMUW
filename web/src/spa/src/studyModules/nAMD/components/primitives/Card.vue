<script setup lang="ts">
/**
 * nAMD primitives — Card.
 *
 * Port of the design's {@code Card({ title, right, children })} primitive
 * (namd-data.jsx). Lightweight white surface with rounded MUW radius, soft
 * card shadow, a small uppercase title row and an optional right-aligned
 * accessory slot (typically an AI / activity pill).
 */
interface Props {
  /** Title rendered in the small uppercase row. Empty hides the header. */
  title?: string
  /** Compact variant — tighter padding, no min-height. */
  compact?: boolean
}

withDefaults(defineProps<Props>(), {
  title: '',
  compact: false,
})
</script>

<template>
  <section
    class="bg-white rounded-muw shadow-muw-card border border-slate-100"
    :class="compact ? 'p-3' : 'p-4'"
    data-testid="namd-card"
  >
    <header
      v-if="title || $slots.right"
      class="flex items-baseline justify-between mb-3"
    >
      <h3 class="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
        {{ title }}
      </h3>
      <div v-if="$slots.right" class="flex items-center gap-1.5">
        <slot name="right" />
      </div>
    </header>
    <slot />
  </section>
</template>

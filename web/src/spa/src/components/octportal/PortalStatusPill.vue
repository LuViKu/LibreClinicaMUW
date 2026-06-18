<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — OCT-portal status pill.
 *
 * Mirrors the mockup's `Pill` primitive (oct-portal.jsx ~ line 120),
 * with five distinct tones:
 *   - suggest = amber  (Vorschläge bestätigen)
 *   - ok      = teal   (Patient gefunden, bestätigt)
 *   - sky     = sky    (ohne Termin / scheduled-but-not-today)
 *   - bad     = rose   (ohne Patient)
 *   - mute    = slate  (Problem / dismissed)
 *
 * Named `PortalStatusPill` so it stays clear of the existing
 * `@/components/StatusPill.vue` primitive — same shape, different
 * tone vocabulary, different mockup semantics.
 */
import { computed } from 'vue'

type Tone = 'suggest' | 'ok' | 'sky' | 'bad' | 'mute'

interface Props {
  tone: Tone
}

const props = defineProps<Props>()

const wrap = computed<string>(() => {
  switch (props.tone) {
    case 'suggest':
      return 'bg-amber-50 text-amber-700'
    case 'ok':
      return 'bg-muw-teal-50 text-muw-teal-700'
    case 'sky':
      return 'bg-muw-sky-50 text-muw-sky-700'
    case 'bad':
      return 'bg-rose-50 text-rose-700'
    case 'mute':
    default:
      return 'bg-slate-100 text-slate-500'
  }
})

const dot = computed<string>(() => {
  switch (props.tone) {
    case 'suggest':
      return 'bg-amber-500'
    case 'ok':
      return 'bg-muw-teal'
    case 'sky':
      return 'bg-muw-sky-500'
    case 'bad':
      return 'bg-rose-500'
    case 'mute':
    default:
      return 'bg-slate-400'
  }
})
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-medium whitespace-nowrap"
    :class="wrap"
    :data-tone="props.tone"
  >
    <span class="w-1.5 h-1.5 rounded-full" :class="dot" aria-hidden="true"></span>
    <slot />
  </span>
</template>

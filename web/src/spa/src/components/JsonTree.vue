<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — Tiny recursive JSON viewer.
 *
 * Drops the dependency on a heavier JSON-tree component (e.g.
 * vue-json-pretty) in favour of a ~50-line recursive {@code <ul>}.
 * Used by the Retinal Metrics viewer to expose the raw
 * {@code output_payload} for the operator + the QA reviewer; clinical
 * decision-making goes through the typed KPIs, this is for the
 * paper trail.
 *
 * <p>Capabilities:
 *   - Primitives render inline with type-coloured text.
 *   - Arrays + objects render with a collapse toggle, default open at
 *     depth 0 and collapsed below depth 2 so the tree doesn't overrun
 *     the page on a large payload (Wave 2's per-B-scan arrays can have
 *     hundreds of entries).
 */
import { ref, computed } from 'vue'

interface Props {
  value: unknown
  /** Recursion depth; consumers always pass 0. */
  depth?: number
  /** Property name being rendered (rendered as a label on the row). */
  name?: string
}

const props = withDefaults(defineProps<Props>(), {
  depth: 0,
  name: '',
})

const isObject = computed(
  () => props.value !== null && typeof props.value === 'object' && !Array.isArray(props.value),
)
const isArray = computed(() => Array.isArray(props.value))
const isComposite = computed(() => isObject.value || isArray.value)

const entries = computed<Array<[string, unknown]>>(() => {
  if (isArray.value) {
    return (props.value as unknown[]).map((v, i) => [String(i), v])
  }
  if (isObject.value) {
    return Object.entries(props.value as Record<string, unknown>)
  }
  return []
})

const expanded = ref<boolean>(props.depth < 2)

function toggle() {
  expanded.value = !expanded.value
}

function primitiveClass(v: unknown): string {
  if (v === null || v === undefined) return 'text-slate-400'
  if (typeof v === 'number') return 'text-emerald-700'
  if (typeof v === 'boolean') return 'text-fuchsia-700'
  if (typeof v === 'string') return 'text-sky-700'
  return 'text-slate-700'
}

function formatPrimitive(v: unknown): string {
  if (v === null) return 'null'
  if (v === undefined) return 'undefined'
  if (typeof v === 'string') return JSON.stringify(v)
  return String(v)
}

const summary = computed(() => {
  if (isArray.value) {
    return `[ ${(props.value as unknown[]).length} ]`
  }
  if (isObject.value) {
    const len = Object.keys(props.value as Record<string, unknown>).length
    return `{ ${len} }`
  }
  return ''
})
</script>

<template>
  <div class="font-mono text-[11px] leading-relaxed">
    <div v-if="isComposite" class="flex items-baseline gap-1">
      <button
        type="button"
        class="text-slate-500 hover:text-slate-800 select-none w-3 text-left"
        :aria-expanded="expanded"
        @click="toggle"
      >{{ expanded ? '▾' : '▸' }}</button>
      <span v-if="name" class="text-slate-600">{{ name }}:</span>
      <span class="text-slate-400">{{ summary }}</span>
    </div>
    <div v-else class="flex items-baseline gap-1.5">
      <span v-if="name" class="text-slate-600">{{ name }}:</span>
      <span :class="primitiveClass(value)">{{ formatPrimitive(value) }}</span>
    </div>

    <ul v-if="isComposite && expanded" class="pl-4 border-l border-slate-100 ml-1.5 mt-0.5">
      <li v-for="[key, val] in entries" :key="key" class="py-0.5">
        <JsonTree :value="val" :name="key" :depth="depth + 1" />
      </li>
    </ul>
  </div>
</template>

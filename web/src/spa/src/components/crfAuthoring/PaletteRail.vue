<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  PALETTE_PRIMITIVES,
  PRESET_CATALOG,
  type PalettePrimitive,
  type PresetDescriptor,
} from './presetCatalog'

/**
 * App-feedback Wave 2 (2026-06-19) — left palette rail.
 *
 * <p>Two sections, top to bottom:
 *
 * <ol>
 *   <li><b>Primitive items</b> — one entry per {@link AuthoringDataType}
 *       the operator can drop straight into a section (ST, INT, REAL,
 *       DATE, BL, TRISTATE_REASON, FILE).</li>
 *   <li><b>Presets</b> — the registry-driven preset catalog (IOP,
 *       OPHTH_EXAM, future entries). Operators drop a preset to
 *       materialise multiple pre-wired items + show-when rules in one
 *       gesture.</li>
 * </ol>
 *
 * <p>The rail is keyboard-friendly: each entry is a {@code <button>}
 * the operator can activate with Enter to bypass HTML5 drag-and-drop
 * (helpful when screen-readers are in play). Drag-and-drop continues to
 * work via the HTML5 DataTransfer payload — the canvas reads
 * {@code application/x-crf-palette} on drop.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code primitive-activated} — keyboard-activated primitive.</li>
 *   <li>{@code preset-activated} — keyboard-activated preset.</li>
 * </ul>
 */

const { t } = useI18n()

const emit = defineEmits<{
  'primitive-activated': [primitive: PalettePrimitive]
  'preset-activated': [preset: PresetDescriptor]
}>()

const primitives = computed(() => PALETTE_PRIMITIVES)
const presets = computed(() => PRESET_CATALOG)

/**
 * Build a drag payload string. The canvas drop handler parses this
 * back into {kind, value}. Keeping the wire format JSON keeps the
 * future-proofing trivial — additional drag sources (e.g. ophth
 * catalog rows) can co-exist by adding a kind.
 */
function makeDragPayload(kind: 'primitive', dataType: string): string
function makeDragPayload(kind: 'preset', presetId: string): string
function makeDragPayload(kind: 'primitive' | 'preset', value: string): string {
  return JSON.stringify({ kind, value })
}

function onPrimitiveDragStart(ev: DragEvent, p: PalettePrimitive): void {
  if (!ev.dataTransfer) return
  ev.dataTransfer.effectAllowed = 'copy'
  ev.dataTransfer.setData(
    'application/x-crf-palette',
    makeDragPayload('primitive', p.dataType),
  )
  // Some browsers fall back to text/plain for non-registered MIME
  // types; mirror so dragover handlers can sniff either.
  ev.dataTransfer.setData(
    'text/plain',
    makeDragPayload('primitive', p.dataType),
  )
}

function onPresetDragStart(ev: DragEvent, p: PresetDescriptor): void {
  if (!ev.dataTransfer) return
  ev.dataTransfer.effectAllowed = 'copy'
  ev.dataTransfer.setData(
    'application/x-crf-palette',
    makeDragPayload('preset', p.id),
  )
  ev.dataTransfer.setData(
    'text/plain',
    makeDragPayload('preset', p.id),
  )
}
</script>

<template>
  <aside
    class="w-56 shrink-0 border-r border-slate-200 bg-slate-50/40 overflow-y-auto"
    data-testid="crf-canvas-palette-rail"
  >
    <div class="p-3 space-y-4">
      <h3 class="text-xs font-semibold uppercase tracking-wider text-slate-600">
        {{ t('crfAuthoring.canvas.palette.title') }}
      </h3>

      <!-- Primitive items -->
      <section data-testid="crf-canvas-palette-primitives">
        <h4 class="text-[11px] font-semibold uppercase tracking-wider text-slate-500 mb-1.5">
          {{ t('crfAuthoring.canvas.palette.primitives') }}
        </h4>
        <ul class="space-y-1.5">
          <li v-for="p in primitives" :key="p.dataType">
            <button
              type="button"
              class="w-full flex flex-col text-left px-2.5 py-1.5 rounded-md border border-slate-200 bg-white hover:border-muw-blue hover:bg-muw-blue/5 cursor-grab"
              :draggable="true"
              :data-testid="`crf-canvas-palette-prim-${p.dataType}`"
              @dragstart="(ev) => onPrimitiveDragStart(ev, p)"
              @click="emit('primitive-activated', p)"
              @keydown.enter.prevent="emit('primitive-activated', p)"
            >
              <span class="text-[12px] font-medium text-slate-800">{{ t(p.labelKey) }}</span>
              <span class="text-[10px] text-slate-500">{{ t(p.descriptionKey) }}</span>
            </button>
          </li>
        </ul>
      </section>

      <!-- Presets -->
      <section data-testid="crf-canvas-palette-presets">
        <h4 class="text-[11px] font-semibold uppercase tracking-wider text-slate-500 mb-1.5">
          {{ t('crfAuthoring.canvas.palette.presets') }}
        </h4>
        <ul class="space-y-1.5">
          <li v-for="p in presets" :key="p.id">
            <button
              type="button"
              class="w-full flex flex-col text-left px-2.5 py-1.5 rounded-md border border-slate-200 bg-white hover:border-muw-blue hover:bg-muw-blue/5 cursor-grab"
              :draggable="true"
              :data-testid="`crf-canvas-palette-preset-${p.id}`"
              @dragstart="(ev) => onPresetDragStart(ev, p)"
              @click="emit('preset-activated', p)"
              @keydown.enter.prevent="emit('preset-activated', p)"
            >
              <span class="text-[12px] font-medium text-slate-800">{{ t(p.labelKey) }}</span>
              <span class="text-[10px] text-slate-500 leading-snug mt-0.5">
                {{ t(p.descriptionKey) }}
              </span>
            </button>
          </li>
        </ul>
      </section>
    </div>
  </aside>
</template>

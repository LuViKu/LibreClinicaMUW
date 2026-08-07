<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import type { ComputedRef } from 'vue'
import { useI18n } from 'vue-i18n'
import draggable from 'vuedraggable'
import { CrfAuthoringErrorsKey } from './errorsInjection'

import {
  useCrfAuthoringStore,
  type AuthoringItem,
  type AuthoringSection,
} from '@/stores/crfAuthoring'
import { findPalettePrimitive, findPreset } from './presetCatalog'

/**
 * Middle canvas — renders each draft section as a drop-target card.
 * Operators drop palette primitives (DataTransfer MIME
 * {@code application/x-crf-palette}) or presets ({@code store.applyPreset}),
 * reorder items via vuedraggable, select items into the right rail, and
 * add/remove/rename sections.
 *
 * <p>Bilateral OD/OS pairing on read: when {@code section.bilateral === true}
 * the renderer surfaces paired items in a two-column grid — OD on the LEFT,
 * OS on the RIGHT (face-to-face clinician convention). Pairing key is the
 * OID suffix after the {@code OD_} / {@code OS_} prefix.
 */

const { t } = useI18n()
const store = useCrfAuthoringStore()

/**
 * Collapsed-section state — client-side only (not persisted), keyed on
 * section.uid so re-orders keep the collapse state stable.
 */
const collapsedSections = ref<Set<string>>(new Set())

function isSectionCollapsed(sectionUid: string): boolean {
  return collapsedSections.value.has(sectionUid)
}

function toggleSectionCollapsed(sectionUid: string): void {
  const next = new Set(collapsedSections.value)
  if (next.has(sectionUid)) next.delete(sectionUid)
  else next.add(sectionUid)
  collapsedSections.value = next
}

/**
 * Section reorder via drag handle — forwards to store.reorderSections(),
 * which re-numbers ordinals so the persisted payload stays contiguous.
 */
function onSectionReorder(reordered: typeof store.draft.sections): void {
  store.reorderSections(reordered as AuthoringSection[])
}

/**
 * Per-field error map provided by CrfAuthoringCanvasView. Defaults to an
 * empty computed so item rows render normally when no validation has
 * been attempted yet.
 */
const fieldErrors = inject<ComputedRef<Record<string, string>>>(
  CrfAuthoringErrorsKey,
  computed(() => ({})),
)

/**
 * Look up an item's error by OID. Used by the item-row v-class +
 * inline error message under the row. Returns {@code undefined} when
 * the item is clean (or the OID is blank — defensive).
 */
function errorForItem(oid: string): string | undefined {
  if (!oid) return undefined
  return fieldErrors.value[oid]
}

const sections = computed(() => store.draft.sections)

interface PalettePayload {
  kind: 'primitive' | 'preset'
  value: string
}

/**
 * Parse the drag payload. Some browsers fall back to text/plain when
 * the application MIME type isn't recognised, so we try both.
 */
function readPalettePayload(ev: DragEvent): PalettePayload | null {
  if (!ev.dataTransfer) return null
  const raw =
    ev.dataTransfer.getData('application/x-crf-palette') ||
    ev.dataTransfer.getData('text/plain')
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as PalettePayload
    if (
      (parsed.kind === 'primitive' || parsed.kind === 'preset') &&
      typeof parsed.value === 'string'
    ) {
      return parsed
    }
  } catch {
    return null
  }
  return null
}

/**
 * Track which section is currently dragged-over so we can highlight
 * the drop target. Cleared on dragleave / drop.
 */
const dragOverSectionUid = ref<string | null>(null)

function onSectionDragOver(ev: DragEvent, sectionUid: string): void {
  // Suppress default to allow drop.
  ev.preventDefault()
  if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'copy'
  dragOverSectionUid.value = sectionUid
}

function onSectionDragLeave(_ev: DragEvent, sectionUid: string): void {
  if (dragOverSectionUid.value === sectionUid) {
    dragOverSectionUid.value = null
  }
}

function onSectionDrop(ev: DragEvent, sectionIndex: number): void {
  ev.preventDefault()
  dragOverSectionUid.value = null
  const payload = readPalettePayload(ev)
  if (!payload) return
  const section = store.draft.sections[sectionIndex]
  if (!section) return
  if (payload.kind === 'primitive') {
    // Resolve via the palette registry so choice blocks (whose id is NOT
    // a data type) seed correctly. Fall back to the bare {dataType}
    // shape for any older drag source still emitting a raw type code.
    const prim = findPalettePrimitive(payload.value)
    store.addItem(
      sectionIndex,
      prim ? prim.seed() : { dataType: payload.value as AuthoringItem['dataType'] },
    )
    // Select the freshly added item so the properties rail fills in.
    const last = section.items[section.items.length - 1]
    if (last) store.selectItem(last.uid)
    return
  }
  if (payload.kind === 'preset') {
    // Re-import from the registry; the catalog mirrors the
    // store.applyPreset contract.
    // We import lazily to avoid a circular import (presetCatalog
    // imports preset generators, which can re-use the store types).
    void applyPresetById(payload.value, section.uid)
  }
}

async function applyPresetById(presetId: string, sectionUid: string): Promise<void> {
  const preset = findPreset(presetId)
  if (!preset) return
  const { PRESET_CATALOG } = await import('./presetCatalog')
  // 2026-06-21 user-feedback batch — each preset materialises as its
  // own section, never mixed into the target section. If the target is
  // empty (typical: dropping onto the default "Section 1"), the store
  // transforms it in-place rather than leaving a dangling empty
  // section above the new content.
  const newSectionUid = store.applyPresetAsSection(presetId, sectionUid, {
    registry: PRESET_CATALOG,
    translate: t,
  })
  if (!newSectionUid) return
  const section = store.draft.sections.find((s) => s.uid === newSectionUid)
  if (section && section.items.length > 0) {
    // Select the parent item (first emitted) so the operator can
    // tweak the label / OID immediately.
    store.selectItem(section.items[0]!.uid)
  }
}

function onItemReorder(sectionIndex: number, reordered: AuthoringItem[]): void {
  store.reorderItems(sectionIndex, reordered)
}

function onAddSection(): void {
  store.addSection()
}

/**
 * Drop-on-new-section: the "Neue Sektion hinzufügen" footer accepts a
 * palette payload. Primitives spin up a fresh section + append the item;
 * presets reuse {@link applyPresetAsSection} so the section title + tag come
 * from the preset rather than the auto-numbered Sn fallback.
 */
const dragOverNewSection = ref(false)
function onNewSectionDragOver(ev: DragEvent): void {
  ev.preventDefault()
  if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'copy'
  dragOverNewSection.value = true
}
function onNewSectionDragLeave(_ev: DragEvent): void {
  dragOverNewSection.value = false
}
function onNewSectionDrop(ev: DragEvent): void {
  ev.preventDefault()
  dragOverNewSection.value = false
  const payload = readPalettePayload(ev)
  if (!payload) return
  const newSectionUid = store.addSection()
  if (!newSectionUid) return
  const newIndex = store.draft.sections.findIndex((s) => s.uid === newSectionUid)
  if (newIndex < 0) return
  if (payload.kind === 'primitive') {
    const prim = findPalettePrimitive(payload.value)
    store.addItem(
      newIndex,
      prim ? prim.seed() : { dataType: payload.value as AuthoringItem['dataType'] },
    )
    const section = store.draft.sections[newIndex]
    const last = section?.items[section.items.length - 1]
    if (last) store.selectItem(last.uid)
    return
  }
  if (payload.kind === 'preset') {
    void applyPresetById(payload.value, newSectionUid)
  }
}

function onRemoveSection(sectionIndex: number): void {
  store.removeSection(sectionIndex)
}

function onItemClick(item: AuthoringItem): void {
  store.selectItem(item.uid)
}

function onItemRemove(sectionIndex: number, itemIndex: number, itemUid: string): void {
  if (store.selectedItemUid === itemUid) store.selectItem(null)
  store.removeItem(sectionIndex, itemIndex)
}

function onSectionTitleInput(sectionIndex: number, ev: Event): void {
  const target = ev.target as HTMLInputElement
  const section = store.draft.sections[sectionIndex]
  if (!section) return
  section.title = target.value
}

function onSectionLabelInput(sectionIndex: number, ev: Event): void {
  const target = ev.target as HTMLInputElement
  const section = store.draft.sections[sectionIndex]
  if (!section) return
  section.label = target.value
}

/**
 * Bilateral pair grouping — port of the legacy wizard's
 * {@code bilateralRowsForSection} routine. The canvas-side variant is
 * intentionally simpler: we only need the visual OD-left / OS-right
 * pairing for rendered preview, not the dual-editor body. (The wizard
 * component was removed in the D3 follow-up, 2026-06-20.)
 */
interface BilateralRow {
  kind: 'bilateral' | 'both-eyes' | 'single'
  key: string
  label: string
  od: AuthoringItem | null
  os: AuthoringItem | null
  bothEyes: AuthoringItem | null
  single: AuthoringItem | null
}

const EYE_PREFIX_RE = /^(OD|OS|OU)_(.+)$/

function bilateralRowsForSection(section: AuthoringSection): BilateralRow[] {
  const rows: BilateralRow[] = []
  const indexBySuffix = new Map<string, number>()
  for (const item of section.items) {
    const m = EYE_PREFIX_RE.exec(item.oid)
    if (!m) {
      rows.push({
        kind: 'single', key: item.uid,
        label: item.descriptionLabel || item.name || item.oid,
        od: null, os: null, bothEyes: null, single: item,
      })
      continue
    }
    const eye = m[1] as 'OD' | 'OS' | 'OU'
    const suffix = m[2]
    const existingIdx = indexBySuffix.get(suffix)
    const labelGuess = (item.descriptionLabel || item.name || suffix).replace(/_/g, ' ')
    if (existingIdx === undefined) {
      if (eye === 'OU') {
        rows.push({
          kind: 'both-eyes', key: suffix, label: labelGuess,
          od: null, os: null, bothEyes: item, single: null,
        })
      } else {
        rows.push({
          kind: 'bilateral', key: suffix, label: labelGuess,
          od: eye === 'OD' ? item : null,
          os: eye === 'OS' ? item : null,
          bothEyes: null, single: null,
        })
      }
      indexBySuffix.set(suffix, rows.length - 1)
      continue
    }
    const row = rows[existingIdx]!
    if (row.kind === 'bilateral' && eye === 'OD' && !row.od) row.od = item
    else if (row.kind === 'bilateral' && eye === 'OS' && !row.os) row.os = item
    else {
      // Duplicate / mismatched — render as own row.
      rows.push({
        kind: 'single', key: item.uid,
        label: item.descriptionLabel || item.name || item.oid,
        od: null, os: null, bothEyes: null, single: item,
      })
    }
  }
  return rows
}
</script>

<template>
  <section
    class="flex-1 min-w-0 overflow-y-auto bg-white"
    data-testid="crf-canvas-section-root"
  >
    <draggable
      :model-value="sections"
      :item-key="(s: AuthoringSection) => s.uid"
      handle=".crf-canvas-section-drag-handle"
      animation="120"
      tag="div"
      class="p-4 space-y-4"
      data-testid="crf-canvas-sections-draggable"
      @update:model-value="onSectionReorder"
    >
      <template #item="{ element: section, index: sIdx }">
      <div
        :key="section.uid"
        class="rounded-lg border bg-white"
        :class="dragOverSectionUid === section.uid ? 'border-muw-blue ring-2 ring-muw-blue/30' : 'border-slate-200'"
        :data-testid="`crf-canvas-section-${sIdx}`"
        @dragover="(ev) => onSectionDragOver(ev, section.uid)"
        @dragleave="(ev) => onSectionDragLeave(ev, section.uid)"
        @drop="(ev) => onSectionDrop(ev, sIdx)"
      >
        <div class="flex items-center gap-2 px-3 py-2 bg-slate-50 border-b border-slate-200">
          <!-- 2026-06-21 user-feedback batch — drag handle for section
               reorder. Vuedraggable's handle prop limits drag to this
               element so input clicks + bilateral toggle stay clickable. -->
          <span
            class="crf-canvas-section-drag-handle inline-flex items-center text-slate-400 hover:text-slate-700 cursor-grab"
            :title="t('crfAuthoring.canvas.section.dragHandle')"
            :aria-label="t('crfAuthoring.canvas.section.dragHandle')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
              <circle cx="9" cy="6" r="1.3" fill="currentColor" />
              <circle cx="15" cy="6" r="1.3" fill="currentColor" />
              <circle cx="9" cy="12" r="1.3" fill="currentColor" />
              <circle cx="15" cy="12" r="1.3" fill="currentColor" />
              <circle cx="9" cy="18" r="1.3" fill="currentColor" />
              <circle cx="15" cy="18" r="1.3" fill="currentColor" />
            </svg>
          </span>
          <!-- Collapse toggle. Chevron points right when collapsed,
               down when expanded. -->
          <button
            type="button"
            class="inline-flex items-center text-slate-500 hover:text-slate-800 px-1"
            :title="isSectionCollapsed(section.uid)
              ? t('crfAuthoring.canvas.section.expand')
              : t('crfAuthoring.canvas.section.collapse')"
            :aria-expanded="!isSectionCollapsed(section.uid)"
            :data-testid="`crf-canvas-section-collapse-${sIdx}`"
            @click="toggleSectionCollapsed(section.uid)"
          >
            <svg
              width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2.2"
              class="transition-transform"
              :class="isSectionCollapsed(section.uid) ? '-rotate-90' : ''"
            >
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
          <div class="flex-1 grid grid-cols-2 gap-2 min-w-0">
            <input
              type="text"
              class="w-full text-sm font-medium border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
              :value="section.title"
              :placeholder="t('crfAuthoring.canvas.section.titlePlaceholder')"
              :data-testid="`crf-canvas-section-title-${sIdx}`"
              @input="(ev) => onSectionTitleInput(sIdx, ev)"
            />
            <input
              type="text"
              class="w-full text-xs font-mono border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
              :value="section.label"
              :placeholder="t('crfAuthoring.canvas.section.labelPlaceholder')"
              :data-testid="`crf-canvas-section-label-${sIdx}`"
              @input="(ev) => onSectionLabelInput(sIdx, ev)"
            />
          </div>
          <!-- 2026-06-21 user-feedback batch — uni/bi-lateral toggle.
               Operator can flip an existing section between bilateral
               (OD-left / OS-right grid) and unilateral (flat list)
               without re-adding items. Preset drops still seed this
               from PresetDescriptor.bilateralSection. -->
          <button
            type="button"
            class="text-[11px] px-2 py-0.5 rounded border transition-colors"
            :class="section.bilateral
              ? 'bg-muw-blue text-white border-muw-blue hover:bg-muw-blue-700'
              : 'bg-white text-slate-600 border-slate-300 hover:bg-slate-100'"
            :data-testid="`crf-canvas-section-bilateral-${sIdx}`"
            :title="section.bilateral
              ? t('crfAuthoring.canvas.section.bilateralOn')
              : t('crfAuthoring.canvas.section.bilateralOff')"
            @click="store.setSectionBilateralByUid(section.uid, !section.bilateral)"
          >
            {{ section.bilateral
              ? t('crfAuthoring.canvas.section.bilateralOnShort')
              : t('crfAuthoring.canvas.section.bilateralOffShort') }}
          </button>
          <button
            v-if="sections.length > 1"
            type="button"
            class="text-[11px] text-slate-500 hover:text-red-600 px-1.5 py-0.5"
            :data-testid="`crf-canvas-section-remove-${sIdx}`"
            @click="onRemoveSection(sIdx)"
          >
            {{ t('crfAuthoring.canvas.section.remove') }}
          </button>
        </div>

        <!-- Section body — collapse-aware. v-show preserves the
             draggable list state inside so reorders + selection
             survive the toggle. -->
        <div v-show="!isSectionCollapsed(section.uid)" class="p-3 space-y-2">
          <div
            v-if="section.items.length === 0"
            class="text-center text-xs italic text-slate-400 border border-dashed border-slate-300 rounded-md py-6"
            :data-testid="`crf-canvas-section-empty-${sIdx}`"
          >
            {{ t('crfAuthoring.canvas.canvas.dropHere') }}
          </div>

          <div
            v-else-if="section.bilateral"
            class="space-y-2"
            :data-testid="`crf-canvas-section-bilateral-${sIdx}`"
          >
            <div
              v-for="row in bilateralRowsForSection(section)"
              :key="row.key"
              class="grid grid-cols-[14rem_1fr_1fr] gap-2 items-center"
            >
              <span class="text-[12px] font-medium text-slate-700 truncate">
                {{ row.label }}
              </span>
              <button
                v-if="row.od"
                type="button"
                class="text-left px-2 py-1.5 rounded border border-slate-200 bg-white hover:border-muw-blue/50 text-[11px]"
                :class="{ 'border-muw-blue ring-1 ring-muw-blue/40': store.selectedItemUid === row.od.uid }"
                :data-testid="`crf-canvas-item-od-${row.key}`"
                @click="onItemClick(row.od)"
              >
                <span class="font-mono text-slate-500">OD · {{ row.od.oid }}</span>
              </button>
              <span v-else class="text-[10px] italic text-slate-400">—</span>
              <button
                v-if="row.os"
                type="button"
                class="text-left px-2 py-1.5 rounded border border-slate-200 bg-white hover:border-muw-blue/50 text-[11px]"
                :class="{ 'border-muw-blue ring-1 ring-muw-blue/40': store.selectedItemUid === row.os.uid }"
                :data-testid="`crf-canvas-item-os-${row.key}`"
                @click="onItemClick(row.os)"
              >
                <span class="font-mono text-slate-500">OS · {{ row.os.oid }}</span>
              </button>
              <span v-else class="text-[10px] italic text-slate-400">—</span>
              <template v-if="row.bothEyes">
                <button
                  type="button"
                  class="col-span-2 text-left px-2 py-1.5 rounded border border-slate-200 bg-white hover:border-muw-blue/50 text-[11px]"
                  :class="{ 'border-muw-blue ring-1 ring-muw-blue/40': store.selectedItemUid === row.bothEyes.uid }"
                  :data-testid="`crf-canvas-item-ou-${row.key}`"
                  @click="onItemClick(row.bothEyes)"
                >
                  <span class="font-mono text-slate-500">OU · {{ row.bothEyes.oid }}</span>
                </button>
              </template>
              <template v-if="row.single">
                <button
                  type="button"
                  class="col-span-2 text-left px-2 py-1.5 rounded border border-slate-200 bg-white hover:border-muw-blue/50 text-[11px]"
                  :class="{ 'border-muw-blue ring-1 ring-muw-blue/40': store.selectedItemUid === row.single.uid }"
                  :data-testid="`crf-canvas-item-single-${row.key}`"
                  @click="onItemClick(row.single)"
                >
                  <span class="font-mono text-slate-500">{{ row.single.oid }}</span>
                </button>
              </template>
            </div>
          </div>

          <draggable
            v-else
            :model-value="section.items"
            item-key="uid"
            handle=".crf-canvas-item-drag-handle"
            ghost-class="opacity-50"
            class="space-y-1.5"
            :data-testid="`crf-canvas-section-list-${sIdx}`"
            @update:model-value="(next: AuthoringItem[]) => onItemReorder(sIdx, next)"
          >
            <template #item="{ element: item, index: iIdx }">
              <div
                :key="item.uid"
                :data-item-uid="item.uid"
                class="flex items-center gap-2 px-2 py-1.5 rounded border bg-white hover:border-muw-blue/50 cursor-pointer"
                :class="errorForItem(item.oid)
                  ? 'border-red-500 ring-1 ring-red-300 bg-red-50/40'
                  : (store.selectedItemUid === item.uid ? 'border-muw-blue ring-1 ring-muw-blue/40' : 'border-slate-200')"
                :data-testid="`crf-canvas-item-${sIdx}-${iIdx}`"
                @click="onItemClick(item)"
              >
                <span
                  class="crf-canvas-item-drag-handle inline-flex items-center text-[11px] text-slate-400 hover:text-slate-600 cursor-grab"
                  :aria-label="t('crfAuthoring.canvas.canvas.dragItem')"
                >
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                    <circle cx="9" cy="6" r="1.2" fill="currentColor" />
                    <circle cx="15" cy="6" r="1.2" fill="currentColor" />
                    <circle cx="9" cy="12" r="1.2" fill="currentColor" />
                    <circle cx="15" cy="12" r="1.2" fill="currentColor" />
                    <circle cx="9" cy="18" r="1.2" fill="currentColor" />
                    <circle cx="15" cy="18" r="1.2" fill="currentColor" />
                  </svg>
                </span>
                <div class="flex-1 min-w-0">
                  <div class="text-[12px] font-medium text-slate-800 truncate">
                    {{ item.name || item.oid || t('crfAuthoring.canvas.canvas.untitled') }}
                  </div>
                  <div class="text-[10px] font-mono text-slate-500 truncate">
                    {{ item.oid || '—' }} · {{ item.dataType }}
                  </div>
                  <div
                    v-if="errorForItem(item.oid)"
                    class="text-[10px] text-red-700 mt-0.5"
                    :data-testid="`crf-canvas-item-error-${sIdx}-${iIdx}`"
                  >
                    {{ errorForItem(item.oid) }}
                  </div>
                </div>
                <button
                  type="button"
                  class="text-[10px] text-slate-400 hover:text-red-600 px-1"
                  :data-testid="`crf-canvas-item-remove-${sIdx}-${iIdx}`"
                  @click.stop="onItemRemove(sIdx, iIdx, item.uid)"
                >
                  {{ t('crfAuthoring.canvas.canvas.removeItem') }}
                </button>
              </div>
            </template>
          </draggable>
        </div>
      </div>
      </template>
    </draggable>

    <!-- 2026-06-21 user-feedback round 4 — the "Neue Sektion hinzufügen"
         area now accepts a drag-released primitive or preset. Dropping
         a primitive seeds a fresh section + appends the item; dropping
         a preset materialises the preset as a brand-new section (the
         "transform empty target in place" path inside applyPresetAsSection
         kicks in automatically). dragOverNewSection drives the dashed
         border highlight so the drop target reads as "this works". -->
    <div class="px-4 pb-4 text-center">
      <button
        type="button"
        class="text-xs text-muw-blue px-3 py-1.5 border border-dashed rounded-md w-full transition"
        :class="dragOverNewSection
          ? 'border-muw-blue bg-muw-blue/5'
          : 'border-slate-300 hover:underline'"
        data-testid="crf-canvas-add-section"
        @click="onAddSection"
        @dragover="onNewSectionDragOver"
        @dragleave="onNewSectionDragLeave"
        @drop="onNewSectionDrop"
      >
        {{ t('crfAuthoring.canvas.section.add') }}
      </button>
    </div>
  </section>
</template>

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
import { findPreset } from './presetCatalog'

/**
 * App-feedback Wave 2 (2026-06-19) — middle canvas.
 *
 * <p>Renders each section in the draft as a drop-target card. Operators
 * can:
 *
 * <ul>
 *   <li>drop a palette primitive into a section (HTML5 DataTransfer with
 *       MIME type {@code application/x-crf-palette}, JSON payload
 *       {@code {"kind":"primitive","value":"INT"}}). The handler reads
 *       the data type and pushes a fresh item with that {@code dataType}
 *       into the section.</li>
 *   <li>drop a palette preset into a section. The handler dispatches
 *       {@code store.applyPreset(id, sectionUid, ...)} with the preset
 *       registry + translator.</li>
 *   <li>reorder items via vuedraggable (the existing wizard logic — we
 *       reuse the per-section list pattern, not the bilateral grid; the
 *       grid rendering happens in a future polish pass).</li>
 *   <li>click an item to select it (fills the right-rail properties
 *       panel).</li>
 *   <li>add / remove sections; rename the section title inline.</li>
 * </ul>
 *
 * <p>Bilateral OD/OS pairing on read: when {@code section.bilateral === true}
 * the renderer surfaces paired items in a two-column grid (OD on the
 * LEFT, OS on the RIGHT — face-to-face clinician convention). The
 * pairing key is the OID suffix after the {@code OD_} / {@code OS_}
 * prefix. This is a PORT of the wizard's {@code bilateralRowsForSection}
 * logic — simpler because the canvas only needs the visual pairing, not
 * the per-row editor.
 */

const { t } = useI18n()
const store = useCrfAuthoringStore()

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
    store.addItem(sectionIndex, { dataType: payload.value as AuthoringItem['dataType'] })
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
    <div class="p-4 space-y-4">
      <div
        v-for="(section, sIdx) in sections"
        :key="section.uid"
        class="rounded-lg border bg-white"
        :class="dragOverSectionUid === section.uid ? 'border-muw-blue ring-2 ring-muw-blue/30' : 'border-slate-200'"
        :data-testid="`crf-canvas-section-${sIdx}`"
        @dragover="(ev) => onSectionDragOver(ev, section.uid)"
        @dragleave="(ev) => onSectionDragLeave(ev, section.uid)"
        @drop="(ev) => onSectionDrop(ev, sIdx)"
      >
        <!-- Section header -->
        <div class="flex items-center gap-2 px-3 py-2 bg-slate-50 border-b border-slate-200">
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

        <!-- Section body -->
        <div class="p-3 space-y-2">
          <!-- Empty state -->
          <div
            v-if="section.items.length === 0"
            class="text-center text-xs italic text-slate-400 border border-dashed border-slate-300 rounded-md py-6"
            :data-testid="`crf-canvas-section-empty-${sIdx}`"
          >
            {{ t('crfAuthoring.canvas.canvas.dropHere') }}
          </div>

          <!-- Bilateral grid render -->
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

          <!-- Flat list (default) -->
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

      <div class="text-center">
        <button
          type="button"
          class="text-xs text-muw-blue hover:underline px-3 py-1.5 border border-dashed border-slate-300 rounded-md w-full"
          data-testid="crf-canvas-add-section"
          @click="onAddSection"
        >
          {{ t('crfAuthoring.canvas.section.add') }}
        </button>
      </div>
    </div>
  </section>
</template>

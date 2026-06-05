<script setup lang="ts">
import { computed, ref } from 'vue'
import type {
  EventTreeNode,
  EventTreeCrfNode,
  EventTreeVersionNode,
} from '@/types/export'
import TextInput from '@/components/TextInput.vue'

/**
 * Phase E.6 — Data Export Phase 2 — checkbox tree for the
 * create-dataset wizard's Scope step.
 *
 * Renders a four-level tree (Event → CRF → Version → Item) with:
 *
 *   - Per-level checkboxes. Toggling a node propagates the new state
 *     to every descendant (select-all / clear-all per level).
 *   - Tri-state semantics: a parent is "checked" when every leaf
 *     descendant is checked, "indeterminate" when only some are, and
 *     "unchecked" when none are.
 *   - Search-as-you-type that filters the tree by event / CRF / item
 *     name + OID. Search keeps the path to every match expanded.
 *   - Per-level live counts: "3 / 12 events", "5 / 9 items" — gives
 *     the operator a sense of what they've already picked.
 *
 * State shape:
 *   - {@code v-model:eventOids}: set of selected event-definition OIDs.
 *     An event is selected when at least one descendant CRF version is
 *     selected (we treat event selection as derived; the wizard payload
 *     wants the OID list either way).
 *   - {@code v-model:versionIds}: set of selected CRF version ids.
 *   - {@code v-model:itemIds}: set of selected item ids.
 *
 * The component is fully controlled — the parent owns the three id
 * sets so the store's draft stays the single source of truth.
 */

interface Props {
  tree: EventTreeNode[]
  eventOids: string[]
  versionIds: number[]
  itemIds: number[]
  /** Whether the parent is still loading the tree from the backend. */
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), { loading: false })

const emit = defineEmits<{
  'update:eventOids': [value: string[]]
  'update:versionIds': [value: number[]]
  'update:itemIds': [value: number[]]
}>()

const search = ref('')
const expanded = ref<Set<string>>(new Set())

/** Composite key generators so we can address each node from a flat set. */
const eventKey = (e: EventTreeNode) => `e:${e.eventOid}`
const crfKey = (e: EventTreeNode, c: EventTreeCrfNode) => `c:${e.eventOid}|${c.crfOid}`
const versionKey = (e: EventTreeNode, c: EventTreeCrfNode, v: EventTreeVersionNode) =>
  `v:${e.eventOid}|${c.crfOid}|${v.versionId}`

const isExpanded = (key: string) => expanded.value.has(key)
const toggleExpanded = (key: string) => {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

/* ============================================================== */
/* Selection sets — efficient lookups + change-emitters.          */
/* ============================================================== */

const eventSet = computed(() => new Set(props.eventOids))
const versionSet = computed(() => new Set(props.versionIds))
const itemSet = computed(() => new Set(props.itemIds))

function emitEvents(next: Set<string>) {
  emit('update:eventOids', [...next])
}
function emitVersions(next: Set<number>) {
  emit('update:versionIds', [...next])
}
function emitItems(next: Set<number>) {
  emit('update:itemIds', [...next])
}

/* ============================================================== */
/* Tri-state checked / indeterminate                              */
/* ============================================================== */

function versionIsChecked(v: EventTreeVersionNode): boolean {
  if (v.items.length === 0) return versionSet.value.has(v.versionId)
  return v.items.every((i) => itemSet.value.has(i.itemId))
}
function versionIsIndeterminate(v: EventTreeVersionNode): boolean {
  if (v.items.length === 0) return false
  const any = v.items.some((i) => itemSet.value.has(i.itemId))
  const all = v.items.every((i) => itemSet.value.has(i.itemId))
  return any && !all
}

function crfIsChecked(c: EventTreeCrfNode): boolean {
  return c.versions.length > 0 && c.versions.every(versionIsChecked)
}
function crfIsIndeterminate(c: EventTreeCrfNode): boolean {
  const any = c.versions.some((v) => versionIsChecked(v) || versionIsIndeterminate(v))
  const all = c.versions.every(versionIsChecked)
  return any && !all
}

function eventIsChecked(e: EventTreeNode): boolean {
  return e.crfs.length > 0 && e.crfs.every(crfIsChecked)
}
function eventIsIndeterminate(e: EventTreeNode): boolean {
  const any = e.crfs.some((c) => crfIsChecked(c) || crfIsIndeterminate(c))
  const all = e.crfs.every(crfIsChecked)
  return any && !all
}

/* ============================================================== */
/* Toggle handlers                                                */
/* ============================================================== */

function toggleItem(e: EventTreeNode, _c: EventTreeCrfNode, v: EventTreeVersionNode, itemId: number) {
  const items = new Set(itemSet.value)
  if (items.has(itemId)) items.delete(itemId)
  else items.add(itemId)
  emitItems(items)

  // Derive version + event membership from items (a version with any
  // selected item is selected; an event with any selected child is
  // selected).
  syncDerived(e, v, items)
}

function toggleVersion(e: EventTreeNode, _c: EventTreeCrfNode, v: EventTreeVersionNode, checked: boolean) {
  const items = new Set(itemSet.value)
  for (const item of v.items) {
    if (checked) items.add(item.itemId)
    else items.delete(item.itemId)
  }
  emitItems(items)
  syncDerived(e, v, items)
}

function toggleCrf(e: EventTreeNode, c: EventTreeCrfNode, checked: boolean) {
  const items = new Set(itemSet.value)
  for (const v of c.versions) {
    for (const item of v.items) {
      if (checked) items.add(item.itemId)
      else items.delete(item.itemId)
    }
  }
  emitItems(items)

  // Versions selection follows: each version with any selected item.
  const versions = new Set(versionSet.value)
  for (const v of c.versions) {
    const any = v.items.some((i) => items.has(i.itemId))
    if (any) versions.add(v.versionId)
    else versions.delete(v.versionId)
  }
  emitVersions(versions)

  syncEventFromItems(e, items)
}

function toggleEvent(e: EventTreeNode, checked: boolean) {
  const items = new Set(itemSet.value)
  const versions = new Set(versionSet.value)
  for (const c of e.crfs) {
    for (const v of c.versions) {
      if (checked) versions.add(v.versionId)
      else versions.delete(v.versionId)
      for (const item of v.items) {
        if (checked) items.add(item.itemId)
        else items.delete(item.itemId)
      }
    }
  }
  emitItems(items)
  emitVersions(versions)

  const events = new Set(eventSet.value)
  if (checked) events.add(e.eventOid)
  else events.delete(e.eventOid)
  emitEvents(events)
}

function syncDerived(e: EventTreeNode, v: EventTreeVersionNode, items: Set<number>) {
  const versions = new Set(versionSet.value)
  const versionHasAny = v.items.some((i) => items.has(i.itemId))
  if (versionHasAny) versions.add(v.versionId)
  else versions.delete(v.versionId)
  emitVersions(versions)

  syncEventFromItems(e, items)
}

function syncEventFromItems(e: EventTreeNode, items: Set<number>) {
  const events = new Set(eventSet.value)
  const eventHasAny = e.crfs.some((c) =>
    c.versions.some((v2) => v2.items.some((i) => items.has(i.itemId))),
  )
  if (eventHasAny) events.add(e.eventOid)
  else events.delete(e.eventOid)
  emitEvents(events)
}

/* ============================================================== */
/* Search filter — keeps any node whose name / OID matches.        */
/* ============================================================== */

function matches(node: { name?: string; oid?: string }, term: string): boolean {
  if (!term) return true
  const t = term.toLowerCase()
  return (
    (node.name?.toLowerCase().includes(t) ?? false) ||
    (node.oid?.toLowerCase().includes(t) ?? false)
  )
}

const filteredTree = computed(() => {
  const term = search.value.trim().toLowerCase()
  if (!term) return props.tree

  const out: EventTreeNode[] = []
  for (const e of props.tree) {
    const eventMatch =
      e.eventName.toLowerCase().includes(term) || e.eventOid.toLowerCase().includes(term)
    const filteredCrfs: EventTreeCrfNode[] = []
    for (const c of e.crfs) {
      const crfMatch =
        c.crfName.toLowerCase().includes(term) || c.crfOid.toLowerCase().includes(term)
      const filteredVersions: EventTreeVersionNode[] = []
      for (const v of c.versions) {
        const versionMatch =
          v.versionName.toLowerCase().includes(term) || v.versionOid.toLowerCase().includes(term)
        const matchingItems = v.items.filter((i) => matches(i, term))
        if (eventMatch || crfMatch || versionMatch || matchingItems.length > 0) {
          filteredVersions.push({
            ...v,
            items: eventMatch || crfMatch || versionMatch ? v.items : matchingItems,
          })
        }
      }
      if (eventMatch || crfMatch || filteredVersions.length > 0) {
        filteredCrfs.push({ ...c, versions: filteredVersions })
      }
    }
    if (eventMatch || filteredCrfs.length > 0) {
      out.push({ ...e, crfs: filteredCrfs })
    }
  }
  return out
})

/* ============================================================== */
/* Counts — shown in the header for fast feedback.                */
/* ============================================================== */

const totals = computed(() => {
  let events = 0
  let items = 0
  for (const e of props.tree) {
    events += 1
    for (const c of e.crfs) {
      for (const v of c.versions) {
        items += v.items.length
      }
    }
  }
  return { events, items }
})

const picked = computed(() => ({
  events: eventSet.value.size,
  items: itemSet.value.size,
}))

/* Initial expansion: open the first event so the user sees the
 * shape of the tree on mount. */
if (props.tree[0]) expanded.value.add(eventKey(props.tree[0]))
</script>

<template>
  <div class="space-y-3">
    <header class="flex flex-wrap items-center gap-3 text-xs text-slate-600">
      <div class="flex-1 min-w-[200px]">
        <TextInput
          id="event-tree-search"
          v-model="search"
          type="search"
          placeholder="Suchen — Event, CRF, Item…"
          inputmode="search"
        />
      </div>
      <div class="px-2 py-1 rounded-md bg-slate-100 font-medium">
        {{ picked.events }} / {{ totals.events }} events
      </div>
      <div class="px-2 py-1 rounded-md bg-slate-100 font-medium">
        {{ picked.items }} / {{ totals.items }} items
      </div>
    </header>

    <div
      v-if="loading"
      class="px-3 py-6 text-center text-sm text-slate-500"
      role="status"
    >
      Event-Baum wird geladen…
    </div>

    <div
      v-else-if="filteredTree.length === 0"
      class="px-3 py-6 text-center text-sm text-slate-500 border border-dashed border-slate-200 rounded-md"
    >
      <template v-if="search">Keine Treffer für „{{ search }}".</template>
      <template v-else>Für dieses Studienprotokoll sind keine Visiten / CRFs konfiguriert.</template>
    </div>

    <ul v-else class="border border-slate-200 rounded-md divide-y divide-slate-100 max-h-[420px] overflow-y-auto bg-white">
      <li v-for="e in filteredTree" :key="e.eventOid" class="px-3 py-2">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-slate-400 hover:text-slate-600 w-5 h-5 inline-flex items-center justify-center"
            :aria-expanded="isExpanded(eventKey(e))"
            :aria-label="(isExpanded(eventKey(e)) ? 'Visite einklappen: ' : 'Visite ausklappen: ') + e.eventName"
            @click="toggleExpanded(eventKey(e))"
          >
            <span aria-hidden="true">{{ isExpanded(eventKey(e)) ? '▾' : '▸' }}</span>
          </button>
          <label class="flex items-center gap-2 cursor-pointer flex-1">
            <input
              type="checkbox"
              class="h-4 w-4 rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
              :checked="eventIsChecked(e)"
              :indeterminate.prop="eventIsIndeterminate(e)"
              :aria-label="`Toggle ${e.eventName}`"
              @change="toggleEvent(e, ($event.target as HTMLInputElement).checked)"
            />
            <span class="font-medium text-sm text-slate-800">{{ e.eventName }}</span>
            <span class="text-xs text-slate-500">{{ e.eventOid }}</span>
            <span
              v-if="e.repeating"
              class="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded bg-muw-teal-100 text-muw-teal-700"
            >repeating</span>
          </label>
        </div>

        <ul v-if="isExpanded(eventKey(e))" class="ml-6 mt-2 space-y-2">
          <li v-for="c in e.crfs" :key="c.crfOid">
            <div class="flex items-center gap-2">
              <button
                type="button"
                class="text-slate-400 hover:text-slate-600 w-5 h-5 inline-flex items-center justify-center"
                :aria-expanded="isExpanded(crfKey(e, c))"
                :aria-label="(isExpanded(crfKey(e, c)) ? 'CRF einklappen: ' : 'CRF ausklappen: ') + c.crfName"
                @click="toggleExpanded(crfKey(e, c))"
              >
                <span aria-hidden="true">{{ isExpanded(crfKey(e, c)) ? '▾' : '▸' }}</span>
              </button>
              <label class="flex items-center gap-2 cursor-pointer flex-1">
                <input
                  type="checkbox"
                  class="h-4 w-4 rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
                  :checked="crfIsChecked(c)"
                  :indeterminate.prop="crfIsIndeterminate(c)"
                  :aria-label="`Toggle ${c.crfName}`"
                  @change="toggleCrf(e, c, ($event.target as HTMLInputElement).checked)"
                />
                <span class="text-sm text-slate-800">{{ c.crfName }}</span>
                <span class="text-xs text-slate-500">{{ c.crfOid }}</span>
              </label>
            </div>

            <ul v-if="isExpanded(crfKey(e, c))" class="ml-6 mt-1 space-y-1">
              <li v-for="v in c.versions" :key="v.versionId">
                <div class="flex items-center gap-2">
                  <button
                    type="button"
                    class="text-slate-400 hover:text-slate-600 w-5 h-5 inline-flex items-center justify-center"
                    :aria-expanded="isExpanded(versionKey(e, c, v))"
                    :aria-label="(isExpanded(versionKey(e, c, v)) ? 'Version einklappen: ' : 'Version ausklappen: ') + v.versionName"
                    @click="toggleExpanded(versionKey(e, c, v))"
                  >
                    <span aria-hidden="true">{{ isExpanded(versionKey(e, c, v)) ? '▾' : '▸' }}</span>
                  </button>
                  <label class="flex items-center gap-2 cursor-pointer flex-1">
                    <input
                      type="checkbox"
                      class="h-4 w-4 rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
                      :checked="versionIsChecked(v)"
                      :indeterminate.prop="versionIsIndeterminate(v)"
                      :aria-label="`Toggle ${v.versionName}`"
                      @change="toggleVersion(e, c, v, ($event.target as HTMLInputElement).checked)"
                    />
                    <span class="text-sm text-slate-700">{{ v.versionName }}</span>
                    <span class="text-xs text-slate-400">{{ v.items.length }} items</span>
                  </label>
                </div>

                <ul v-if="isExpanded(versionKey(e, c, v))" class="ml-6 mt-1 grid grid-cols-1 sm:grid-cols-2 gap-1">
                  <li v-for="i in v.items" :key="i.itemId">
                    <label class="flex items-center gap-2 cursor-pointer text-xs">
                      <input
                        type="checkbox"
                        class="h-3.5 w-3.5 rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
                        :checked="itemSet.has(i.itemId)"
                        :aria-label="`Toggle ${i.name}`"
                        @change="toggleItem(e, c, v, i.itemId)"
                      />
                      <span class="text-slate-700 truncate">{{ i.name }}</span>
                      <span class="text-slate-400 truncate">{{ i.oid }}</span>
                    </label>
                  </li>
                </ul>
              </li>
            </ul>
          </li>
        </ul>
      </li>
    </ul>
  </div>
</template>

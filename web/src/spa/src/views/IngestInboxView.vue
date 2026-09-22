<script setup lang="ts">
/**
 * P3.2 — one inbox for everything that arrives.
 *
 * Replaces ImageInboxView. The platform had two queues for one activity: an
 * OCT volume went to the retinal "parked" list and a fundus photo to the image
 * inbox, so an operator had to know which queue a file had landed in before
 * they could look for it — and neither view could show them that one patient
 * had both waiting.
 *
 * Three things this does that the image inbox did not: it filters (the old one
 * showed the newest 200 rows and nothing else, which stops being usable once
 * OCT volumes share the queue), it selects several files to bind as one
 * decision, and it can put a file back when somebody filed it wrongly.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import AssignIngestDialog from '@/components/ingest/AssignIngestDialog.vue'
import { useConfirm } from '@/composables/useConfirm'
import {
  bindIngestItem,
  bulkBindIngestItems,
  dismissIngestItem,
  ingestInboxCounts,
  listIngestInbox,
  unbindIngestItem,
  type IngestItem,
  type IngestKind,
  type IngestStatus,
} from '@/api/ingest'

const { t } = useI18n()
const confirm = useConfirm()

// The API client prepends CONTEXT_PATH for fetches; a raw <img src> does not.
const CONTEXT_PATH = '/LibreClinica'

const rows = ref<IngestItem[]>([])
const counts = ref<Record<string, number>>({})
const loading = ref(true)
const error = ref<string | null>(null)
const busyId = ref<number | null>(null)

const kind = ref<IngestKind | null>(null)
const status = ref<IngestStatus>('UNBOUND')
const search = ref('')

/** Files the operator has ticked, to bind in one go. */
const selected = ref<Set<number>>(new Set())

const dialogOpen = ref(false)
/** Null while binding a selection rather than a single row. */
const dialogRow = ref<IngestItem | null>(null)

const KINDS: IngestKind[] = ['e2e', 'dicom', 'image', 'other']

const selectedCount = computed(() => selected.value.size)
const canUnbind = computed(() => status.value === 'BOUND')

function previewSrc(row: IngestItem): string {
  return `${CONTEXT_PATH}${row.previewUrl}`
}

/** Bytes as something an operator can read at a glance. */
function size(row: IngestItem): string {
  if (row.byteSize == null) return ''
  const mb = row.byteSize / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(0)} MB` : `${Math.max(1, Math.round(row.byteSize / 1024))} KB`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  selected.value = new Set()
  try {
    const res = await listIngestInbox({
      kind: kind.value,
      status: status.value,
      q: search.value.trim() || null,
    })
    rows.value = res.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function loadCounts(): Promise<void> {
  try {
    const res = await ingestInboxCounts()
    counts.value = res.byKind
  } catch {
    // The chips lose their numbers; the queue itself still works.
    counts.value = {}
  }
}

onMounted(() => {
  void load()
  void loadCounts()
})
watch([kind, status], () => void load())

function removeRow(id: number): void {
  rows.value = rows.value.filter((r) => r.id !== id)
  selected.value.delete(id)
  void loadCounts()
}

function toggle(id: number): void {
  // Reassigned rather than mutated so the computed count stays reactive.
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

async function runBind(
  ids: number[],
  studySubjectId: number,
  studyEventId: number | null,
  eventCrfId: number | null,
): Promise<void> {
  error.value = null
  try {
    if (ids.length === 1) {
      busyId.value = ids[0] ?? null
      await bindIngestItem(ids[0] as number, { studySubjectId, studyEventId, eventCrfId })
      removeRow(ids[0] as number)
      return
    }
    const res = await bulkBindIngestItems(ids, { studySubjectId, studyEventId, eventCrfId })
    res.bound.forEach(removeRow)
    if (res.skipped.length > 0) {
      // Say so rather than silently binding fewer than the operator selected.
      error.value = t('ingestInbox.someSkipped', { n: res.skipped.length })
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busyId.value = null
  }
}

function bindSuggested(row: IngestItem): void {
  const s = row.suggestion
  if (!s) return
  void runBind([row.id], s.studySubjectId, s.studyEventId, s.eventCrfId)
}

function openAssign(row: IngestItem | null): void {
  dialogRow.value = row
  dialogOpen.value = true
}

function onDialogBind(payload: {
  ingestItemId: number
  studySubjectId: number
  studyEventId: number | null
  eventCrfId: number | null
}): void {
  dialogOpen.value = false
  const ids = dialogRow.value ? [dialogRow.value.id] : [...selected.value]
  void runBind(ids, payload.studySubjectId, payload.studyEventId, payload.eventCrfId)
}

async function onDismiss(row: IngestItem): Promise<void> {
  if (!(await confirm({ message: t('ingestInbox.dismissConfirm'), danger: true }))) return
  busyId.value = row.id
  error.value = null
  try {
    await dismissIngestItem(row.id)
    removeRow(row.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busyId.value = null
  }
}

async function onUnbind(row: IngestItem): Promise<void> {
  // Worth confirming: this also removes the CRF value the bind wrote.
  if (!(await confirm({ message: t('ingestInbox.unbindConfirm'), danger: true }))) return
  busyId.value = row.id
  error.value = null
  try {
    await unbindIngestItem(row.id)
    removeRow(row.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="p-6 max-w-6xl mx-auto">
    <header class="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-xl font-semibold text-slate-800">{{ t('ingestInbox.title') }}</h1>
        <p class="text-[13px] text-slate-500 mt-1">
          {{ t('ingestInbox.subtitle', { count: rows.length }) }}
        </p>
      </div>
      <!-- DR-029 — files can be brought in here as well as reconciled. -->
      <RouterLink
        :to="{ name: 'ingest-upload' }"
        class="inline-flex items-center gap-1.5 px-3.5 py-2 text-[13px] font-semibold bg-muw-blue text-white rounded-lg hover:bg-muw-blue-700 shadow-[0_1px_2px_rgba(17,29,78,0.18)]"
        data-testid="inbox-upload-link"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path d="M12 13v8M8 17l4-4 4 4" />
          <path d="M20 16.58A5 5 0 0 0 18 7h-1.26A8 8 0 1 0 4 15.25" />
        </svg>
        {{ t('ingestInbox.uploadAction') }}
      </RouterLink>
    </header>

    <!-- Filters. The kind chips carry their counts so an operator can see at a
         glance that scans are waiting even while looking at photographs. -->
    <div class="mb-4 flex flex-wrap items-center gap-2" role="group" :aria-label="t('ingestInbox.filters')">
      <button
        type="button"
        class="px-3 py-1.5 text-[12px] font-medium rounded-full ring-1"
        :class="kind === null ? 'bg-muw-blue text-white ring-muw-blue' : 'bg-white text-slate-600 ring-slate-200 hover:bg-slate-50'"
        :aria-pressed="kind === null"
        data-testid="kind-all"
        @click="kind = null"
      >{{ t('ingestInbox.kindAll') }}</button>
      <button
        v-for="k in KINDS"
        :key="k"
        type="button"
        class="px-3 py-1.5 text-[12px] font-medium rounded-full ring-1"
        :class="kind === k ? 'bg-muw-blue text-white ring-muw-blue' : 'bg-white text-slate-600 ring-slate-200 hover:bg-slate-50'"
        :aria-pressed="kind === k"
        :data-testid="`kind-${k}`"
        @click="kind = k"
      >
        {{ t(`ingestInbox.kind.${k}`) }}
        <span
          v-if="counts[k]"
          class="ml-1"
          :class="kind === k ? 'text-white' : 'text-slate-500'"
        >{{ counts[k] }}</span>
      </button>

      <span class="mx-1 h-5 w-px bg-slate-200" aria-hidden="true"></span>

      <label class="sr-only" for="ingest-status">{{ t('ingestInbox.statusLabel') }}</label>
      <select
        id="ingest-status"
        v-model="status"
        class="px-2.5 py-1.5 text-[12px] rounded-lg ring-1 ring-slate-200 bg-white text-slate-700"
        data-testid="status-select"
      >
        <option value="UNBOUND">{{ t('ingestInbox.status.UNBOUND') }}</option>
        <option value="BOUND">{{ t('ingestInbox.status.BOUND') }}</option>
        <option value="DISMISSED">{{ t('ingestInbox.status.DISMISSED') }}</option>
      </select>

      <label class="sr-only" for="ingest-q">{{ t('ingestInbox.searchLabel') }}</label>
      <input
        id="ingest-q"
        v-model="search"
        type="search"
        class="px-2.5 py-1.5 text-[12px] rounded-lg ring-1 ring-slate-200 bg-white text-slate-700 min-w-[10rem]"
        :placeholder="t('ingestInbox.searchPlaceholder')"
        data-testid="search-input"
        @keyup.enter="load"
      />
      <button
        type="button"
        class="px-3 py-1.5 text-[12px] font-medium rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50"
        data-testid="apply-filters"
        @click="load"
      >{{ t('ingestInbox.apply') }}</button>
    </div>

    <!-- The selection banner only appears once something is ticked, so the
         single-file path is unchanged for an operator who never uses it. -->
    <div
      v-if="selectedCount > 0"
      class="mb-4 px-4 py-2.5 rounded-xl bg-muw-teal-50 ring-1 ring-muw-teal-200 flex items-center gap-3"
      data-testid="bulk-banner"
    >
      <span class="text-[13px] text-muw-teal-800">
        {{ t('ingestInbox.selected', { n: selectedCount }) }}
      </span>
      <button
        type="button"
        class="px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-teal-600 text-white hover:bg-muw-teal-700"
        data-testid="bulk-bind"
        @click="openAssign(null)"
      >{{ t('ingestInbox.bindSelected') }}</button>
      <button
        type="button"
        class="text-[12px] text-slate-500 hover:text-slate-700 underline"
        @click="selected = new Set()"
      >{{ t('ingestInbox.clearSelection') }}</button>
    </div>

    <p v-if="error" class="mb-4 text-[13px] text-rose-700" data-testid="inbox-error">{{ error }}</p>

    <p v-if="loading" class="text-[13px] text-slate-500" data-testid="inbox-loading">
      {{ t('ingestInbox.loading') }}
    </p>

    <p v-else-if="rows.length === 0" class="text-[13px] text-slate-500 italic" data-testid="inbox-empty">
      {{ t('ingestInbox.empty') }}
    </p>

    <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4" data-testid="inbox-grid">
      <div
        v-for="row in rows"
        :key="row.id"
        class="bg-white rounded-2xl ring-1 overflow-hidden flex flex-col"
        :class="selected.has(row.id) ? 'ring-muw-teal-400' : 'ring-slate-200'"
        data-testid="ingest-row"
      >
        <div class="aspect-[4/3] bg-slate-100 flex items-center justify-center overflow-hidden relative">
          <img
            v-if="row.hasPreview"
            :src="previewSrc(row)"
            alt=""
            class="w-full h-full object-contain"
            loading="lazy"
          />
          <!-- An OCT volume has no preview until the pipeline renders one; say
               what it is rather than showing an empty frame. -->
          <span v-else class="text-[12px] text-slate-500 px-3 text-center">
            {{ t(`ingestInbox.kind.${row.kind}`) }}<template v-if="size(row)"> · {{ size(row) }}</template>
          </span>
          <label class="absolute top-2 left-2 bg-white/90 rounded-md px-1.5 py-1 ring-1 ring-slate-200">
            <input
              type="checkbox"
              class="align-middle"
              :checked="selected.has(row.id)"
              :aria-label="t('ingestInbox.selectOne')"
              :data-testid="`select-${row.id}`"
              @change="toggle(row.id)"
            />
          </label>
        </div>
        <div class="p-4 flex-1 flex flex-col">
          <div class="text-[13px] text-slate-700">
            <span class="font-medium">{{ t('ingestInbox.patientId') }}:</span> {{ row.patientId || '—' }}
          </div>
          <div class="text-[12px] text-slate-500 mt-0.5">
            {{ t('ingestInbox.eye') }}: {{ row.laterality || '—' }} · {{ row.acquisitionDate || '—' }}
          </div>
          <div class="text-[11px] text-slate-500 mt-0.5 uppercase tracking-wide">
            {{ t(`ingestInbox.kind.${row.kind}`) }}
            <template v-if="row.device"> · {{ row.device }}</template>
            <template v-if="row.receivedAt"> · {{ row.receivedAt.slice(0, 10) }}</template>
          </div>

          <p v-if="row.suggestion" class="mt-2 text-[12px] text-muw-teal-700">
            {{ t('ingestInbox.suggestion', {
              label: row.suggestion.subjectLabel,
              study: row.suggestion.studyName,
            }) }}
          </p>

          <div class="mt-auto pt-3 flex flex-wrap gap-2">
            <button
              v-if="!canUnbind && row.suggestion && row.suggestion.eventCrfId"
              type="button"
              class="px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-teal-600 text-white hover:bg-muw-teal-700 disabled:bg-slate-300"
              :disabled="busyId === row.id"
              @click="bindSuggested(row)"
            >{{ t('ingestInbox.bindSuggested') }}</button>
            <button
              v-if="!canUnbind"
              type="button"
              class="px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-blue text-white hover:bg-muw-blue-700 disabled:bg-slate-300"
              :disabled="busyId === row.id"
              @click="openAssign(row)"
            >{{ t('ingestInbox.bind') }}</button>
            <button
              v-if="canUnbind"
              type="button"
              class="px-3 py-1.5 text-[12px] font-medium rounded-lg border border-amber-300 text-amber-800 hover:bg-amber-50 disabled:opacity-50"
              :disabled="busyId === row.id"
              :data-testid="`unbind-${row.id}`"
              @click="onUnbind(row)"
            >{{ t('ingestInbox.unbind') }}</button>
            <button
              v-if="!canUnbind"
              type="button"
              class="px-3 py-1.5 text-[12px] font-medium rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:opacity-50"
              :disabled="busyId === row.id"
              @click="onDismiss(row)"
            >{{ t('ingestInbox.dismiss') }}</button>
          </div>
        </div>
      </div>
    </div>

    <AssignIngestDialog
      v-if="dialogOpen"
      :open="dialogOpen"
      :ingest-item-id="dialogRow ? dialogRow.id : 0"
      :initial-patient-id="dialogRow?.patientId ?? ''"
      @bind="onDialogBind"
      @close="dialogOpen = false"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * System → Standorte → Quellen: where device files come from, and the
 * location each source is assigned to. System administrators only.
 *
 * A source is an uploader on an acquisition PC, a DICOM sender (by its
 * calling AE title), the Remidio account or the upload page. Files of a
 * source without a location are visible to administrators alone; assigning
 * the location moves the files that wait for it into that location's inbox
 * in one step. So the page is built around what is still owed: a source
 * without a location that has files waiting is emphasised ("12 Dateien
 * warten"), the summary above the table counts them, and the tab that holds
 * this panel carries the number of sources without a location.
 *
 * Each row's location is a select. Choosing one assigns it at once, since
 * the change is cheap to undo (choose another, or "nicht zugeordnet"), and
 * confirms with a notice; the server's refusals (409 when the location is
 * no longer available, 404) are shown above the table and the select goes
 * back to where it was. While a row's request is on its way the select
 * shows the choice, and a second change to that row is put aside.
 *
 * An assignment moves files into a location's inbox, so the locations' own
 * file counts are read again afterwards (silently, no loading row).
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import SelectInput from '@/components/SelectInput.vue'

import { useAdminIngestSourcesStore } from '@/stores/adminIngestSources'
import { useAdminLocationsStore } from '@/stores/adminLocations'
import { useNotificationsStore } from '@/stores/notifications'
import { formatAgo, formatInstant, secondsSince } from '@/lib/byteFormat'
import type { IngestSource } from '@/types/ingestSource'

const { t, te, locale } = useI18n()
const sources = useAdminIngestSourcesStore()
const locations = useAdminLocationsStore()
const notifications = useNotificationsStore()

onMounted(() => {
  // The page reads both lists when it opens; this covers a panel shown on its own.
  if (!sources.loaded && !sources.isLoading) void sources.load()
  if (!locations.loaded && !locations.isLoading) void locations.load()
})

/* ------------------------------ What a row shows ------------------------------ */

/** A kind the SPA does not know (a newer server) is shown as the server named it. */
function kindLabel(kind: string): string {
  const key = `adminLocations.sources.kind.${kind}`
  return te(key) ? t(key) : kind
}

/** Waiting files nobody but an administrator can see: the gap this page exists to close. */
function needsLocation(s: IngestSource): boolean {
  return s.locationId === null && s.pendingItemCount > 0
}

function ago(iso: string): string {
  return formatAgo(secondsSince(iso), locale.value)
}

/* ------------------------------ The location select ------------------------------ */

/**
 * Choices made but not yet answered, by source id. The select shows the
 * choice while the request runs; when the request is over the entry goes and
 * the select shows what the server holds, which puts a refused choice back.
 */
const pending = ref<Record<number, string>>({})
const busy = ref<number[]>([])

function shownLocation(s: IngestSource): string {
  return pending.value[s.id] ?? (s.locationId === null ? '' : String(s.locationId))
}

interface LocationChoice {
  id: number
  label: string
}

/**
 * The locations a source can be given: the available ones, in the server's
 * order. A source that sits at a location which is no longer available keeps
 * that one in its select, marked, so that the select shows where it is.
 */
function choicesFor(s: IngestSource): LocationChoice[] {
  const out: LocationChoice[] = []
  let current = false
  for (const l of locations.locations) {
    if (l.status === 'available') {
      out.push({ id: l.id, label: l.name })
    } else if (l.id === s.locationId) {
      out.push({ id: l.id, label: t('adminLocations.sources.removedOption', { name: l.name }) })
    } else {
      continue
    }
    if (l.id === s.locationId) current = true
  }
  if (s.locationId !== null && !current) {
    // The locations list is not at hand (or does not know it): the source's own words.
    out.push({ id: s.locationId, label: s.locationName ?? String(s.locationId) })
  }
  return out
}

/** The select is a view of the row's model: put it back when a change is set aside. */
function restoreSelect(s: IngestSource) {
  const el = document.getElementById(`source-location-${s.id}`) as HTMLSelectElement | null
  if (el) el.value = shownLocation(s)
}

/** The server's refusal of the last assignment, above the table where the rows are. */
const actionError = ref<string | null>(null)

async function assign(s: IngestSource, value: string) {
  if (busy.value.includes(s.id)) {
    restoreSelect(s)
    return
  }
  const locationId = value === '' ? null : Number(value)
  if (locationId === s.locationId) return
  actionError.value = null
  pending.value = { ...pending.value, [s.id]: value }
  busy.value = [...busy.value, s.id]
  try {
    const result = await sources.assignLocation(s.id, locationId)
    if (result.ok) {
      if (locationId === null) {
        notifications.success(t('adminLocations.sources.unassignedNow', { name: s.displayName }))
      } else {
        const named = {
          name: s.displayName,
          location: result.source.locationName ?? locations.locations.find((l) => l.id === locationId)?.name ?? String(locationId),
        }
        // Only a source that had no location had files without an origin to hand over.
        notifications.success(
          s.locationId === null && s.pendingItemCount > 0
            ? t('adminLocations.sources.assignedFiles', { ...named, n: s.pendingItemCount })
            : t('adminLocations.sources.assigned', named),
        )
      }
      // The assignment moved files into a location's inbox: its count is the server's to say.
      void locations.refresh()
    } else {
      actionError.value = t('adminLocations.sources.assignFailed', { name: s.displayName, reason: result.message })
    }
  } finally {
    const { [s.id]: _answered, ...rest } = pending.value
    pending.value = rest
    busy.value = busy.value.filter((id) => id !== s.id)
  }
}

const showSummary = computed(() => sources.loaded && !sources.error && sources.sources.length > 0)
</script>

<template>
  <section aria-labelledby="sources-heading" data-testid="sources-panel">
    <h2 id="sources-heading" class="sr-only">{{ t('adminLocations.sources.heading') }}</h2>
    <p class="mb-3 max-w-3xl text-xs leading-relaxed text-slate-500">{{ t('adminLocations.sources.intro') }}</p>

    <p
      v-if="showSummary"
      class="mb-3 text-[13px]"
      :class="sources.unassignedCount > 0 ? 'font-medium text-amber-900' : 'text-slate-600'"
      data-testid="sources-summary"
    >
      <template v-if="sources.unassignedCount > 0">
        {{ t('adminLocations.sources.unassignedSummary', { sources: sources.unassignedCount, files: sources.waitingCount }) }}
      </template>
      <template v-else>{{ t('adminLocations.sources.allAssigned') }}</template>
    </p>

    <div
      v-if="sources.error"
      class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800"
      role="alert"
      data-testid="sources-error"
    >
      {{ sources.error }}
    </div>
    <div
      v-if="actionError"
      class="mb-3 flex items-start gap-2 rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800"
      role="alert"
      data-testid="sources-action-error"
    >
      <span class="flex-1">{{ actionError }}</span>
      <button
        type="button"
        class="shrink-0 text-rose-500 hover:text-rose-700 muw-focus"
        :aria-label="t('common.dismiss')"
        data-testid="sources-action-error-dismiss"
        @click="actionError = null"
      >✕</button>
    </div>

    <DenseTable>
      <template #header>
        <tr class="border-b border-slate-200">
          <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.sources.column.kind') }}</th>
          <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.sources.column.name') }}</th>
          <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.sources.column.location') }}</th>
          <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.sources.column.waiting') }}</th>
          <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.sources.column.lastSeen') }}</th>
          <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.sources.column.status') }}</th>
        </tr>
      </template>

      <tr v-if="sources.isLoading">
        <td :colspan="6" class="px-3 py-6 text-center text-slate-500 italic">{{ t('common.loading') }}</td>
      </tr>
      <tr v-else-if="sources.sources.length === 0 && !sources.error">
        <td :colspan="6" class="px-3 py-6 text-center text-slate-500" data-testid="sources-empty">
          {{ t('adminLocations.sources.empty') }}
        </td>
      </tr>

      <template v-else>
        <tr
          v-for="s in sources.sources"
          :key="s.id"
          :class="needsLocation(s) ? 'bg-amber-50/70' : ''"
          :data-testid="`source-row-${s.id}`"
          :data-needs-location="needsLocation(s) ? 'true' : undefined"
          :aria-busy="busy.includes(s.id) ? 'true' : undefined"
        >
          <td class="px-3 py-2" :data-testid="`source-kind-${s.id}`">{{ kindLabel(s.kind) }}</td>
          <td class="px-3 py-2" :data-testid="`source-name-${s.id}`">
            <span class="font-medium text-slate-900">{{ s.displayName }}</span>
            <span v-if="s.sourceKey" class="ml-1 font-mono text-[11px] text-slate-500">{{ s.sourceKey }}</span>
          </td>
          <td class="px-3 py-2 w-64">
            <SelectInput
              :id="`source-location-${s.id}`"
              :model-value="shownLocation(s)"
              :aria-label="t('adminLocations.sources.locationAria', { name: s.displayName })"
              @update:model-value="(v) => assign(s, v)"
            >
              <option value="">{{ t('adminLocations.sources.unassigned') }}</option>
              <option v-for="c in choicesFor(s)" :key="c.id" :value="String(c.id)">{{ c.label }}</option>
            </SelectInput>
          </td>
          <td class="px-3 py-2" :data-testid="`source-waiting-${s.id}`">
            <template v-if="needsLocation(s)">
              <StatusPill variant="warning">{{ t('adminLocations.sources.waiting', s.pendingItemCount) }}</StatusPill>
              <span class="sr-only"> {{ t('adminLocations.sources.waitingHint') }}</span>
            </template>
            <span v-else class="tabular-nums">{{ s.pendingItemCount }}</span>
          </td>
          <td class="px-3 py-2 text-xs text-slate-700" :data-testid="`source-seen-${s.id}`">
            <time v-if="s.lastSeenAt" :datetime="s.lastSeenAt" :title="formatInstant(s.lastSeenAt, locale)">{{ ago(s.lastSeenAt) }}</time>
            <span v-else class="text-slate-500">{{ t('adminLocations.sources.neverSeen') }}</span>
          </td>
          <td class="px-3 py-2" :data-testid="`source-status-${s.id}`">
            <StatusPill :variant="s.status === 'active' ? 'success' : 'danger'">
              {{ t(`uploaders.status.${s.status}`) }}
            </StatusPill>
          </td>
        </tr>
      </template>
    </DenseTable>
  </section>
</template>

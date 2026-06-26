<script setup lang="ts">
/**
 * 2026-06-19 — RetinalParkedAdminView.
 *
 * Administrator-only cross-study list of {@code retinal_inference_job}
 * rows in {@code status='parked'}. These rows have NO
 * {@code study_subject_id} linkage (event_crf_id IS NULL), so the
 * per-subject {@code ParkedScansList} can never surface them — this
 * view is the only path the operator has back to a parked upload.
 *
 * <p>Why sysadmin-only: parked rows cross every study by definition;
 * the per-study {@code SiteVisibilityFilter} can't gate them
 * meaningfully. The MUW deployment runs a single sysadmin + two
 * service accounts (per institutional decision), so funneling cleanup
 * through the sysadmin role matches operational reality.
 *
 * <p>Architectural background + the two fix-option trade-offs are
 * captured in {@code docs/development/modernization/retinal-jobs-admin-followup.md}
 * (Option B is what this view implements).
 *
 * <p>2026-06-20 B2 — bulk selection + bulk-bind toolbar. The common
 * case is one upload session that emitted multiple scans (OD + OS or
 * repeat acquisitions) all sharing the same visit; clicking
 * "Markierte zuweisen" runs the same two-step wizard but emits a
 * {@code jobIds: number[]} payload so the backend's bulk endpoint
 * binds the whole batch in one round-trip.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import AssignParkedDialog from '@/components/retinal/AssignParkedDialog.vue'

import { useRetinalParkedStore } from '@/stores/retinalParked'
import type { ParkedJobAdminRow } from '@/api/retinal'

const { t } = useI18n()
const store = useRetinalParkedStore()

onMounted(() => { void store.load() })

/** Active dialog target — array of jobIds being bound (length 1 for
 *  the per-row "Zuordnen", length N for the bulk-toolbar action).
 *  Null when no dialog is mounted. */
const activeJobIds = ref<number[] | null>(null)
const activeInitialPatientId = ref<string>('')
/** Soft toast after a successful or raced bind. */
const toastMessage = ref<string | null>(null)
const toastTone = ref<'ok' | 'warn'>('ok')

const rows = computed<ParkedJobAdminRow[]>(() => store.list)

/** Set of selected job_ids. Cleared on load + after a successful bind. */
const selectedIds = ref<Set<number>>(new Set())

const allSelected = computed(
  () => rows.value.length > 0 && selectedIds.value.size === rows.value.length,
)

const someSelected = computed(
  () => selectedIds.value.size > 0 && selectedIds.value.size < rows.value.length,
)

function isSelected(jobId: number): boolean {
  return selectedIds.value.has(jobId)
}

function toggleRow(jobId: number): void {
  const next = new Set(selectedIds.value)
  if (next.has(jobId)) next.delete(jobId)
  else next.add(jobId)
  selectedIds.value = next
}

function toggleAll(): void {
  if (allSelected.value) {
    selectedIds.value = new Set()
  } else {
    selectedIds.value = new Set(rows.value.map((r) => r.jobId))
  }
}

function clearSelection(): void {
  selectedIds.value = new Set()
}

function openAssign(row: ParkedJobAdminRow): void {
  activeJobIds.value = [row.jobId]
  activeInitialPatientId.value = row.patientId ?? ''
  toastMessage.value = null
}

/**
 * Bulk-toolbar action — opens the dialog with the full selection. The
 * seed PatientId is taken from the first selected row (most upload
 * sessions belong to a single patient; the operator can clear in
 * step 1 if not).
 */
function openBulkAssign(): void {
  const ids = Array.from(selectedIds.value)
  if (ids.length === 0) return
  const firstRow = rows.value.find((r) => r.jobId === ids[0])
  activeJobIds.value = ids
  activeInitialPatientId.value = firstRow?.patientId ?? ''
  toastMessage.value = null
}

function onAssignClose(): void {
  activeJobIds.value = null
}

async function onAssignBind(payload: {
  jobIds: number[]
  eventCrfId: number
}): Promise<void> {
  const jobIds = payload.jobIds
  // Single-row path: stick with the original single-bind endpoint so
  // existing semantics (409 raced toast, optimistic row removal) keep
  // working byte-for-byte. Multi-row path: go through the bulk
  // endpoint so the backend's per-row outcomes drive the summary toast.
  if (jobIds.length === 1) {
    try {
      const ok = await store.bind(jobIds[0], payload.eventCrfId)
      activeJobIds.value = null
      toastMessage.value = ok
        ? t('retinalParked.toast.bindOk')
        : t('retinalParked.toast.bindRaced')
      toastTone.value = ok ? 'ok' : 'warn'
      clearSelection()
    } catch {
      activeJobIds.value = null
    } finally {
      await store.load()
    }
    return
  }
  try {
    const response = await store.bulkBind(jobIds, payload.eventCrfId)
    activeJobIds.value = null
    const s = response.summary
    const parts: string[] = []
    parts.push(t('retinalParked.bulkBind.bulkSummaryBound', { count: s.bound }))
    if (s.alreadyBound > 0) {
      parts.push(t('retinalParked.bulkBind.bulkSummaryAlreadyBound', { count: s.alreadyBound }))
    }
    if (s.forbidden > 0) {
      parts.push(t('retinalParked.bulkBind.bulkSummaryForbidden', { count: s.forbidden }))
    }
    if (s.invalidState > 0) {
      parts.push(t('retinalParked.bulkBind.bulkSummaryInvalidState', { count: s.invalidState }))
    }
    toastMessage.value = parts.join(' · ')
    // Any non-BOUND row in the response means a partial — surface
    // the toast with a warn tone so the operator double-checks.
    toastTone.value = s.bound === jobIds.length ? 'ok' : 'warn'
    clearSelection()
  } catch {
    // store.error surfaces inline.
    activeJobIds.value = null
    toastMessage.value = t('retinalParked.bulkBind.bulkErrorPartial')
    toastTone.value = 'warn'
  } finally {
    await store.load()
  }
}

/**
 * The visit picker emitted no started event_crf for the picked event.
 * Surface a warn toast so the operator knows the bind didn't take and
 * what to do about it — silent close was the 2026-06-18 bug.
 */
function onNoEventCrf(): void {
  activeJobIds.value = null
  toastMessage.value = t('retinalParked.toast.noEventCrf')
  toastTone.value = 'warn'
}

function shortIsoDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  // Backend emits "yyyy-MM-ddTHH:mm:ssZ" — split off T + drop seconds.
  const t = iso.indexOf('T')
  if (t === -1) return iso
  const date = iso.slice(0, t)
  const time = iso.slice(t + 1, t + 6) // HH:mm
  return `${date} · ${time}`
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink
        to="/"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white"
      >
        {{ t('nav.home') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-5xl px-8 py-6">
      <div class="mb-4 flex items-end justify-between gap-4">
        <div>
          <h1 class="text-xl font-semibold tracking-tight">
            {{ t('retinalParked.title') }}
          </h1>
          <p class="text-[13px] text-slate-500 mt-1 max-w-[760px]">
            {{ t('retinalParked.subtitle') }}
          </p>
        </div>
        <button
          type="button"
          class="text-xs text-muw-blue hover:underline"
          data-testid="retinal-parked-refresh"
          @click="store.load()"
        >
          {{ t('retinalParked.action.refresh') }}
        </button>
      </div>

      <div
        v-if="toastMessage"
        :class="toastTone === 'ok' ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-amber-50 border-amber-200 text-amber-800'"
        class="mb-4 rounded-md border px-3 py-2 text-[13px]"
        data-testid="retinal-parked-toast"
      >
        {{ toastMessage }}
      </div>

      <!-- Bulk-action toolbar, appears as soon as ≥1 row is selected. -->
      <div
        v-if="selectedIds.size > 0"
        class="mb-3 flex items-center justify-between gap-3 rounded-md border border-sky-200 bg-sky-50 px-3 py-2 text-[13px]"
        data-testid="retinal-parked-bulk-toolbar"
      >
        <span class="text-sky-900" data-testid="retinal-parked-bulk-counter">
          {{
            t('retinalParked.bulkBind.selectedCount', {
              count: selectedIds.size,
              total: rows.length,
            })
          }}
        </span>
        <div class="flex items-center gap-3">
          <button
            type="button"
            class="text-xs text-sky-700 hover:underline"
            data-testid="retinal-parked-bulk-clear"
            @click="clearSelection"
          >
            {{ t('retinalParked.bulkBind.selectionClear') }}
          </button>
          <button
            type="button"
            class="rounded-md bg-sky-700 px-3 py-1 text-xs font-medium text-white hover:bg-sky-800"
            data-testid="retinal-parked-bulk-assign"
            @click="openBulkAssign"
          >
            {{ t('retinalParked.bulkBind.bulkAction') }}
          </button>
        </div>
      </div>

      <p
        v-if="store.isLoading && rows.length === 0"
        class="text-slate-500 italic"
      >
        {{ t('common.loading') }}
      </p>
      <p
        v-else-if="store.error"
        class="text-rose-700"
        data-testid="retinal-parked-error"
      >
        {{ store.error }}
      </p>

      <DenseTable v-else>
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium w-8">
              <input
                type="checkbox"
                :checked="allSelected"
                :indeterminate.prop="someSelected"
                :disabled="rows.length === 0"
                data-testid="retinal-parked-select-all"
                :aria-label="t('retinalParked.bulkBind.selectAllAria')"
                @change="toggleAll"
              />
            </th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.jobId') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.patientId') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.eye') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.task') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.uploadedAt') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">{{ t('retinalParked.columns.actions') }}</th>
          </tr>
        </template>

        <tr v-if="rows.length === 0">
          <td colspan="7" class="px-3 py-6 text-center text-slate-500 italic" data-testid="retinal-parked-empty">
            {{ t('retinalParked.empty') }}
          </td>
        </tr>

        <tr
          v-for="row in rows"
          :key="row.jobId"
          :data-testid="`parked-row-${row.jobId}`"
          :class="isSelected(row.jobId) ? 'bg-sky-50/60' : ''"
        >
          <td class="px-3 py-2 w-8">
            <input
              type="checkbox"
              :checked="isSelected(row.jobId)"
              :data-testid="`parked-select-${row.jobId}`"
              :aria-label="t('retinalParked.bulkBind.selectRowAria', { jobId: row.jobId })"
              @change="toggleRow(row.jobId)"
            />
          </td>
          <td class="px-3 py-2 font-mono text-xs text-slate-600">#{{ row.jobId }}</td>
          <td class="px-3 py-2 font-mono text-[12px]">
            <span v-if="row.patientId">{{ row.patientId }}</span>
            <span v-else class="text-slate-300 italic">{{ t('retinalParked.unknownPatient') }}</span>
          </td>
          <td class="px-3 py-2 font-mono text-xs">{{ row.laterality }}</td>
          <td class="px-3 py-2 text-slate-600 text-xs">{{ row.task || '—' }}</td>
          <td class="px-3 py-2 text-slate-600 text-xs font-mono">{{ shortIsoDate(row.enqueuedAt) }}</td>
          <td class="px-3 py-2 text-right whitespace-nowrap">
            <button
              type="button"
              class="text-xs text-muw-blue hover:underline disabled:opacity-50"
              :data-testid="`parked-assign-${row.jobId}`"
              :disabled="store.bindingJobId === row.jobId"
              @click="openAssign(row)"
            >
              {{ t('retinalParked.action.assign') }}
            </button>
          </td>
        </tr>
      </DenseTable>
    </main>

    <AssignParkedDialog
      v-if="activeJobIds"
      :open="activeJobIds !== null"
      :job-ids="activeJobIds"
      :initial-patient-id="activeInitialPatientId"
      @bind="onAssignBind"
      @no-event-crf="onNoEventCrf"
      @close="onAssignClose"
    />
  </div>
</template>

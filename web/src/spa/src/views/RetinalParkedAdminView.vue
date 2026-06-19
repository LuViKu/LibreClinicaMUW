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

/** Active dialog target — null when no row is being assigned. */
const activeRow = ref<ParkedJobAdminRow | null>(null)
/** Soft toast after a successful or raced bind. */
const toastMessage = ref<string | null>(null)
const toastTone = ref<'ok' | 'warn'>('ok')

const rows = computed<ParkedJobAdminRow[]>(() => store.list)

function openAssign(row: ParkedJobAdminRow): void {
  activeRow.value = row
  toastMessage.value = null
}

function onAssignClose(): void {
  activeRow.value = null
}

async function onAssignBind(payload: { jobId: number; eventCrfId: number }): Promise<void> {
  try {
    const ok = await store.bind(payload.jobId, payload.eventCrfId)
    activeRow.value = null
    toastMessage.value = ok
      ? t('retinalParked.toast.bindOk')
      : t('retinalParked.toast.bindRaced')
    toastTone.value = ok ? 'ok' : 'warn'
  } catch {
    // store.error surfaces inline; close the dialog so the operator
    // can see the page-level error.
    activeRow.value = null
  } finally {
    // Always re-fetch — defensive against an optimistic-removal /
    // backend-state divergence the 2026-06-18 smoke uncovered.
    await store.load()
  }
}

/**
 * The visit picker emitted no started event_crf for the picked event.
 * Surface a warn toast so the operator knows the bind didn't take and
 * what to do about it — silent close was the 2026-06-18 bug.
 */
function onNoEventCrf(): void {
  activeRow.value = null
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
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.jobId') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.patientId') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.eye') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.task') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('retinalParked.columns.uploadedAt') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">{{ t('retinalParked.columns.actions') }}</th>
          </tr>
        </template>

        <tr v-if="rows.length === 0">
          <td colspan="6" class="px-3 py-6 text-center text-slate-500 italic" data-testid="retinal-parked-empty">
            {{ t('retinalParked.empty') }}
          </td>
        </tr>

        <tr
          v-for="row in rows"
          :key="row.jobId"
          :data-testid="`parked-row-${row.jobId}`"
        >
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
      v-if="activeRow"
      :open="activeRow !== null"
      :job-id="activeRow.jobId"
      :initial-patient-id="activeRow.patientId ?? ''"
      @bind="onAssignBind"
      @no-event-crf="onNoEventCrf"
      @close="onAssignClose"
    />
  </div>
</template>

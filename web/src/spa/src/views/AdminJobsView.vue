<script setup lang="ts">
/**
 * Phase E.8 Slice L5 (2026-06-20) — sysadmin Quartz-trigger list,
 * SPA replacement for the legacy ViewAllJobsServlet + the
 * ViewJob / ViewImportJob family.
 *
 * Read-only, except for legacy scheduled exports (made by the retiring
 * /CreateJobExport): those are listed with what they were set to do and
 * can be deleted, once an equivalent SPA schedule exists on the dataset.
 * New export schedules are set up per dataset under Data Export. Pause /
 * pause-all stay out of scope; see the backend controller's javadoc.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SystemRail from '@/components/SystemRail.vue'
import { apiDelete, apiGet, ApiError } from '@/api/client'
import { useConfirm } from '@/composables/useConfirm'

const { t } = useI18n()
const confirm = useConfirm()

/** What a legacy scheduled export was set to do, from its stored job data. */
interface LegacyExport {
  jobName?: string | null
  datasetId?: number | null
  period?: string | null
  exportFormat?: string | null
  contactEmail?: string | null
}

interface JobRow {
  name: string
  group: string
  description: string | null
  priority: number
  previousFireTime: string | null
  nextFireTime: string | null
  finalFireTime: string | null
  state: string
  jobName?: string
  jobGroup?: string
  /** Present for a legacy scheduled export — the only kind that can be deleted here. */
  legacyExport?: LegacyExport
  /** The store could not read this trigger's data; name, group and state are all it has. */
  unreadable?: boolean
}

interface JobsResponse {
  schedulerName: string
  isStarted: boolean
  isStandby: boolean
  jobs: JobRow[]
}

const data = ref<JobsResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const lastRefreshed = ref<number | null>(null)
const deleting = ref<string | null>(null)

const hasLegacy = computed(() => (data.value?.jobs ?? []).some((j) => j.legacyExport))

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await apiGet<JobsResponse>('/pages/api/v1/admin/jobs')
    lastRefreshed.value = Date.now()
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminJobs.loadFailed')
  } finally {
    loading.value = false
  }
}

async function deleteLegacy(row: JobRow) {
  if (!(await confirm({ message: t('adminJobs.legacy.confirmDelete', { name: row.name }), danger: true }))) return
  deleting.value = row.name
  error.value = null
  try {
    await apiDelete(`/pages/api/v1/admin/jobs/legacy-exports/${encodeURIComponent(row.name)}`)
    await load()
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminJobs.legacy.deleteFailed')
  } finally {
    deleting.value = null
  }
}

function legacySummary(l: LegacyExport): string {
  return [
    l.datasetId != null ? t('adminJobs.legacy.dataset', { id: l.datasetId }) : null,
    l.period,
    l.exportFormat,
    l.contactEmail,
  ].filter((part) => part).join(' · ')
}

function fmt(iso: string | null): string {
  if (!iso) return '—'
  const t = Date.parse(iso)
  return Number.isNaN(t) ? iso : new Date(t).toLocaleString()
}

function stateBadge(state: string): string {
  if (state === 'NORMAL') return 'bg-emerald-50 text-emerald-800 border-emerald-200'
  if (state === 'PAUSED') return 'bg-amber-50 text-amber-800 border-amber-200'
  if (state === 'ERROR' || state === 'BLOCKED') return 'bg-rose-50 text-rose-800 border-rose-200'
  return 'bg-slate-50 text-slate-700 border-slate-200'
}

onMounted(load)
</script>

<template>
  <div class="flex">
    <SystemRail />

  <div class="flex-1 max-w-5xl px-8 py-6">
    <div class="flex items-baseline justify-between mb-4">
      <h1 class="text-base font-semibold tracking-tight">{{ t('adminJobs.title') }}</h1>
      <div class="flex items-center gap-3 text-xs text-slate-500">
        <span v-if="lastRefreshed">{{ t('adminJobs.refreshedAt', { ts: new Date(lastRefreshed).toLocaleTimeString() }) }}</span>
        <button type="button" class="px-3 py-1.5 border border-slate-300 rounded bg-white hover:bg-slate-50 text-xs muw-focus" :disabled="loading" @click="load">
          {{ loading ? t('common.loading') : t('adminJobs.refresh') }}
        </button>
      </div>
    </div>

    <p class="text-xs text-slate-500 mb-4">{{ t('adminJobs.subtitle') }}</p>

    <p
      v-if="hasLegacy"
      class="mb-3 rounded-md bg-amber-50 border border-amber-200 px-3 py-2 text-xs text-amber-900"
      data-testid="legacy-export-note"
    >
      {{ t('adminJobs.legacy.note') }}
    </p>

    <div v-if="error" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800" role="alert">{{ error }}</div>

    <div v-if="data" class="mb-4 flex gap-3 text-[11px] text-slate-500">
      <span>{{ t('adminJobs.scheduler') }}: <span class="text-slate-700 font-medium">{{ data.schedulerName }}</span></span>
      <span>{{ data.isStarted ? t('adminJobs.started') : t('adminJobs.notStarted') }}</span>
      <span v-if="data.isStandby">{{ t('adminJobs.standby') }}</span>
    </div>

    <div v-if="data" class="overflow-x-auto rounded-md border border-slate-200 bg-white">
      <table class="w-full text-xs">
        <thead class="bg-slate-50 text-[11px] uppercase tracking-wider text-slate-500">
          <tr>
            <th class="px-3 py-2 text-left">{{ t('adminJobs.name') }}</th>
            <th class="px-3 py-2 text-left">{{ t('adminJobs.group') }}</th>
            <th class="px-3 py-2 text-left">{{ t('adminJobs.state') }}</th>
            <th class="px-3 py-2 text-left">{{ t('adminJobs.prevFire') }}</th>
            <th class="px-3 py-2 text-left">{{ t('adminJobs.nextFire') }}</th>
            <th class="px-3 py-2 text-left">{{ t('adminJobs.description') }}</th>
            <th class="px-3 py-2 text-right"><span class="sr-only">{{ t('common.actions') }}</span></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-for="row in data.jobs" :key="row.group + '/' + row.name" :data-testid="`job-${row.group}-${row.name}`">
            <td class="px-3 py-2 font-medium">
              {{ row.name }}
              <div v-if="row.legacyExport" class="mt-0.5 font-normal">
                <span class="inline-block px-1.5 py-0.5 rounded border border-amber-200 bg-amber-50 text-amber-800 text-[10px]">{{ t('adminJobs.legacy.badge') }}</span>
                <span v-if="row.unreadable" class="ml-1 text-[10px] text-rose-700">{{ t('adminJobs.legacy.unreadable') }}</span>
                <div v-else class="text-[11px] text-slate-500 break-words">{{ legacySummary(row.legacyExport) }}</div>
              </div>
            </td>
            <td class="px-3 py-2 text-slate-500">{{ row.group }}</td>
            <td class="px-3 py-2"><span class="inline-block px-2 py-0.5 rounded-full border text-[10px]" :class="stateBadge(row.state)">{{ row.state }}</span></td>
            <td class="px-3 py-2 text-slate-500">{{ fmt(row.previousFireTime) }}</td>
            <td class="px-3 py-2 text-slate-500">{{ fmt(row.nextFireTime) }}</td>
            <td class="px-3 py-2 text-slate-500 break-words">{{ row.description || '—' }}</td>
            <td class="px-3 py-2 text-right">
              <button
                v-if="row.legacyExport"
                type="button"
                class="px-2 py-0.5 border border-rose-200 rounded bg-white hover:bg-rose-50 text-rose-700 disabled:opacity-50 muw-focus"
                data-testid="legacy-export-delete"
                :disabled="deleting === row.name"
                @click="deleteLegacy(row)"
              >
                {{ deleting === row.name ? t('common.removing') : t('adminJobs.legacy.delete') }}
              </button>
            </td>
          </tr>
          <tr v-if="data.jobs.length === 0">
            <td colspan="7" class="px-3 py-6 text-center text-slate-400 italic">{{ t('adminJobs.noJobs') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
  </div>
</template>

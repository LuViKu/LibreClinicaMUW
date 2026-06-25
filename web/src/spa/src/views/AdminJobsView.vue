<script setup lang="ts">
/**
 * Phase E.8 Slice L5 (2026-06-20) — sysadmin Quartz-trigger list,
 * SPA replacement for the legacy ViewAllJobsServlet + the
 * ViewJob / ViewImportJob family.
 *
 * Read-only by design — pause / pause-all surfaces are out of scope
 * for this slice. See the backend controller's javadoc for the
 * rationale.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { apiGet, ApiError } from '@/api/client'

const { t } = useI18n()

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
  <div class="max-w-5xl mx-auto px-6 py-6">
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
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-for="row in data.jobs" :key="row.group + '/' + row.name">
            <td class="px-3 py-2 font-medium">{{ row.name }}</td>
            <td class="px-3 py-2 text-slate-500">{{ row.group }}</td>
            <td class="px-3 py-2"><span class="inline-block px-2 py-0.5 rounded-full border text-[10px]" :class="stateBadge(row.state)">{{ row.state }}</span></td>
            <td class="px-3 py-2 text-slate-500">{{ fmt(row.previousFireTime) }}</td>
            <td class="px-3 py-2 text-slate-500">{{ fmt(row.nextFireTime) }}</td>
            <td class="px-3 py-2 text-slate-500 break-words">{{ row.description || '—' }}</td>
          </tr>
          <tr v-if="data.jobs.length === 0">
            <td colspan="6" class="px-3 py-6 text-center text-slate-400 italic">{{ t('adminJobs.noJobs') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

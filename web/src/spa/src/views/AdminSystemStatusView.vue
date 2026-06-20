<script setup lang="ts">
/**
 * Phase E.8 Slice L3 (2026-06-20) — sysadmin system-status view.
 *
 * Polls /api/v1/admin/system-status on mount + manual refresh. Three
 * panels: JVM facts, database facts (Liquibase changelog count +
 * reachability probe), application status (OOM marker, uptime).
 *
 * Sysadmin-only — the backend returns 403 for non-sysadmin sessions
 * and the SPA router meta below requires the Administrator role.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { apiGet, ApiError } from '@/api/client'

const { t } = useI18n()

interface SystemStatus {
  jvm: {
    javaVersion: string
    vmName: string
    heapMaxMb: number
    heapUsedMb: number
    heapFreeMb: number
    threadCount: number
    availableProcessors: number
  }
  database: {
    liquibaseChangelogCount: number | null
    liquibaseError?: string
    reachable: boolean
    databaseProductName?: string
    databaseProductVersion?: string
    connectError?: string
  }
  application: {
    status: 'OK' | 'OutOfMemory'
    upSinceMillis: number
  }
}

const data = ref<SystemStatus | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const lastRefreshed = ref<number | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await apiGet<SystemStatus>('/pages/api/v1/admin/system-status')
    lastRefreshed.value = Date.now()
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminSystemStatus.loadFailed')
  } finally {
    loading.value = false
  }
}

function formatUptime(ms: number): string {
  if (!ms) return '—'
  const s = Math.floor(ms / 1000)
  const days = Math.floor(s / 86400)
  const hours = Math.floor((s % 86400) / 3600)
  const minutes = Math.floor((s % 3600) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes}m`
  return `${minutes}m`
}

onMounted(load)
</script>

<template>
  <div class="max-w-4xl mx-auto px-6 py-6">
    <div class="flex items-baseline justify-between mb-4">
      <h1 class="text-base font-semibold tracking-tight">{{ t('adminSystemStatus.title') }}</h1>
      <div class="flex items-center gap-3 text-xs text-slate-500">
        <span v-if="lastRefreshed">{{ t('adminSystemStatus.refreshedAt', { ts: new Date(lastRefreshed).toLocaleTimeString() }) }}</span>
        <button type="button" class="px-3 py-1.5 border border-slate-300 rounded bg-white hover:bg-slate-50 text-xs muw-focus" :disabled="loading" @click="load">
          {{ loading ? t('common.loading') : t('adminSystemStatus.refresh') }}
        </button>
      </div>
    </div>

    <div v-if="error" class="mb-4 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800" role="alert">{{ error }}</div>

    <div v-if="data" class="grid gap-4 md:grid-cols-3 text-xs">
      <section class="rounded-md border border-slate-200 bg-white p-4">
        <h2 class="text-sm font-medium mb-2">{{ t('adminSystemStatus.jvmHeading') }}</h2>
        <dl class="space-y-1">
          <div class="flex justify-between"><dt class="text-slate-500">Java</dt><dd>{{ data.jvm.javaVersion }}</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">VM</dt><dd class="text-right truncate ml-2">{{ data.jvm.vmName }}</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">Heap used</dt><dd>{{ data.jvm.heapUsedMb }} / {{ data.jvm.heapMaxMb }} MB</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">Threads</dt><dd>{{ data.jvm.threadCount }}</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">CPUs</dt><dd>{{ data.jvm.availableProcessors }}</dd></div>
        </dl>
      </section>

      <section class="rounded-md border border-slate-200 bg-white p-4">
        <h2 class="text-sm font-medium mb-2">{{ t('adminSystemStatus.dbHeading') }}</h2>
        <dl class="space-y-1">
          <div class="flex justify-between">
            <dt class="text-slate-500">{{ t('adminSystemStatus.reachable') }}</dt>
            <dd>
              <span :class="data.database.reachable ? 'text-emerald-700' : 'text-rose-700'">
                {{ data.database.reachable ? t('adminSystemStatus.yes') : t('adminSystemStatus.no') }}
              </span>
            </dd>
          </div>
          <div v-if="data.database.databaseProductName" class="flex justify-between">
            <dt class="text-slate-500">{{ t('adminSystemStatus.dbProduct') }}</dt>
            <dd class="text-right">{{ data.database.databaseProductName }} {{ data.database.databaseProductVersion }}</dd>
          </div>
          <div class="flex justify-between">
            <dt class="text-slate-500">{{ t('adminSystemStatus.changelogCount') }}</dt>
            <dd>{{ data.database.liquibaseChangelogCount ?? '—' }}</dd>
          </div>
          <div v-if="data.database.connectError" class="text-rose-700">{{ data.database.connectError }}</div>
        </dl>
      </section>

      <section class="rounded-md border border-slate-200 bg-white p-4">
        <h2 class="text-sm font-medium mb-2">{{ t('adminSystemStatus.appHeading') }}</h2>
        <dl class="space-y-1">
          <div class="flex justify-between">
            <dt class="text-slate-500">{{ t('adminSystemStatus.appStatus') }}</dt>
            <dd>
              <span :class="data.application.status === 'OK' ? 'text-emerald-700' : 'text-rose-700'">
                {{ data.application.status }}
              </span>
            </dd>
          </div>
          <div class="flex justify-between">
            <dt class="text-slate-500">{{ t('adminSystemStatus.uptime') }}</dt>
            <dd>{{ formatUptime(data.application.upSinceMillis) }}</dd>
          </div>
        </dl>
      </section>
    </div>
  </div>
</template>

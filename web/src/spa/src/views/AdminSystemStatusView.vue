<script setup lang="ts">
/**
 * Phase E.8 Slice L3 (2026-06-20) — sysadmin system-status view.
 *
 * Polls /api/v1/admin/system-status on mount + manual refresh. Three
 * panels: JVM facts, database facts (Liquibase changelog count +
 * reachability probe), application status (OOM marker, uptime).
 *
 * 2026-09-22 — a fourth panel, the retinal-inference cluster, fed by its
 * own request to /api/v1/admin/retinal-cluster so a dead GPU node running
 * out its probe timeout never delays the three panels above. One row per
 * node (state, registered tasks, host + GPU, latency) and the tail of the
 * app-VM cron monitor's log. Born of an outage nobody could see from
 * inside the app for nineteen days.
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

type ClusterNodeState = 'healthy' | 'degraded' | 'unhealthy' | 'unreachable'

interface ClusterNode {
  name: string
  url: string
  state: ClusterNodeState
  supportedTasks: string[]
  missingTasks: string[]
  latencyMs: number | null
  node: string | null
  gpuDevice: string | null
  gpuName: string | null
  error: string | null
}

interface ClusterStatus {
  configured: boolean
  remotePushUrl: string
  expectedTasks: string[]
  nodes: ClusterNode[]
  monitor?: { path: string; readable: boolean; lines: string[] }
}

const data = ref<SystemStatus | null>(null)
const cluster = ref<ClusterStatus | null>(null)
const clusterError = ref<string | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const lastRefreshed = ref<number | null>(null)

async function loadSystem() {
  error.value = null
  try {
    data.value = await apiGet<SystemStatus>('/pages/api/v1/admin/system-status')
    lastRefreshed.value = Date.now()
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminSystemStatus.loadFailed')
  }
}

async function loadCluster() {
  clusterError.value = null
  try {
    cluster.value = await apiGet<ClusterStatus>('/pages/api/v1/admin/retinal-cluster')
  } catch (err) {
    clusterError.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminSystemStatus.clusterLoadFailed')
  }
}

// The two requests are independent on purpose: the cluster probe waits on
// remote hosts and can take its full timeout; the local facts must not.
async function load() {
  loading.value = true
  try {
    await Promise.all([loadSystem(), loadCluster()])
  } finally {
    loading.value = false
  }
}

const stateClass: Record<ClusterNodeState, string> = {
  healthy: 'bg-emerald-50 text-emerald-800 border-emerald-200',
  degraded: 'bg-amber-50 text-amber-800 border-amber-200',
  unhealthy: 'bg-rose-50 text-rose-800 border-rose-200',
  unreachable: 'bg-rose-50 text-rose-800 border-rose-200',
}

function stateLabel(state: ClusterNodeState): string {
  return t(`adminSystemStatus.clusterState${state.charAt(0).toUpperCase()}${state.slice(1)}`)
}

function gpuLabel(n: ClusterNode): string {
  if (!n.gpuName && !n.gpuDevice) return '\u2014'
  const dev = n.gpuDevice ? `#${n.gpuDevice}` : ''
  return [n.gpuName, dev].filter(Boolean).join(' ')
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
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('adminSystemStatus.jvmJava') }}</dt><dd>{{ data.jvm.javaVersion }}</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">VM</dt><dd class="text-right truncate ml-2">{{ data.jvm.vmName }}</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('adminSystemStatus.jvmHeapUsed') }}</dt><dd>{{ data.jvm.heapUsedMb }} / {{ data.jvm.heapMaxMb }} MB</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('adminSystemStatus.jvmThreads') }}</dt><dd>{{ data.jvm.threadCount }}</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('adminSystemStatus.jvmCpus') }}</dt><dd>{{ data.jvm.availableProcessors }}</dd></div>
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

    <section class="mt-4 rounded-md border border-slate-200 bg-white p-4 text-xs" aria-labelledby="cluster-heading">
      <h2 id="cluster-heading" class="text-sm font-medium mb-2">{{ t('adminSystemStatus.clusterHeading') }}</h2>

      <div v-if="clusterError" class="rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-rose-800" role="alert">{{ clusterError }}</div>

      <template v-else-if="cluster">
        <p v-if="!cluster.configured" class="text-slate-500">{{ t('adminSystemStatus.clusterNotConfigured') }}</p>

        <template v-else>
          <p class="text-slate-500 mb-2">{{ t('adminSystemStatus.clusterTargetIs', { url: cluster.remotePushUrl }) }}</p>

          <table class="w-full border-collapse">
            <thead>
              <tr class="text-left text-slate-500 border-b border-slate-200">
                <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.clusterNode') }}</th>
                <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.clusterState') }}</th>
                <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.clusterTasks') }}</th>
                <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.clusterGpu') }}</th>
                <th scope="col" class="py-1 font-medium text-right">{{ t('adminSystemStatus.clusterLatency') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="n in cluster.nodes" :key="n.url" class="border-b border-slate-100 align-top" :data-node="n.name">
                <td class="py-1.5 pr-3">
                  <div class="font-medium">{{ n.name }}</div>
                  <div class="text-slate-500 truncate max-w-[16rem]">{{ n.node && n.node !== n.name ? `${n.node} \u00b7 ` : '' }}{{ n.url }}</div>
                </td>
                <td class="py-1.5 pr-3">
                  <span class="inline-block rounded border px-1.5 py-0.5" :class="stateClass[n.state]">{{ stateLabel(n.state) }}</span>
                  <div v-if="n.error" class="text-rose-700 mt-1 break-all">{{ n.error }}</div>
                </td>
                <td class="py-1.5 pr-3">
                  <span>{{ n.supportedTasks.length }} / {{ cluster.expectedTasks.length }}</span>
                  <div v-if="n.missingTasks.length" class="text-amber-800">{{ t('adminSystemStatus.clusterMissingTasks', { tasks: n.missingTasks.join(', ') }) }}</div>
                </td>
                <td class="py-1.5 pr-3">{{ gpuLabel(n) }}</td>
                <td class="py-1.5 text-right tabular-nums">{{ n.latencyMs != null ? `${n.latencyMs} ms` : '\u2014' }}</td>
              </tr>
            </tbody>
          </table>
        </template>

        <div v-if="cluster.monitor" class="mt-4">
          <h3 class="font-medium mb-1">{{ t('adminSystemStatus.clusterMonitorHeading') }}</h3>
          <p class="text-slate-500 mb-1">{{ t('adminSystemStatus.clusterMonitorHint') }}</p>
          <p v-if="!cluster.monitor.readable" class="text-amber-800">{{ t('adminSystemStatus.clusterMonitorUnavailable', { path: cluster.monitor.path }) }}</p>
          <p v-else-if="cluster.monitor.lines.length === 0" class="text-emerald-700">{{ t('adminSystemStatus.clusterMonitorEmpty') }}</p>
          <pre v-else class="rounded bg-slate-50 border border-slate-200 p-2 overflow-x-auto whitespace-pre-wrap break-all" data-testid="monitor-log">{{ cluster.monitor.lines.join('\n') }}</pre>
        </div>
      </template>
    </section>
  </div>
</template>

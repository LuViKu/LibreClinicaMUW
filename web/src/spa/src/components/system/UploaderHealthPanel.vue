<script setup lang="ts">
/**
 * DR-033 — the uploaders on the acquisition PCs, as the System Status page
 * shows them.
 *
 * One row per program that has sent a heartbeat (the Export Watcher on the
 * Clarus and Spectralis PCs, the Optomed Bridge beside the Optomed Client):
 * its state, when it last reported, the files it is holding and the ones it
 * gave up on, how full its disk is. Below, what actually arrived per device
 * — the two answer different questions: a program can report itself healthy
 * while the photographer exports into another folder, and files can arrive
 * through the upload page while the program on the PC is dead.
 *
 * Its own request (GET /api/v1/admin/uploaders), so a slow answer here never
 * holds up the JVM and database panels. Re-fetches when the page's refresh
 * button bumps `refreshKey`.
 */
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { apiDelete, apiGet, ApiError } from '@/api/client'
import { useConfirm } from '@/composables/useConfirm'
import { formatAgo, formatBytes, formatInstant, secondsSince } from '@/lib/byteFormat'
import type { Uploader, UploadersResponse } from '@/types/systemHealth'

const props = defineProps<{ refreshKey?: number }>()

const { t, te, locale } = useI18n()
const confirm = useConfirm()

const data = ref<UploadersResponse | null>(null)
const error = ref<string | null>(null)
const removing = ref<number | null>(null)

async function load() {
  error.value = null
  try {
    data.value = await apiGet<UploadersResponse>('/pages/api/v1/admin/uploaders')
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminSystemStatus.uploaders.loadFailed')
  }
}

onMounted(load)
watch(() => props.refreshKey, load)

/** Program names are product names: the same in every language, so not in the locale files. */
const PROGRAM_NAMES: Record<string, string> = {
  'export-watcher': 'Export Watcher',
  'optomed-bridge': 'Optomed Bridge',
}

function programName(kind: string): string {
  return PROGRAM_NAMES[kind] ?? kind
}

/** `server-unreachable` → `serverUnreachable`, the key the locale files use. */
function camel(code: string): string {
  return code.replace(/-([a-z0-9])/g, (_, c: string) => c.toUpperCase())
}

function statusLabel(u: Uploader): string {
  if (u.status === 'stopped') {
    return t(u.stopReason === 'session-end'
      ? 'adminSystemStatus.uploaders.statusShutdown'
      : 'adminSystemStatus.uploaders.statusStopped')
  }
  return t(`adminSystemStatus.uploaders.status${u.status.charAt(0).toUpperCase()}${u.status.slice(1)}`)
}

function statusClass(u: Uploader): string {
  switch (u.status) {
    case 'ok': return 'bg-emerald-50 text-emerald-800 border-emerald-200'
    case 'warning':
    case 'disabled': return 'bg-amber-50 text-amber-800 border-amber-200'
    // Logged off / shut down is the PC's evening, not a fault.
    case 'stopped': return u.stopReason === 'session-end'
      ? 'bg-slate-50 text-slate-700 border-slate-200'
      : 'bg-rose-50 text-rose-800 border-rose-200'
    default: return 'bg-rose-50 text-rose-800 border-rose-200'
  }
}

/** A known code in words; an unknown one (a newer program) as it came. */
function issueText(code: string): string {
  const key = `adminSystemStatus.uploaders.issue.${camel(code)}`
  return te(key) ? t(key) : code
}

function sourceLabel(kind: string): string {
  const key = `adminSystemStatus.uploaders.source.${camel(kind)}`
  return te(key) ? t(key) : kind
}

function ago(seconds: number | null): string {
  return formatAgo(seconds, locale.value)
}

function agoOf(iso: string | null): string {
  return iso ? ago(secondsSince(iso)) : '—'
}

function diskLine(u: Uploader): string {
  if (u.diskFreeBytes == null || u.diskTotalBytes == null || u.diskTotalBytes <= 0) return '—'
  return t('adminSystemStatus.uploaders.diskFree', {
    free: formatBytes(u.diskFreeBytes, locale.value),
    total: formatBytes(u.diskTotalBytes, locale.value),
  })
}

async function remove(u: Uploader) {
  const ok = await confirm({
    title: t('adminSystemStatus.uploaders.removeTitle'),
    message: t('adminSystemStatus.uploaders.removeMessage', { name: u.name, program: programName(u.kind) }),
    confirmLabel: t('adminSystemStatus.uploaders.remove'),
    danger: true,
  })
  if (!ok) return
  removing.value = u.id
  try {
    await apiDelete(`/pages/api/v1/admin/uploaders/${u.id}`)
    await load()
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminSystemStatus.uploaders.removeFailed')
  } finally {
    removing.value = null
  }
}
</script>

<template>
  <section class="mt-4 rounded-md border border-slate-200 bg-white p-4 text-xs" aria-labelledby="uploaders-heading" data-testid="uploader-panel">
    <h2 id="uploaders-heading" class="text-sm font-medium mb-1">{{ t('adminSystemStatus.uploaders.heading') }}</h2>
    <p class="text-slate-500 mb-2">{{ t('adminSystemStatus.uploaders.hint') }}</p>

    <div v-if="error" class="rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-rose-800" role="alert">{{ error }}</div>

    <template v-else-if="data">
      <p v-if="!data.heartbeatEnabled" class="mb-2 text-amber-800" data-testid="heartbeat-off">{{ t('adminSystemStatus.uploaders.heartbeatOff') }}</p>

      <p v-if="data.uploaders.length === 0" class="text-slate-500" data-testid="uploaders-empty">{{ t('adminSystemStatus.uploaders.empty') }}</p>

      <table v-else class="w-full border-collapse">
        <thead>
          <tr class="text-left text-slate-500 border-b border-slate-200">
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colProgram') }}</th>
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colState') }}</th>
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colLastContact') }}</th>
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colQueue') }}</th>
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colDisk') }}</th>
            <th scope="col" class="py-1 font-medium"><span class="sr-only">{{ t('adminSystemStatus.uploaders.colActions') }}</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in data.uploaders" :key="u.id" class="border-b border-slate-100 align-top" data-testid="uploader-row" :data-status="u.status">
            <td class="py-1.5 pr-3">
              <div class="font-medium">{{ u.name }}</div>
              <div class="text-slate-500">{{ programName(u.kind) }}<template v-if="u.version"> · {{ u.version }}</template></div>
            </td>
            <td class="py-1.5 pr-3">
              <span class="inline-block rounded border px-1.5 py-0.5" :class="statusClass(u)" data-testid="uploader-status">{{ statusLabel(u) }}</span>
              <ul v-if="u.issues.length" class="mt-1 space-y-0.5 text-amber-800" data-testid="uploader-issues">
                <li v-for="code in u.issues" :key="code">{{ issueText(code) }}</li>
              </ul>
            </td>
            <td class="py-1.5 pr-3">
              <div :title="formatInstant(u.lastSeenAt, locale)">{{ ago(u.secondsSinceSeen) }}</div>
              <div class="text-slate-500">
                {{ t('adminSystemStatus.uploaders.lastUpload', { when: agoOf(u.lastUploadAt) }) }}
              </div>
              <div v-if="u.uploadedToday != null" class="text-slate-500">
                {{ t('adminSystemStatus.uploaders.uploadedToday', { n: u.uploadedToday }) }}
              </div>
            </td>
            <td class="py-1.5 pr-3 tabular-nums">
              <div>{{ t('adminSystemStatus.uploaders.pending', { n: u.pendingFiles ?? 0 }) }}</div>
              <div v-if="u.oldestPendingMinutes != null && (u.pendingFiles ?? 0) > 0" class="text-slate-500">
                {{ t('adminSystemStatus.uploaders.oldestPending', { minutes: u.oldestPendingMinutes }) }}
              </div>
              <div v-if="(u.failedFiles ?? 0) > 0" class="text-rose-700">
                {{ t('adminSystemStatus.uploaders.failed', { n: u.failedFiles }) }}
              </div>
            </td>
            <td class="py-1.5 pr-3 whitespace-nowrap" :class="u.issues.includes('disk-low') ? 'text-amber-800' : ''">{{ diskLine(u) }}</td>
            <td class="py-1.5 text-right">
              <button
                type="button"
                class="px-2 py-1 border border-slate-300 rounded bg-white hover:bg-slate-50 muw-focus"
                :disabled="removing === u.id"
                :aria-label="t('adminSystemStatus.uploaders.removeAria', { name: u.name, program: programName(u.kind) })"
                data-testid="uploader-remove"
                @click="remove(u)"
              >{{ t('adminSystemStatus.uploaders.remove') }}</button>
            </td>
          </tr>
        </tbody>
      </table>

      <h3 class="font-medium mt-4 mb-1">{{ t('adminSystemStatus.uploaders.arrivalsHeading') }}</h3>
      <p class="text-slate-500 mb-1">{{ t('adminSystemStatus.uploaders.arrivalsHint') }}</p>
      <p v-if="data.ingestByDevice.length === 0" class="text-slate-500">{{ t('adminSystemStatus.uploaders.arrivalsEmpty') }}</p>
      <table v-else class="w-full border-collapse" data-testid="arrivals">
        <thead>
          <tr class="text-left text-slate-500 border-b border-slate-200">
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colDevice') }}</th>
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colIngress') }}</th>
            <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.uploaders.colLastFile') }}</th>
            <th scope="col" class="py-1 pr-3 font-medium text-right">{{ t('adminSystemStatus.uploaders.col24h') }}</th>
            <th scope="col" class="py-1 font-medium text-right">{{ t('adminSystemStatus.uploaders.col7d') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in data.ingestByDevice" :key="`${d.device}|${d.sourceKind}`" class="border-b border-slate-100" data-testid="arrival-row">
            <td class="py-1 pr-3">{{ d.device ?? t('adminSystemStatus.uploaders.deviceUnknown') }}</td>
            <td class="py-1 pr-3">{{ sourceLabel(d.sourceKind) }}</td>
            <td class="py-1 pr-3" :title="formatInstant(d.lastReceivedAt, locale)">{{ agoOf(d.lastReceivedAt) }}</td>
            <td class="py-1 pr-3 text-right tabular-nums">{{ d.last24h }}</td>
            <td class="py-1 text-right tabular-nums">{{ d.last7d }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>

<script setup lang="ts">
/**
 * DR-033 — the storage the platform uses, as the System Status page shows it.
 *
 * Reads the newest hourly scan (GET /api/v1/admin/storage): per disk, how
 * full it is and — when free space shrank over the past week — roughly when
 * it runs out; per file store, its size, its file count and the change
 * against a week earlier; the database's size and largest tables. A
 * sysadmin can measure now; the scan runs on the server's own thread and
 * this panel polls until it is done.
 *
 * Walking the stores is too slow to do on a page load, which is why the
 * figures carry the time they were measured.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { apiGet, apiPost, ApiError } from '@/api/client'
import { formatBytes, formatBytesDelta, formatInstant, formatPercent } from '@/lib/byteFormat'
import type { StorageFilesystem, StorageResponse, StorageStore } from '@/types/systemHealth'

const props = defineProps<{ refreshKey?: number }>()

const { t, te, locale } = useI18n()

const data = ref<StorageResponse | null>(null)
const error = ref<string | null>(null)
const requesting = ref(false)

/** Poll while a scan runs: every 3 s, for at most 5 minutes. */
const POLL_MS = 3000
const POLL_LIMIT = 100
let pollTimer: ReturnType<typeof setTimeout> | null = null
let polls = 0

async function load() {
  error.value = null
  try {
    data.value = await apiGet<StorageResponse>('/pages/api/v1/admin/storage')
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminSystemStatus.storage.loadFailed')
  }
  schedulePoll()
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
  if (!data.value?.scanning || polls >= POLL_LIMIT) {
    polls = 0
    return
  }
  polls++
  pollTimer = setTimeout(load, POLL_MS)
}

async function rescan() {
  requesting.value = true
  error.value = null
  try {
    await apiPost('/pages/api/v1/admin/storage/rescan', {})
    if (data.value) data.value = { ...data.value, scanning: true }
    polls = 0
    schedulePoll()
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminSystemStatus.storage.rescanFailed')
  } finally {
    requesting.value = false
  }
}

onMounted(load)
watch(() => props.refreshKey, load)
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
})

const storesByKey = computed(() => {
  const m = new Map<string, StorageStore>()
  for (const s of data.value?.stores ?? []) m.set(s.key, s)
  return m
})

function storeLabel(key: string): string {
  const k = `adminSystemStatus.storage.store.${key.replace(/-([a-z0-9])/g, (_, c: string) => c.toUpperCase())}`
  return te(k) ? t(k) : key
}

function bytes(n: number | null | undefined): string {
  return formatBytes(n, locale.value)
}

function delta(now: number, before: number | null): string {
  return before == null ? '—' : formatBytesDelta(now - before, locale.value)
}

function fsTitle(fs: StorageFilesystem): string {
  return fs.stores.map(storeLabel).join(', ')
}

function barClass(fs: StorageFilesystem): string {
  const p = fs.usedPercent ?? 0
  if (p >= 90) return 'bg-rose-500'
  if (p >= 80) return 'bg-amber-500'
  return 'bg-emerald-500'
}

/** Days until full: rose under a week, amber under a month, plain beyond. */
function fullClass(days: number): string {
  if (days < 7) return 'text-rose-700 font-medium'
  if (days < 30) return 'text-amber-800'
  return 'text-slate-600'
}

function number(n: number | null | undefined): string {
  return n == null ? '—' : new Intl.NumberFormat(locale.value).format(n)
}
</script>

<template>
  <section class="mt-4 rounded-md border border-slate-200 bg-white p-4 text-xs" aria-labelledby="storage-heading" data-testid="storage-panel">
    <div class="flex items-baseline justify-between mb-1">
      <h2 id="storage-heading" class="text-sm font-medium">{{ t('adminSystemStatus.storage.heading') }}</h2>
      <div class="flex items-center gap-3 text-slate-500">
        <span v-if="data?.sampledAt" data-testid="storage-sampled-at">{{ t('adminSystemStatus.storage.measuredAt', { when: formatInstant(data.sampledAt, locale) }) }}</span>
        <span v-if="data?.scanning" class="text-slate-600" role="status" data-testid="storage-scanning">{{ t('adminSystemStatus.storage.scanning') }}</span>
        <button
          type="button"
          class="px-3 py-1.5 border border-slate-300 rounded bg-white hover:bg-slate-50 muw-focus"
          :disabled="requesting || data?.scanning"
          data-testid="storage-rescan"
          @click="rescan"
        >{{ t('adminSystemStatus.storage.rescan') }}</button>
      </div>
    </div>
    <p class="text-slate-500 mb-2">{{ t('adminSystemStatus.storage.hint') }}</p>

    <div v-if="error" class="rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-rose-800" role="alert">{{ error }}</div>

    <template v-else-if="data">
      <p v-if="!data.sampledAt" class="text-slate-500" data-testid="storage-none">{{ t('adminSystemStatus.storage.none') }}</p>

      <template v-else>
        <div v-for="fs in data.filesystems" :key="fs.key" class="mb-3" data-testid="storage-fs">
          <div class="flex items-baseline justify-between">
            <span class="font-medium">{{ t('adminSystemStatus.storage.disk', { stores: fsTitle(fs) }) }}</span>
            <span class="tabular-nums">{{ t('adminSystemStatus.storage.diskFree', { free: bytes(fs.usableBytes), total: bytes(fs.totalBytes) }) }}</span>
          </div>
          <div class="mt-1 h-2 w-full rounded bg-slate-100 overflow-hidden" aria-hidden="true">
            <div class="h-2" :class="barClass(fs)" :style="{ width: `${Math.min(100, fs.usedPercent ?? 0)}%` }" />
          </div>
          <div class="mt-1 flex flex-wrap gap-x-4 text-slate-600">
            <span data-testid="storage-used-percent">{{ t('adminSystemStatus.storage.usedPercent', { percent: formatPercent(fs.usedPercent, locale) }) }}</span>
            <span v-if="fs.daysUntilFull != null" :class="fullClass(fs.daysUntilFull)" data-testid="storage-days-until-full">
              {{ t('adminSystemStatus.storage.daysUntilFull', { days: Math.floor(fs.daysUntilFull) }) }}
            </span>
            <span v-else-if="fs.usableBytesBefore != null" data-testid="storage-not-filling">{{ t('adminSystemStatus.storage.notFilling') }}</span>
          </div>
          <p v-if="fs.containerLayer" class="mt-1 text-rose-700" role="alert">{{ t('adminSystemStatus.storage.containerLayer') }}</p>
        </div>

        <table class="w-full border-collapse mt-2" data-testid="storage-stores">
          <thead>
            <tr class="text-left text-slate-500 border-b border-slate-200">
              <th scope="col" class="py-1 pr-3 font-medium">{{ t('adminSystemStatus.storage.colStore') }}</th>
              <th scope="col" class="py-1 pr-3 font-medium text-right">{{ t('adminSystemStatus.storage.colSize') }}</th>
              <th scope="col" class="py-1 pr-3 font-medium text-right">{{ t('adminSystemStatus.storage.colFiles') }}</th>
              <th scope="col" class="py-1 font-medium text-right">{{ t('adminSystemStatus.storage.colWeek') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in data.stores" :key="s.key" class="border-b border-slate-100 align-top" data-testid="storage-store" :data-store="s.key">
              <td class="py-1 pr-3">
                <div>{{ storeLabel(s.key) }}</div>
                <div class="text-slate-500 break-all">{{ s.path }}</div>
                <div v-if="!s.present" class="text-slate-500">{{ t('adminSystemStatus.storage.absent') }}</div>
                <div v-else-if="!s.complete" class="text-amber-800">{{ t('adminSystemStatus.storage.incomplete') }}</div>
                <div v-if="s.containerLayer" class="text-rose-700">{{ t('adminSystemStatus.storage.storeContainerLayer') }}</div>
              </td>
              <td class="py-1 pr-3 text-right tabular-nums whitespace-nowrap">{{ s.present ? bytes(s.usedBytes) : '—' }}</td>
              <td class="py-1 pr-3 text-right tabular-nums whitespace-nowrap">{{ s.present ? number(s.fileCount) : '—' }}</td>
              <td class="py-1 text-right tabular-nums whitespace-nowrap">{{ s.present ? delta(s.usedBytes, s.usedBytesBefore) : '—' }}</td>
            </tr>
            <tr class="align-top" data-testid="storage-database">
              <td class="py-1 pr-3">
                <div>{{ t('adminSystemStatus.storage.database') }}</div>
                <div v-if="data.database.largestTables.length" class="text-slate-500">
                  {{ t('adminSystemStatus.storage.largestTables', {
                    tables: data.database.largestTables.map((x) => `${x.name} ${bytes(x.bytes)}`).join(', '),
                  }) }}
                </div>
              </td>
              <td class="py-1 pr-3 text-right tabular-nums whitespace-nowrap">{{ bytes(data.database.sizeBytes) }}</td>
              <td class="py-1 pr-3 text-right">{{ '—' }}</td>
              <td class="py-1 text-right tabular-nums whitespace-nowrap">{{ delta(data.database.sizeBytes, data.database.sizeBytesBefore) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="storesByKey.size && data.stores.some((s) => s.usedBytesBefore == null)" class="mt-1 text-slate-500">{{ t('adminSystemStatus.storage.noWeekYet') }}</p>
      </template>
    </template>
  </section>
</template>

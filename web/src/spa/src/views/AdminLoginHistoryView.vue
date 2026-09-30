<script setup lang="ts">
/**
 * R1.1 (2026-09-30) — login history, the SPA replacement for the legacy
 * /AuditUserActivity page.
 *
 * Reads /api/v1/admin/login-history: one page of audit_user_login, newest
 * first, filtered on the server by user, status and date range, with the
 * total of the whole filtered result. Every status reads as words, the SSO
 * codes (Phase D.5) included. The CSV export takes the same filters and holds
 * every row they select, not the page on screen.
 *
 * Sysadmin-only — the backend answers 403 to anyone else, and the route meta
 * requires the Administrator role.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import DateInput from '@/components/DateInput.vue'
import DenseTable from '@/components/DenseTable.vue'
import SelectInput from '@/components/SelectInput.vue'
import SkeletonRow from '@/components/SkeletonRow.vue'
import StatusPill from '@/components/StatusPill.vue'
import SystemRail from '@/components/SystemRail.vue'
import TextInput from '@/components/TextInput.vue'

import { apiGet, ApiError } from '@/api/client'
import { apiDownload } from '@/api/download'

const { t } = useI18n()

type LoginStatus =
  | 'SUCCESSFUL_LOGIN'
  | 'FAILED_LOGIN'
  | 'FAILED_LOGIN_LOCKED'
  | 'SUCCESSFUL_LOGOUT'
  | 'ACCESS_CODE_VIEWED'
  | 'SSO_LOGIN'
  | 'SSO_LOGIN_FAILED'
  | 'UNKNOWN'

interface LoginAttempt {
  id: number
  userName: string | null
  userAccountId: number | null
  /** ISO-8601 instant (UTC). */
  attemptedAt: string | null
  status: LoginStatus
  statusCode: number | null
  details: string | null
}

interface LoginHistoryPage {
  totalCount: number
  page: number
  pageSize: number
  rows: LoginAttempt[]
}

const PAGE_SIZE = 50
const BASE = '/pages/api/v1/admin/login-history'

/** Every way a login can be refused, the SSO refusal included. */
const REFUSED = 'FAILED_LOGIN,FAILED_LOGIN_LOCKED,SSO_LOGIN_FAILED'

const STATUSES: readonly Exclude<LoginStatus, 'UNKNOWN'>[] = [
  'SUCCESSFUL_LOGIN',
  'FAILED_LOGIN',
  'FAILED_LOGIN_LOCKED',
  'SUCCESSFUL_LOGOUT',
  'SSO_LOGIN',
  'SSO_LOGIN_FAILED',
  'ACCESS_CODE_VIEWED',
]

const statusVariant: Record<LoginStatus, 'success' | 'danger' | 'warning' | 'info' | 'neutral'> = {
  SUCCESSFUL_LOGIN: 'success',
  SSO_LOGIN: 'success',
  SUCCESSFUL_LOGOUT: 'neutral',
  FAILED_LOGIN: 'danger',
  SSO_LOGIN_FAILED: 'danger',
  FAILED_LOGIN_LOCKED: 'warning',
  ACCESS_CODE_VIEWED: 'info',
  UNKNOWN: 'neutral',
}

const user = ref('')
const status = ref('')
const from = ref('')
const to = ref('')
const page = ref(0)

const data = ref<LoginHistoryPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const exporting = ref(false)
const exportError = ref<string | null>(null)

const statusOptions = computed(() => [
  { value: '', label: t('adminLoginHistory.filter.allStatuses') },
  { value: REFUSED, label: t('adminLoginHistory.filter.allRefused') },
  ...STATUSES.map((s) => ({ value: s, label: t(`adminLoginHistory.status.${s}`) })),
])

const rows = computed(() => data.value?.rows ?? [])
const total = computed(() => data.value?.totalCount ?? 0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const rangeStart = computed(() => (total.value === 0 ? 0 : page.value * PAGE_SIZE + 1))
const rangeEnd = computed(() => Math.min((page.value + 1) * PAGE_SIZE, total.value))
const canPrev = computed(() => page.value > 0 && !loading.value)
const canNext = computed(() => page.value + 1 < totalPages.value && !loading.value)
const hasFilters = computed(() => !!(user.value.trim() || status.value || from.value || to.value))

/** The filters as query parameters; the page and the export share them. */
function filterQuery(): URLSearchParams {
  const q = new URLSearchParams()
  if (user.value.trim()) q.set('user', user.value.trim())
  if (status.value) q.set('status', status.value)
  if (from.value) q.set('from', from.value)
  if (to.value) q.set('to', to.value)
  return q
}

/** A refused filter names its reason; anything else says what failed. */
function describe(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    const body = err.body as { errors?: { field: string; message: string }[] } | null
    if (err.status === 400 && body?.errors?.length) return body.errors.map((e) => e.message).join(' ')
    return `${err.status}: ${err.message}`
  }
  return fallback
}

/** Only the newest request may fill the table; a slower older one is dropped. */
let latest = 0

async function load(): Promise<void> {
  const mine = ++latest
  loading.value = true
  error.value = null
  const q = filterQuery()
  q.set('page', String(page.value))
  q.set('pageSize', String(PAGE_SIZE))
  try {
    const result = await apiGet<LoginHistoryPage>(`${BASE}?${q.toString()}`)
    if (mine === latest) data.value = result
  } catch (err) {
    if (mine === latest) {
      data.value = null
      error.value = describe(err, t('adminLoginHistory.loadFailed'))
    }
  } finally {
    if (mine === latest) loading.value = false
  }
}

let typing: ReturnType<typeof setTimeout> | null = null

// A new filter starts again at the first page. Typing waits for a pause.
watch([user, status, from, to], ([nextUser], [previousUser]) => {
  if (typing) clearTimeout(typing)
  const run = () => {
    page.value = 0
    void load()
  }
  if (nextUser !== previousUser) typing = setTimeout(run, 300)
  else run()
})

function clearFilters(): void {
  user.value = ''
  status.value = ''
  from.value = ''
  to.value = ''
}

function goPrev(): void {
  if (!canPrev.value) return
  page.value--
  void load()
}

function goNext(): void {
  if (!canNext.value) return
  page.value++
  void load()
}

async function exportCsv(): Promise<void> {
  if (exporting.value) return
  exporting.value = true
  exportError.value = null
  const q = filterQuery().toString()
  try {
    await apiDownload(`${BASE}/export.csv${q ? `?${q}` : ''}`, 'login-history.csv')
  } catch (err) {
    exportError.value = describe(err, t('adminLoginHistory.exportFailed'))
  } finally {
    exporting.value = false
  }
}

function statusLabel(row: LoginAttempt): string {
  if (row.status === 'UNKNOWN') return t('adminLoginHistory.status.UNKNOWN', { code: row.statusCode ?? '—' })
  return t(`adminLoginHistory.status.${row.status}`)
}

/** Local date and time to the second: DD.MM.YYYY HH:MM:SS. */
function formatAttempt(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getDate())}.${p(d.getMonth() + 1)}.${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

onMounted(load)
onBeforeUnmount(() => {
  if (typing) clearTimeout(typing)
})
</script>

<template>
  <div class="flex">
    <SystemRail />

  <div class="flex-1 px-8 py-6">
    <div class="mb-5">
      <div class="text-xs text-slate-500 mb-1">{{ t('adminLoginHistory.subtitle') }}</div>
      <h1 class="text-xl font-semibold tracking-tight">{{ t('adminLoginHistory.title') }}</h1>
    </div>

    <div class="flex flex-wrap items-end gap-3 mb-4 text-xs">
      <div class="w-56">
        <label for="lh-user" class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('adminLoginHistory.filter.user') }}</label>
        <TextInput id="lh-user" v-model="user" type="search" :placeholder="t('adminLoginHistory.filter.userPlaceholder')" />
      </div>
      <div class="w-60">
        <label for="lh-status" class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('adminLoginHistory.filter.status') }}</label>
        <SelectInput id="lh-status" v-model="status">
          <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
        </SelectInput>
      </div>
      <div class="w-40">
        <label for="lh-from" class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('adminLoginHistory.filter.from') }}</label>
        <DateInput id="lh-from" v-model="from" :max="to || undefined" />
      </div>
      <div class="w-40">
        <label for="lh-to" class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('adminLoginHistory.filter.to') }}</label>
        <DateInput id="lh-to" v-model="to" :min="from || undefined" />
      </div>
      <button
        v-if="hasFilters"
        type="button"
        class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700 muw-focus"
        @click="clearFilters"
      >
        {{ t('common.clear') }}
      </button>

      <div class="ml-auto flex flex-col items-end gap-1">
        <button
          type="button"
          data-testid="login-history-export"
          class="px-3 py-2 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700 muw-focus disabled:opacity-60"
          :disabled="exporting"
          @click="exportCsv"
        >
          {{ exporting ? t('adminLoginHistory.exporting') : t('adminLoginHistory.exportCsv') }}
        </button>
        <span class="text-[10px] text-slate-400">{{ t('adminLoginHistory.exportHint') }}</span>
      </div>
    </div>

    <div v-if="exportError" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800" role="alert">{{ exportError }}</div>

    <DenseTable>
      <template #header>
        <tr class="border-b border-slate-200">
          <th scope="col" class="px-3 py-2 font-medium w-44">{{ t('adminLoginHistory.col.when') }}</th>
          <th scope="col" class="px-3 py-2 font-medium w-56">{{ t('adminLoginHistory.col.user') }}</th>
          <th scope="col" class="px-3 py-2 font-medium w-56">{{ t('adminLoginHistory.col.status') }}</th>
          <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLoginHistory.col.details') }}</th>
        </tr>
      </template>

      <template v-if="loading && !data && !error">
        <tr class="sr-only"><td colspan="4" role="status">{{ t('common.loading') }}</td></tr>
        <SkeletonRow :columns="4" :rows="6" />
      </template>

      <tr v-else-if="error">
        <td colspan="4" class="px-3 py-6 text-center text-rose-700" role="alert">{{ error }}</td>
      </tr>

      <tr v-else-if="rows.length === 0">
        <td colspan="4" class="px-3 py-6 text-center text-slate-500">{{ t('adminLoginHistory.empty') }}</td>
      </tr>

      <tr v-for="row in rows" v-else :key="row.id" :data-testid="`login-row-${row.id}`">
        <td class="px-3 py-2 font-mono text-xs text-slate-700 whitespace-nowrap">{{ formatAttempt(row.attemptedAt) }}</td>
        <td class="px-3 py-2 text-slate-800 break-all">{{ row.userName ?? '—' }}</td>
        <td class="px-3 py-2">
          <StatusPill compact :variant="statusVariant[row.status] ?? 'neutral'">{{ statusLabel(row) }}</StatusPill>
        </td>
        <td class="px-3 py-2 text-slate-600 font-mono text-xs break-all">{{ row.details ?? '' }}</td>
      </tr>

      <template #statusBar>
        <span>{{ t('adminLoginHistory.range', { start: rangeStart, end: rangeEnd, total }) }}</span>
        <div class="flex items-center gap-2">
          <button
            type="button"
            data-testid="page-prev"
            class="px-2 py-1 rounded border border-slate-200 bg-white text-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!canPrev"
            @click="goPrev"
          >
            {{ t('adminLoginHistory.prev') }}
          </button>
          <span class="text-slate-500 text-[11px]">{{ t('adminLoginHistory.pageOfTotal', { page: page + 1, total: totalPages }) }}</span>
          <button
            type="button"
            data-testid="page-next"
            class="px-2 py-1 rounded border border-slate-200 bg-white text-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!canNext"
            @click="goNext"
          >
            {{ t('adminLoginHistory.next') }}
          </button>
        </div>
      </template>
    </DenseTable>
  </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'

import { useSdvStore } from '@/stores/sdv'
import type { SdvRow, SdvRequirement, SdvStatus } from '@/types/sdv'

const { t } = useI18n()
const sdv = useSdvStore()

onMounted(() => {
  if (sdv.rows.length === 0) sdv.load()
})

const statusVariant = (s: SdvStatus): 'success' | 'info' | 'warning' | 'danger' | 'neutral' => {
  switch (s) {
    case 'verified':
    case 'locked':
      return 'success'
    case 'pending':
      return 'info'
    case 'query':
      return 'danger'
    default:
      return 'neutral'
  }
}

const requirementLabel = (r: SdvRequirement) => t(`sdv.requirement.${r}`)
const statusLabel = (s: SdvStatus) => t(`sdv.status.${s}`)

const MONTH_ABBR = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
function formatDate(iso: string): string {
  const [y, m, d] = iso.split('-').map((s) => Number.parseInt(s, 10))
  return `${String(d ?? 1).padStart(2, '0')}-${MONTH_ABBR[(m ?? 1) - 1] ?? '???'}-${y}`
}

/* ----- Bulk-verify confirmation modal ----- */
const confirmOpen = ref(false)
const justVerifiedCount = ref<number | null>(null)
async function openConfirm() {
  if (sdv.selectedCount === 0) return
  confirmOpen.value = true
}
async function confirmVerify() {
  confirmOpen.value = false
  justVerifiedCount.value = await sdv.verifySelected()
  setTimeout(() => { justVerifiedCount.value = null }, 4_000)
}

/* ----- Add Query modal — launched from a row action ----- */
type NoteType = 'query' | 'failed-validation' | 'annotation' | 'reason-for-change'
const queryOpen = ref(false)
const queryTarget = ref<SdvRow | null>(null)
const queryType = ref<NoteType>('query')
const queryDescription = ref('')
function openAddQuery(row: SdvRow) {
  queryTarget.value = row
  queryType.value = 'query'
  queryDescription.value = ''
  queryOpen.value = true
}
function submitQuery() {
  if (!queryTarget.value) return
  // Optimistic: bump the row's open-queries counter + flip status to 'query'.
  // Backend wiring lands in E.4 — see api-surface.md row 6.
  const oid = queryTarget.value.eventCrfOid
  const row = sdv.rows.find((r) => r.eventCrfOid === oid)
  if (row) {
    row.openQueries += 1
    if (row.status === 'pending') row.status = 'query'
  }
  queryOpen.value = false
}

const queryTargetCrfDescription = computed(() =>
  queryTarget.value
    ? `${queryTarget.value.subjectId} · ${queryTarget.value.eventLabel} · ${queryTarget.value.crfName}`
    : '',
)

const statusOptions: { v: 'all' | SdvStatus; l: () => string }[] = [
  { v: 'all',      l: () => t('sdv.status.all') },
  { v: 'pending',  l: () => t('sdv.status.pending') },
  { v: 'query',    l: () => t('sdv.status.query') },
  { v: 'verified', l: () => t('sdv.status.verified') },
  { v: 'locked',   l: () => t('sdv.status.locked') },
]

const requirementOptions: { v: 'all' | SdvRequirement; l: () => string }[] = [
  { v: 'all',                l: () => t('sdv.requirement.all') },
  { v: 'required-100',       l: () => t('sdv.requirement.required-100') },
  { v: 'required-partial',   l: () => t('sdv.requirement.required-partial') },
  { v: 'not-required',       l: () => t('sdv.requirement.not-required') },
]
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/sdv" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
          <polyline points="22 4 12 14.01 9 11.01" />
        </svg>
        {{ t('nav.sdv') }}
      </RouterLink>
      <RouterLink to="/notes" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M4 15a4 4 0 0 0 4 4h12V5a2 2 0 0 0-2-2H8a4 4 0 0 0-4 4z" />
        </svg>
        {{ t('nav.notes') }}
      </RouterLink>
      <RouterLink to="/audit-log" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        </svg>
        {{ t('nav.auditLog') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 px-8 py-6">
      <div class="flex items-end justify-between mb-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('sdv.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('sdv.title') }}</h1>
        </div>
        <div class="flex items-center gap-2 text-xs">
          <button class="px-3 py-1.5 border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-1.5">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" x2="12" y1="15" y2="3" />
            </svg>
            {{ t('common.export') }}
          </button>
        </div>
      </div>

      <!-- Filter row -->
      <div class="flex items-center gap-3 mb-4 text-xs">
        <div class="w-72">
          <TextInput
            id="sdv-search"
            v-model="sdv.query"
            type="search"
            :placeholder="t('sdv.searchPlaceholder')"
            inputmode="search"
          >
            <template #prefix-icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.3-4.3" />
              </svg>
            </template>
          </TextInput>
        </div>

        <div class="w-44">
          <SelectInput id="sdv-status-filter" :model-value="sdv.statusFilter" @update:model-value="(v) => sdv.statusFilter = v as 'all' | SdvStatus">
            <option v-for="o in statusOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
          </SelectInput>
        </div>

        <div class="w-52">
          <SelectInput id="sdv-req-filter" :model-value="sdv.requirementFilter" @update:model-value="(v) => sdv.requirementFilter = v as 'all' | SdvRequirement">
            <option v-for="o in requirementOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
          </SelectInput>
        </div>

        <label class="inline-flex items-center gap-1.5 text-slate-600 cursor-pointer">
          <input v-model="sdv.onlyWithQueries" type="checkbox" class="rounded text-muw-blue" />
          {{ t('sdv.filter.onlyWithQueries') }}
        </label>

        <button
          v-if="sdv.query || sdv.statusFilter !== 'all' || sdv.requirementFilter !== 'all' || sdv.onlyWithQueries"
          type="button"
          class="text-slate-500 hover:text-slate-900"
          @click="sdv.clearFilters()"
        >
          {{ t('common.clear') }}
        </button>

        <div class="ml-auto text-slate-500">
          {{ t('sdv.showingCount', { visible: sdv.visibleCount, total: sdv.totalCount, verifiable: sdv.verifiableCount }) }}
        </div>
      </div>

      <!-- Bulk-action bar -->
      <div
        v-if="sdv.selectedCount > 0"
        class="flex items-center justify-between bg-muw-blue-50 border border-muw-blue-200 rounded-muw px-3 py-2 mb-3 text-xs"
        role="region"
        :aria-label="t('sdv.selectionAriaLabel')"
      >
        <div class="flex items-center gap-2 text-muw-blue-900">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          {{ t('sdv.selectedCount', { count: sdv.selectedCount }) }}
        </div>
        <div class="flex items-center gap-2">
          <button class="px-3 py-1.5 text-xs border border-muw-blue-200 rounded-md bg-white hover:bg-slate-50 text-slate-700" @click="sdv.clearSelection()">
            {{ t('sdv.action.cancelSelection') }}
          </button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5 font-medium"
            :disabled="sdv.isVerifying"
            @click="openConfirm"
          >
            {{ t('sdv.action.markAsVerified', { count: sdv.selectedCount }) }}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </button>
        </div>
      </div>

      <!-- "Just verified" toast row -->
      <div
        v-if="justVerifiedCount !== null"
        class="flex items-center gap-2 bg-muw-teal-50 border border-muw-teal-200 rounded-muw px-3 py-2 mb-3 text-xs text-muw-teal-700"
        role="status"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <polyline points="20 6 9 17 4 12" />
        </svg>
        {{ t('sdv.toast.verifiedCount', { count: justVerifiedCount }) }}
      </div>

      <DenseTable>
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 w-8">
              <input
                type="checkbox"
                class="rounded text-muw-blue"
                :checked="sdv.allVerifiableSelected"
                :disabled="sdv.verifiableCount === 0"
                :aria-label="t('sdv.selectAllAriaLabel')"
                @change="sdv.toggleAllInView()"
              />
            </th>
            <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('sdv.column.subject') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-24">{{ t('sdv.column.site') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('sdv.column.event') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-24">{{ t('sdv.column.eventDate') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('sdv.column.crf') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('sdv.column.requirement') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('sdv.column.status') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right w-32"></th>
          </tr>
        </template>

        <tr v-if="sdv.isLoading">
          <td :colspan="9" class="px-3 py-6 text-center text-slate-500 italic">{{ t('common.loading') }}</td>
        </tr>
        <tr v-else-if="sdv.visibleCount === 0">
          <td :colspan="9" class="px-3 py-6 text-center text-slate-500">{{ t('sdv.empty') }}</td>
        </tr>

        <tr v-for="row in sdv.filtered" :key="row.eventCrfOid">
          <td class="px-3 py-2">
            <input
              type="checkbox"
              class="rounded text-muw-blue"
              :checked="sdv.selected.has(row.eventCrfOid)"
              :disabled="row.status !== 'pending'"
              :aria-label="t('sdv.selectRowAriaLabel', { subject: row.subjectId, crf: row.crfName })"
              @change="sdv.toggle(row.eventCrfOid)"
            />
          </td>
          <td class="px-3 py-2 font-medium">{{ row.subjectId }}</td>
          <td class="px-3 py-2 text-slate-600">{{ row.siteLabel }}</td>
          <td class="px-3 py-2 text-slate-700">{{ row.eventLabel }}</td>
          <td class="px-3 py-2 text-slate-600 font-mono text-xs">{{ formatDate(row.eventStartDate) }}</td>
          <td class="px-3 py-2 text-slate-700">{{ row.crfName }}<span class="text-slate-400 ml-1">· {{ row.crfLanguage }}</span></td>
          <td class="px-3 py-2 text-slate-600">{{ requirementLabel(row.requirement) }}</td>
          <td class="px-3 py-2">
            <div class="flex items-center gap-1.5">
              <StatusPill :variant="statusVariant(row.status)">{{ statusLabel(row.status) }}</StatusPill>
              <span v-if="row.openQueries > 0" class="text-[10px] font-semibold text-rose-700 bg-rose-50 border border-rose-200 rounded-full px-1.5" :title="t('sdv.openQueriesTooltip', { count: row.openQueries })">
                {{ row.openQueries }}
              </span>
            </div>
          </td>
          <td class="px-3 py-2 text-right">
            <button
              class="text-muw-blue hover:underline text-xs mr-3"
              @click="openAddQuery(row)"
            >
              {{ t('sdv.action.addQuery') }}
            </button>
            <RouterLink :to="`/event-crfs/${row.eventCrfOid}`" class="text-muw-blue hover:underline text-xs">
              {{ t('sdv.action.openCrf') }}
            </RouterLink>
          </td>
        </tr>

        <template #statusBar>
          <span>{{ t('sdv.showingCount', { visible: sdv.visibleCount, total: sdv.totalCount, verifiable: sdv.verifiableCount }) }}</span>
        </template>
      </DenseTable>
    </main>

    <!-- Bulk-verify confirmation modal -->
    <Modal v-model:open="confirmOpen" labelled-by="sdv-confirm-title" panel-class="max-w-md">
      <template #header>
        <h2 id="sdv-confirm-title" class="text-lg font-semibold tracking-tight">{{ t('sdv.confirm.title') }}</h2>
      </template>

      <p class="text-sm text-slate-700">
        {{ t('sdv.confirm.body', { count: sdv.selectedCount }) }}
      </p>
      <p class="text-xs text-slate-500 mt-2 leading-relaxed">
        {{ t('sdv.confirm.note') }}
      </p>

      <template #footer>
        <div class="text-xs text-slate-500">
          {{ t('sdv.confirm.auditNote') }}
        </div>
        <div class="flex items-center gap-2">
          <button class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700" @click="confirmOpen = false">
            {{ t('common.cancel') }}
          </button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
            @click="confirmVerify"
          >
            {{ t('sdv.confirm.cta', { count: sdv.selectedCount }) }}
          </button>
        </div>
      </template>
    </Modal>

    <!-- Add Query modal -->
    <Modal v-model:open="queryOpen" labelled-by="sdv-query-title" panel-class="max-w-2xl">
      <template #header>
        <div>
          <h2 id="sdv-query-title" class="text-lg font-semibold tracking-tight">{{ t('sdv.query.title') }}</h2>
          <p class="text-xs text-slate-500 mt-0.5">{{ queryTargetCrfDescription }}</p>
        </div>
      </template>

      <div class="space-y-4">
        <div>
          <FieldLabel for="sdv-query-type" required>{{ t('sdv.query.noteType') }}</FieldLabel>
          <div class="grid grid-cols-4 gap-2 text-xs">
            <label
              v-for="opt in (['query','failed-validation','annotation','reason-for-change'] as NoteType[])"
              :key="opt"
              class="flex items-center justify-center gap-1.5 px-3 py-2 rounded-md border cursor-pointer font-medium transition-colors"
              :class="queryType === opt
                ? 'border-muw-blue-200 bg-muw-blue-50 text-muw-blue'
                : 'border-slate-200 hover:bg-slate-50 text-slate-700'"
            >
              <input type="radio" name="sdv-query-type" :value="opt" :checked="queryType === opt" class="sr-only" @change="queryType = opt" />
              <span>{{ t(`sdv.query.type.${opt}`) }}</span>
            </label>
          </div>
        </div>

        <div>
          <FieldLabel for="sdv-query-desc" required>{{ t('sdv.query.descriptionLabel') }}</FieldLabel>
          <textarea
            id="sdv-query-desc"
            v-model="queryDescription"
            class="w-full px-3 py-2 border border-slate-300 rounded-md text-sm focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 focus:outline-none muw-focus"
            rows="4"
            :placeholder="t('sdv.query.descriptionPlaceholder')"
          ></textarea>
        </div>
      </div>

      <template #footer>
        <div class="text-xs text-slate-500">
          {{ t('sdv.query.auditTrailTell') }}
        </div>
        <div class="flex items-center gap-2">
          <button class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700" @click="queryOpen = false">
            {{ t('common.cancel') }}
          </button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
            :disabled="queryDescription.trim().length === 0"
            @click="submitQuery"
          >
            {{ t('sdv.query.submit') }}
          </button>
        </div>
      </template>
    </Modal>
  </div>
</template>

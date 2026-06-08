<script setup lang="ts">
/**
 * Phase E.6 — Patientenübersicht (Patient Overview).
 *
 * Cross-study landing keyed on the underlying patient (one human, many
 * enrolments) rather than the active study's study-subject labels. The
 * list pulls from `/pages/api/v1/patients` with server-side search +
 * pagination; clicking a row opens the {@link PatientDetailModal} which
 * loads the eye timeline + measurement series in-component.
 *
 * The view itself stays slim — page-size 50, debounced search box,
 * pagination footer. All cross-study logic lives in the store + modal.
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import PatientDetailModal from '@/components/PatientDetailModal.vue'

import { usePatientsOverviewStore } from '@/stores/patientsOverview'
import type { PatientListItem } from '@/types/patient'

const { t } = useI18n()
const store = usePatientsOverviewStore()

const searchInput = ref('')
const selectedSubjectId = ref<number | null>(null)
const modalOpen = ref(false)

let debounceHandle: ReturnType<typeof setTimeout> | null = null

watch(searchInput, (next) => {
  if (debounceHandle) clearTimeout(debounceHandle)
  debounceHandle = setTimeout(() => {
    void store.loadList(0, store.pageSize, next.trim())
  }, 300)
})

onMounted(() => {
  void store.loadList(store.page, store.pageSize, store.search)
})

onBeforeUnmount(() => {
  if (debounceHandle) clearTimeout(debounceHandle)
})

function openDetail(subjectId: number): void {
  selectedSubjectId.value = subjectId
  modalOpen.value = true
  void store.loadDetail(subjectId)
}

function onModalClose(): void {
  modalOpen.value = false
}

const rangeStart = computed(() => {
  if (store.totalCount === 0) return 0
  return store.page * store.pageSize + 1
})
const rangeEnd = computed(() => {
  const end = (store.page + 1) * store.pageSize
  return Math.min(end, store.totalCount)
})
const totalPages = computed(() => Math.max(1, Math.ceil(store.totalCount / store.pageSize)))
const canPrev = computed(() => store.page > 0)
const canNext = computed(() => store.page + 1 < totalPages.value)

function goPrev(): void {
  if (!canPrev.value) return
  void store.loadList(store.page - 1, store.pageSize, store.search)
}

function goNext(): void {
  if (!canNext.value) return
  void store.loadList(store.page + 1, store.pageSize, store.search)
}

/** ISO date → dd.MM.yyyy for the German UI. */
function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const dd = String(d.getUTCDate()).padStart(2, '0')
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0')
  const yyyy = d.getUTCFullYear()
  return `${dd}.${mm}.${yyyy}`
}

function pillVariant(eye: string | null): 'investigator' | 'monitor' | 'info' {
  if (eye === 'OU') return 'info'
  if (eye === 'OD') return 'investigator'
  return 'monitor'
}

function rowAriaLabel(p: PatientListItem): string {
  return t('patients.list.rowAria', {
    id: p.subjectId,
    enrolments: p.enrolments.length,
  })
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink
        to="/"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
          <polyline points="9 22 9 12 15 12 15 22" />
        </svg>
        {{ t('nav.home') }}
      </RouterLink>
      <RouterLink
        to="/patients"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium"
        aria-current="page"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
          <circle cx="9" cy="7" r="4" />
          <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
          <path d="M16 3.13a4 4 0 0 1 0 7.75" />
        </svg>
        {{ t('nav.patientsOverview') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 px-8 py-6 max-w-[1200px]">
      <div class="flex items-end justify-between mb-5">
        <div>
          <div class="text-xs text-slate-500 mb-1">
            {{ t('patients.list.subtitle') }}
          </div>
          <h1 class="text-xl font-semibold tracking-tight">
            {{ t('patients.list.title') }}
          </h1>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-x-3 gap-y-2 mb-4 text-xs">
        <div class="w-80">
          <TextInput
            id="patients-overview-search"
            v-model="searchInput"
            type="search"
            inputmode="search"
            :placeholder="t('patients.list.searchPlaceholder')"
          >
            <template #prefix-icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.3-4.3" />
              </svg>
            </template>
          </TextInput>
        </div>
        <div class="ml-auto text-slate-500 whitespace-nowrap">
          {{ t('patients.list.rangeLabel', { start: rangeStart, end: rangeEnd, total: store.totalCount }) }}
        </div>
      </div>

      <DenseTable :sticky-header-offset="56">
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium w-24">{{ t('patients.list.columns.yearOfBirth') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('patients.list.columns.gender') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('patients.list.columns.studies') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('patients.list.columns.lastVisit') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-20 text-right">{{ t('patients.list.columns.actions') }}</th>
          </tr>
        </template>

        <tr v-if="store.isLoadingList">
          <td colspan="5" class="px-3 py-6 text-center text-slate-500 italic">
            {{ t('common.loading') }}
          </td>
        </tr>

        <tr v-else-if="store.listError">
          <td colspan="5" class="px-3 py-6 text-center text-rose-700">
            {{ store.listError }}
          </td>
        </tr>

        <tr v-else-if="store.list.length === 0">
          <td colspan="5" class="px-3 py-6 text-center text-slate-500">
            {{ t('patients.list.empty') }}
          </td>
        </tr>

        <tr
          v-for="p in store.list"
          v-else
          :key="p.subjectId"
          :data-testid="`patient-row-${p.subjectId}`"
          :aria-label="rowAriaLabel(p)"
          class="cursor-pointer"
          @click="openDetail(p.subjectId)"
        >
          <td class="px-3 py-2 text-slate-700 font-mono text-xs">{{ p.yearOfBirth ?? '—' }}</td>
          <td class="px-3 py-2 text-slate-600">{{ p.gender }}</td>
          <td class="px-3 py-2">
            <div class="flex flex-wrap gap-1.5">
              <StatusPill
                v-for="(e, idx) in p.enrolments"
                :key="`${e.studyOid}-${e.label}-${idx}`"
                :variant="pillVariant(e.studyEye)"
                compact
              >
                {{ e.studyName }}<span v-if="e.studyEye" class="ml-1 font-mono">{{ e.studyEye }}</span>
              </StatusPill>
              <span v-if="p.enrolments.length === 0" class="text-slate-400 text-xs">—</span>
            </div>
          </td>
          <td class="px-3 py-2 text-slate-600 font-mono text-xs">
            {{ formatDate(p.enrolments.map((e) => e.lastVisitAt).filter((v): v is string => !!v).sort().reverse()[0] ?? null) }}
          </td>
          <td class="px-3 py-2 text-right">
            <button
              type="button"
              class="text-muw-blue text-xs hover:underline"
              :data-testid="`open-${p.subjectId}`"
              @click.stop="openDetail(p.subjectId)"
            >
              {{ t('patients.list.open') }}
            </button>
          </td>
        </tr>

        <template #statusBar>
          <div class="flex items-center gap-3">
            <span>{{ t('patients.list.rangeLabel', { start: rangeStart, end: rangeEnd, total: store.totalCount }) }}</span>
          </div>
          <div class="flex items-center gap-2">
            <button
              type="button"
              data-testid="page-prev"
              class="px-2 py-1 rounded border border-slate-200 bg-white text-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
              :disabled="!canPrev"
              @click="goPrev"
            >
              {{ t('patients.list.prev') }}
            </button>
            <span class="text-slate-500 text-[11px]">
              {{ t('patients.list.pageOfTotal', { page: store.page + 1, total: totalPages }) }}
            </span>
            <button
              type="button"
              data-testid="page-next"
              class="px-2 py-1 rounded border border-slate-200 bg-white text-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
              :disabled="!canNext"
              @click="goNext"
            >
              {{ t('patients.list.next') }}
            </button>
          </div>
        </template>
      </DenseTable>
    </main>

    <PatientDetailModal
      :open="modalOpen"
      :subject-id="selectedSubjectId"
      @close="onModalClose"
      @update:open="(v) => (modalOpen = v)"
    />
  </div>
</template>

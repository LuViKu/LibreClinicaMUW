<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'

import { useSubjectsStore } from '@/stores/subjects'
import type { EventStatus, Subject } from '@/types/subject'

const { t } = useI18n()
const subjects = useSubjectsStore()
const route = useRoute()

/**
 * Phase E.6 — HomeView's "Schedule visit" card routes here with
 * `?action=schedule`. v1 surfaces a banner "Pick a subject" — the
 * actual scheduler lives on the subject-detail view. Operator
 * feedback may upgrade this to an inline dialog launched from the
 * matrix row, but the banner keeps the scope honest.
 */
const isScheduleMode = computed(() => route.query.action === 'schedule')

onMounted(() => {
  if (subjects.rows.length === 0) {
    subjects.load('TDS0004')
  }
})

/**
 * Compute the table's column header set from the first row's events.
 * The store guarantees every row has the same event labels in the same
 * order; falling back to the empty array keeps the view from crashing
 * during the first render before mock data hydrates.
 */
const eventColumns = computed(() =>
  subjects.rows[0]?.events.map((e) => ({ oid: e.eventDefinitionOid, label: e.label })) ?? [],
)

const statusVariant = (status: EventStatus): 'success' | 'info' | 'warning' | 'neutral' => {
  switch (status) {
    case 'signed':
    case 'locked':
    case 'complete':
      return 'success'
    case 'scheduled':
    case 'in-progress':
      return 'info'
    case 'not-scheduled':
    default:
      return 'neutral'
  }
}

const statusLabel = (status: EventStatus): string => t(`subjectMatrix.status.${status}`)

/** ISO `YYYY-MM-DD` → `dd-MMM-yyyy` (clinical convention). */
const MONTH_ABBR = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
function formatDate(iso: string): string {
  const [y, m, d] = iso.split('-').map((s) => Number.parseInt(s, 10))
  const mi = (m ?? 1) - 1
  return `${String(d ?? 1).padStart(2, '0')}-${MONTH_ABBR[mi] ?? '???'}-${y}`
}

type Filter = 'all' | 'open-events' | 'all-events-complete' | 'signed'
const filters: { id: Filter; label: () => string }[] = [
  { id: 'all',                 label: () => t('subjectMatrix.filter.all') },
  { id: 'open-events',         label: () => t('subjectMatrix.filter.openEvents') },
  { id: 'all-events-complete', label: () => t('subjectMatrix.filter.allComplete') },
  { id: 'signed',              label: () => t('subjectMatrix.filter.signed') },
]

const ariaSortLabel = (subject: Subject) =>
  subject.signed ? t('subjectMatrix.ariaSigned', { id: subject.id }) : t('subjectMatrix.ariaUnsigned', { id: subject.id })
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
        to="/subjects"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium"
        aria-current="page"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <rect width="18" height="18" x="3" y="3" rx="2" />
          <path d="M3 9h18M9 21V9" />
        </svg>
        {{ t('nav.subjectMatrix') }}
      </RouterLink>

      <template #footer>
        <dl class="space-y-1.5 text-[11px]">
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('subjectMatrix.studyCard.pi') }}</dt><dd class="text-slate-700">Max von Pettenkofer</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('subjectMatrix.studyCard.start') }}</dt><dd class="text-slate-700">01-Jul-2020</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('subjectMatrix.studyCard.subjects') }}</dt><dd class="text-slate-700">{{ subjects.totalCount }} {{ t('subjectMatrix.studyCard.enrolled') }}</dd></div>
          <div class="flex justify-between"><dt class="text-slate-500">{{ t('subjectMatrix.studyCard.status') }}</dt><dd><StatusPill variant="success">{{ t('subjectMatrix.studyCard.active') }}</StatusPill></dd></div>
        </dl>
      </template>
    </SideRail>

    <main class="flex-1 px-8 py-6 max-w-[1200px]">
      <!-- Phase E.6 — schedule-visit hint from HomeView's
           "Schedule visit" card. The actual dialog lives on
           SubjectDetailView; this banner tells the operator to drill
           in. v1 keeps it deliberately simple — operator feedback can
           drive an inline-launch flow later. -->
      <div
        v-if="isScheduleMode"
        class="mb-4 rounded-md border border-muw-blue-200 bg-muw-blue-50 px-4 py-3 text-xs text-muw-blue-900 flex items-start gap-2.5"
        role="status"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <path d="M12 16v-4M12 8h.01" />
        </svg>
        <p class="leading-relaxed">{{ t('subjectMatrix.scheduleHint') }}</p>
      </div>

      <div class="flex items-end justify-between mb-5">
        <div>
          <div class="text-xs text-slate-500 mb-1">
            München (TDS0004) · {{ subjects.totalCount }} {{ t('subjectMatrix.subjectsCountTrail') }}
          </div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('subjectMatrix.title') }}</h1>
        </div>
        <div class="flex items-center gap-2">
          <button class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-1.5">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" x2="12" y1="15" y2="3" />
            </svg>
            {{ t('common.export') }}
          </button>
          <RouterLink
            to="/subjects/new"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
              <line x1="12" x2="12" y1="5" y2="19" />
              <line x1="5" x2="19" y1="12" y2="12" />
            </svg>
            {{ t('subjectMatrix.addSubject') }}
          </RouterLink>
        </div>
      </div>

      <!-- Filter row -->
      <div class="flex flex-wrap items-center gap-x-3 gap-y-2 mb-4 text-xs">
        <div class="w-72">
          <TextInput
            id="subject-matrix-search"
            v-model="subjects.query"
            type="search"
            inputmode="search"
            :placeholder="t('subjectMatrix.searchPlaceholder')"
          >
            <template #prefix-icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.3-4.3" />
              </svg>
            </template>
          </TextInput>
        </div>

        <div class="flex items-center gap-1 shrink-0">
          <button
            v-for="f in filters"
            :key="f.id"
            type="button"
            class="px-2.5 py-1 rounded-full border text-xs font-medium transition-colors whitespace-nowrap shrink-0"
            :class="
              subjects.statusFilter === f.id
                ? 'border-muw-blue-200 bg-muw-blue-50 text-muw-blue-700'
                : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            "
            @click="subjects.statusFilter = f.id"
          >
            {{ f.label() }}
          </button>
        </div>

        <label class="inline-flex items-center gap-1.5 text-slate-600 cursor-pointer whitespace-nowrap shrink-0">
          <input v-model="subjects.onlyWithQueries" type="checkbox" class="rounded text-muw-blue" />
          {{ t('subjectMatrix.filter.onlyWithQueries') }}
        </label>

        <button
          v-if="subjects.query || subjects.statusFilter !== 'all' || subjects.onlyWithQueries"
          type="button"
          class="ml-2 text-slate-500 hover:text-slate-900 whitespace-nowrap shrink-0"
          @click="subjects.clearFilters()"
        >
          {{ t('common.clear') }}
        </button>

        <div class="ml-auto text-slate-500 whitespace-nowrap shrink-0">
          {{ t('subjectMatrix.showingCount', { visible: subjects.visibleCount, total: subjects.totalCount }) }}
        </div>
      </div>

      <DenseTable :sticky-header-offset="56">
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium w-24">{{ t('subjectMatrix.column.subject') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('subjectMatrix.column.gender') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-16">{{ t('ophth.studyEye.column') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('subjectMatrix.column.group') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-24">{{ t('subjectMatrix.column.enrolled') }}</th>
            <th
              v-for="col in eventColumns"
              :key="col.oid"
              scope="col"
              class="px-3 py-2 font-medium"
            >
              {{ col.label }}
            </th>
            <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('subjectMatrix.column.signed') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right w-20"></th>
          </tr>
        </template>

        <tr v-if="subjects.isLoading">
          <td :colspan="6 + eventColumns.length" class="px-3 py-6 text-center text-slate-500 italic">
            {{ t('common.loading') }}
          </td>
        </tr>

        <tr v-else-if="subjects.error">
          <td :colspan="6 + eventColumns.length" class="px-3 py-6 text-center text-rose-700">
            {{ subjects.error }}
          </td>
        </tr>

        <tr v-else-if="subjects.visibleCount === 0">
          <td :colspan="6 + eventColumns.length" class="px-3 py-6 text-center text-slate-500">
            {{ t('subjectMatrix.empty') }}
          </td>
        </tr>

        <tr
          v-for="subject in subjects.filtered"
          :key="subject.id"
          :aria-label="ariaSortLabel(subject)"
        >
          <td class="px-3 py-2 font-medium">
            <RouterLink :to="`/subjects/${subject.id}`" class="text-muw-blue hover:underline">
              {{ subject.id }}
            </RouterLink>
            <span v-if="subject.secondaryId" class="ml-1.5 text-slate-400 text-[11px] font-normal">
              · {{ subject.secondaryId }}
            </span>
          </td>
          <td class="px-3 py-2 text-slate-600">{{ subject.gender }}</td>
          <td class="px-3 py-2 text-slate-600 font-mono text-[11px]">
            <span v-if="subject.studyEye" class="px-1.5 py-0.5 rounded bg-muw-blue-50 text-muw-blue border border-muw-blue-100">
              {{ subject.studyEye }}
            </span>
            <span v-else class="text-slate-400">—</span>
          </td>
          <td class="px-3 py-2 text-slate-600">{{ subject.groupLabel ?? '—' }}</td>
          <td class="px-3 py-2 text-slate-600 font-mono text-xs">{{ formatDate(subject.enrolledOn) }}</td>

          <td v-for="cell in subject.events" :key="cell.eventDefinitionOid" class="px-3 py-2">
            <div class="flex items-center gap-1.5">
              <StatusPill :variant="statusVariant(cell.status)">{{ statusLabel(cell.status) }}</StatusPill>
              <span
                v-if="cell.openQueries > 0"
                class="text-[10px] font-semibold text-rose-700 bg-rose-50 border border-rose-200 rounded-full px-1.5"
                :title="t('subjectMatrix.openQueriesTooltip', { count: cell.openQueries })"
              >
                {{ cell.openQueries }}
              </span>
            </div>
          </td>

          <td class="px-3 py-2">
            <StatusPill v-if="subject.signed" variant="success">{{ t('subjectMatrix.signed') }}</StatusPill>
            <span v-else class="text-slate-400">—</span>
          </td>

          <td class="px-3 py-2 text-right">
            <RouterLink :to="`/subjects/${subject.id}`" class="text-muw-blue text-xs hover:underline">
              {{ t('subjectMatrix.openSubject') }}
            </RouterLink>
          </td>
        </tr>

        <template #statusBar>
          <span>{{ t('subjectMatrix.showingCount', { visible: subjects.visibleCount, total: subjects.totalCount }) }}</span>
          <span>{{ t('subjectMatrix.rowsPerPage') }}: 25</span>
        </template>
      </DenseTable>
    </main>
  </div>
</template>

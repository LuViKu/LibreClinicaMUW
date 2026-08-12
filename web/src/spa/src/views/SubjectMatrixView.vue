<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'

import { useSubjectsStore } from '@/stores/subjects'
import { useStudyStore } from '@/stores/study'
import { useAuthStore } from '@/stores/auth'
import StudyMetricsModal from '@/components/StudyMetricsModal.vue'
import type { EventStatus, Subject } from '@/types/subject'
import { formatDate } from '@/lib/dateFormat'
import { useViewBreadcrumb } from '@/composables/useViewBreadcrumb'

const { t } = useI18n()
const subjects = useSubjectsStore()
const study = useStudyStore()
const auth = useAuthStore()
const route = useRoute()

// 2026-06-23 user-feedback round — nested breadcrumb trail.
// "<study> > Studienteilnehmer". The Subject Matrix is the leaf
// of its own flow so the inner-trail is a single non-link crumb;
// App.vue prepends the active study.
useViewBreadcrumb(computed(() => [{ label: t('nav.subjectMatrix'), to: null }]))

/**
 * Phase E.6 — Pick-a-subject banner that previously launched on the
 * "Visite planen" CTA in this header. The CTA was retired 2026-06-09
 * per operator feedback (visit scheduling now starts from the per-
 * subject detail view), but inbound links / bookmarks carrying
 * `?action=schedule` keep the banner so the legacy URL still
 * communicates intent.
 */
const isScheduleMode = ref(route.query.action === 'schedule')

onMounted(() => {
  // 2026-07-06 — always re-load on mount. The prior `rows.length === 0`
  // gate skipped the fetch whenever the store already held rows,
  // which broke the Add-Subject → Matrix flow: `subjects.add()`
  // optimistically prepends the new row (rows.length becomes 1
  // even if `load()` had never run), so the subsequent matrix
  // mount saw a non-zero count and served only that one row,
  // hiding every previously-enrolled subject. The endpoint is cheap
  // (< 100 ms for the demo studies), correctness beats the cache.
  subjects.load()
  // Phase E.6 — fetch the active study identity so the footer card
  // (PI, planned start, status) renders real data instead of the
  // Phase-E.4 mock placeholders ("Max von Pettenkofer", "01-Jul-2020").
  const oid = auth.user?.activeStudy?.oid
  if (oid) void study.loadIdentity(oid)
})

/**
 * SubjectMatrix footer card bindings — drive PI / planned-start /
 * status off the cached study identity. Each falls back to an
 * em-dash when the field is empty or the identity hasn't loaded
 * yet, so a fresh navigation never flashes a stale value from the
 * previous study.
 */
const studyPi = computed(() => {
  const v = study.identity?.principalInvestigator?.trim()
  return v && v.length > 0 ? v : '—'
})
const studyStart = computed(() => {
  const iso = study.identity?.datePlannedStart ?? null
  return iso ? formatDate(iso) : '—'
})
const studyStatusLabel = computed(() => {
  const raw = study.identity?.status?.trim() ?? ''
  if (raw === '') return '—'
  // Backend ships the StudyStatus enum's resource-bundle label
  // ("Available" / "Pending" / "Frozen" / "Locked"); normalise to
  // operator-visible terms in the local SPA palette.
  return raw
})
const studyStatusActive = computed(
  () => (study.identity?.status ?? '').toLowerCase().includes('available'),
)

/**
 * Display label for the matrix's "study · N subjects" trail row.
 * When the operator is bound to a top-level study, surfaces just
 * the study's name; when bound to a site, prefixes with the parent
 * study's identity. Replaces the Phase-E.4 hardcoded "München
 * (TDS0004)" placeholder.
 */
const studyContextLabel = computed(() => {
  const active = auth.user?.activeStudy
  if (!active?.name) return ''
  if (active.isSite && study.identity?.parentStudyName) {
    return `${study.identity.parentStudyName} · ${active.name}`
  }
  return active.name
})

/**
 * Compute the table's column header set from the first row's events.
 * The store guarantees every row has the same event labels in the same
 * order; falling back to the empty array keeps the view from crashing
 * during the first render before mock data hydrates.
 *
 * <p>2026-06-23 — Studies whose event-definitions all share the same
 * label (e.g. RIS's repeating "Retinal Imaging Visit" × 7) blew up
 * the table width with a duplicated 22-char column header, pushing
 * the trailing "Öffnen" action off-screen. When >1 columns share a
 * label we collapse to compact ordinal shorthand ("V1" … "VN")
 * with the full label preserved in the `title` attribute (hover
 * tooltip). Studies with distinct per-visit labels (typical
 * interventional protocols) keep their original headers.
 */
const eventColumns = computed(() => {
  const raw = subjects.rows[0]?.events.map((e) => ({
    oid: e.eventDefinitionOid,
    label: e.label,
  })) ?? []
  const uniqueLabels = new Set(raw.map((c) => c.label))
  if (raw.length > 1 && uniqueLabels.size === 1) {
    return raw.map((c, i) => ({
      oid: c.oid,
      label: `V${i + 1}`,
      title: c.label,
    }))
  }
  return raw.map((c) => ({ ...c, title: c.label }))
})

/* Studien-Statistik modal — opens on the SideRail link. */
const metricsModalOpen = ref(false)

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

/**
 * Client-side CSV export of the *currently filtered* matrix — the columns the
 * table shows (subject, gender, study-eye, group, enrolment, one column per
 * visit with its status, aggregate signed). No backend round-trip; honours the
 * active filter/search. (Per-subject ODM/CSV/PDF snapshots remain the row-level
 * SubjectExportButton.) Previously this button had no handler at all.
 */
function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v)
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}
function exportCsv(): void {
  const rows = subjects.filtered
  if (rows.length === 0) return
  const evCols = eventColumns.value
  const header = [
    t('subjectMatrix.column.subject'),
    t('subjectMatrix.column.gender'),
    t('ophth.studyEye.column'),
    t('subjectMatrix.column.group'),
    t('subjectMatrix.column.enrolled'),
    ...evCols.map((c) => c.title || c.label),
    t('subjectMatrix.column.signed'),
  ]
  const lines = [header.map(csvCell).join(',')]
  for (const s of rows) {
    lines.push([
      s.id,
      s.gender ?? '',
      s.studyEye ?? '',
      s.groupLabel ?? '',
      s.enrolledOn ?? '',
      ...evCols.map((_c, i) => (s.events[i] ? statusLabel(s.events[i]!.status) : '')),
      s.signed ? t('subjectMatrix.signed') : '',
    ].map(csvCell).join(','))
  }
  // Prepend a UTF-8 BOM so Excel reads the umlauts correctly.
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `subjects-${auth.user?.activeStudy?.oid ?? 'study'}-${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

type Filter =
  | 'all'
  | 'open-events'
  | 'all-events-complete'
  | 'signed'
  | 'today'
  | 'ready-to-sign'
const filters: { id: Filter; label: () => string }[] = [
  { id: 'all',                 label: () => t('subjectMatrix.filter.all') },
  // 2026-06-11 — HomeView's "Today's open CRFs" + "Ready to sign"
  // operator cards deep-link to ?filter=today / ?filter=ready-to-sign.
  // Keep these two chips adjacent to 'all' so the operator-facing
  // filters cluster together; the data-shape filters
  // (open-events / all-events-complete / signed) follow.
  { id: 'today',               label: () => t('subjectMatrix.filter.today') },
  { id: 'ready-to-sign',       label: () => t('subjectMatrix.filter.readyToSign') },
  { id: 'open-events',         label: () => t('subjectMatrix.filter.openEvents') },
  { id: 'all-events-complete', label: () => t('subjectMatrix.filter.allComplete') },
  { id: 'signed',              label: () => t('subjectMatrix.filter.signed') },
]

/**
 * Adopt `?filter=<id>` from the inbound URL once on mount. HomeView's
 * "Today's open CRFs" + "Ready to sign" + (future) other operator
 * cards deep-link here with the filter pre-set; we sync it into the
 * store's persistent statusFilter so the chip UI highlights and
 * `subjects.filtered` re-computes. Unknown values are ignored —
 * bookmarks predating a chip rename shouldn't crash the view.
 */
const ALLOWED_FILTERS: ReadonlyArray<Filter> = [
  'all', 'open-events', 'all-events-complete', 'signed', 'today', 'ready-to-sign',
]
function adoptFilterFromQuery() {
  const raw = route.query.filter
  if (typeof raw !== 'string') return
  if ((ALLOWED_FILTERS as ReadonlyArray<string>).includes(raw)) {
    subjects.statusFilter = raw as Filter
  }
}
adoptFilterFromQuery()

const ariaSortLabel = (subject: Subject) =>
  subject.signed ? t('subjectMatrix.ariaSigned', { id: subject.id }) : t('subjectMatrix.ariaUnsigned', { id: subject.id })

/**
 * 2026-06-23 user-feedback round — Panel D of subject-matrix-visits.html.
 *
 * <p>The matrix table grew wider than the viewport whenever a study
 * accrued enough visits (RIS at 7 + the GA cohort + observational
 * arms), pushing the page into horizontal scroll and burying the
 * "Öffnen" action off the right edge. Panel D's answer: freeze the
 * Subject column on the left and the Öffnen column on the right while
 * the visit columns scroll horizontally INSIDE the table. The page
 * itself never overflows; the operator uses the chevron buttons (or
 * scrolls the table directly) to slide through the visit timeline.
 *
 * <p>On first paint we scroll to the right edge so the most recent
 * visit is in view — matches the design's "Jump to latest" default.
 */
const tableScrollerEl = ref<HTMLDivElement | null>(null)
function scrollVisits(direction: -1 | 1): void {
  const el = tableScrollerEl.value
  if (!el) return
  el.scrollBy({ left: direction * 320, behavior: 'smooth' })
}
function scrollToLatest(): void {
  const el = tableScrollerEl.value
  if (!el) return
  el.scrollTo({ left: el.scrollWidth, behavior: 'smooth' })
}
function scrollToOldest(): void {
  const el = tableScrollerEl.value
  if (!el) return
  el.scrollTo({ left: 0, behavior: 'smooth' })
}

// 2026-06-24 user-feedback round — open the matrix scrolled to the
// LEFT so the operator sees Subject / Gender / Eye / Group /
// Enrolled identity columns first. The visitor uses the chevron /
// Jump-to-latest buttons (or two-finger scrolls inside the table)
// to slide forward to the recent visits. Was previously
// `el.scrollLeft = el.scrollWidth` (open on latest) — the user's
// mental model is patient-identity-first, then visits.
watch(eventColumns, async (next, prev) => {
  if (next.length === 0) return
  if (prev && prev.length === next.length) return
  await nextTick()
  const el = tableScrollerEl.value
  if (el) el.scrollLeft = 0
}, { immediate: false })
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
          <div class="flex justify-between gap-3"><dt class="text-slate-500 shrink-0">{{ t('subjectMatrix.studyCard.pi') }}</dt><dd class="text-slate-700 text-right truncate">{{ studyPi }}</dd></div>
          <div class="flex justify-between gap-3"><dt class="text-slate-500 shrink-0">{{ t('subjectMatrix.studyCard.start') }}</dt><dd class="text-slate-700 text-right truncate">{{ studyStart }}</dd></div>
          <div class="flex justify-between gap-3"><dt class="text-slate-500 shrink-0">{{ t('subjectMatrix.studyCard.subjects') }}</dt><dd class="text-slate-700 text-right">{{ subjects.totalCount }} {{ t('subjectMatrix.studyCard.enrolled') }}</dd></div>
          <div class="flex justify-between gap-3"><dt class="text-slate-500 shrink-0">{{ t('subjectMatrix.studyCard.status') }}</dt><dd><StatusPill :variant="studyStatusActive ? 'success' : 'neutral'">{{ studyStatusActive ? t('subjectMatrix.studyCard.active') : studyStatusLabel }}</StatusPill></dd></div>
        </dl>
      </template>

      <template #metrics>
        <button
          type="button"
          class="w-full flex items-center justify-between gap-2 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white text-xs"
          data-testid="open-study-metrics"
          @click="metricsModalOpen = true"
        >
          <span class="inline-flex items-center gap-2">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
              <path d="M3 3v18h18" />
              <rect x="7" y="11" width="3" height="6" rx="1" />
              <rect x="12" y="7" width="3" height="10" rx="1" />
              <rect x="17" y="13" width="3" height="4" rx="1" />
            </svg>
            {{ t('subjectMatrix.metricsLink') }}
          </span>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="m9 18 6-6-6-6" />
          </svg>
        </button>
      </template>
    </SideRail>

    <div class="flex-1 px-8 py-6 min-w-0">
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
            <template v-if="studyContextLabel">{{ studyContextLabel }} · </template>{{ subjects.totalCount }} {{ t('subjectMatrix.subjectsCountTrail') }}
          </div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('subjectMatrix.title') }}</h1>
        </div>
        <div class="flex items-center gap-2">
          <button
            data-testid="subject-matrix-export"
            :disabled="subjects.filtered.length === 0"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
            :title="subjects.filtered.length === 0 ? t('subjectMatrix.empty') : t('common.export')"
            @click="exportCsv"
          >
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

      <!-- 2026-06-23 user-feedback round — Panel D layout. The
           Subject column on the left and the action column on the
           right stay frozen via `position:sticky` while the visit
           columns scroll horizontally inside the table's own
           overflow-x-auto wrapper. The page itself never overflows.
           Chevron + Jump-to-latest controls let the operator slide
           through long visit timelines without finger-scrolling. -->
      <div
        class="rounded-md border border-slate-200 bg-white overflow-hidden"
        data-testid="subject-matrix-panel"
      >
        <div
          v-if="eventColumns.length > 0"
          class="flex items-center justify-between gap-2 px-3 py-2 border-b border-slate-100 bg-slate-50/70 text-[11px] text-slate-500"
        >
          <span data-testid="subject-matrix-visit-count">
            {{ t('subjectMatrix.visitsTrail', { count: eventColumns.length }) }}
          </span>
          <div class="flex items-center gap-1.5">
            <button
              type="button"
              class="w-6 h-6 rounded-md border border-slate-200 bg-white text-slate-500 inline-flex items-center justify-center hover:bg-slate-50"
              :aria-label="t('subjectMatrix.visitNav.first')"
              data-testid="subject-matrix-scroll-oldest"
              @click="scrollToOldest"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                <polyline points="11 6 5 12 11 18" />
                <polyline points="19 6 13 12 19 18" />
              </svg>
            </button>
            <button
              type="button"
              class="w-6 h-6 rounded-md border border-slate-200 bg-white text-slate-500 inline-flex items-center justify-center hover:bg-slate-50"
              :aria-label="t('subjectMatrix.visitNav.prev')"
              data-testid="subject-matrix-scroll-prev"
              @click="scrollVisits(-1)"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                <polyline points="15 18 9 12 15 6" />
              </svg>
            </button>
            <button
              type="button"
              class="w-6 h-6 rounded-md border border-slate-200 bg-white text-slate-500 inline-flex items-center justify-center hover:bg-slate-50"
              :aria-label="t('subjectMatrix.visitNav.next')"
              data-testid="subject-matrix-scroll-next"
              @click="scrollVisits(1)"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </button>
            <button
              type="button"
              class="ml-1 px-2 py-0.5 rounded-md border border-slate-200 bg-white text-[11px] font-medium text-muw-blue inline-flex items-center gap-1 hover:bg-slate-50"
              data-testid="subject-matrix-scroll-latest"
              @click="scrollToLatest"
            >
              {{ t('subjectMatrix.visitNav.jumpLatest') }}
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                <path d="M5 12h14M13 5l7 7-7 7" />
              </svg>
            </button>
          </div>
        </div>

        <div
          ref="tableScrollerEl"
          class="overflow-x-auto"
          data-testid="subject-matrix-scroller"
        >
          <table
            class="text-left border-separate text-sm"
            style="border-spacing: 0; min-width: 100%"
          >
            <thead>
              <tr class="bg-slate-50 text-slate-500 text-[11px] font-medium">
                <th
                  scope="col"
                  class="sticky left-0 z-20 bg-slate-50 px-3 py-2 border-b border-slate-200 whitespace-nowrap"
                  style="box-shadow: 1px 0 0 #e2e8f0"
                >
                  {{ t('subjectMatrix.column.subject') }}
                </th>
                <th scope="col" class="px-3 py-2 border-b border-slate-200 whitespace-nowrap">{{ t('subjectMatrix.column.gender') }}</th>
                <th scope="col" class="px-3 py-2 border-b border-slate-200 whitespace-nowrap">{{ t('ophth.studyEye.column') }}</th>
                <th scope="col" class="px-3 py-2 border-b border-slate-200 whitespace-nowrap">{{ t('subjectMatrix.column.group') }}</th>
                <th scope="col" class="px-3 py-2 border-b border-slate-200 whitespace-nowrap">{{ t('subjectMatrix.column.enrolled') }}</th>
                <th
                  v-for="col in eventColumns"
                  :key="col.oid"
                  scope="col"
                  class="px-3 py-2 border-b border-slate-200 whitespace-nowrap text-center"
                  :title="col.title"
                  style="min-width: 96px"
                >
                  {{ col.label }}
                </th>
                <th scope="col" class="px-3 py-2 border-b border-slate-200 whitespace-nowrap">{{ t('subjectMatrix.column.signed') }}</th>
                <th
                  scope="col"
                  class="sticky right-0 z-20 bg-slate-50 px-3 py-2 border-b border-slate-200 text-right whitespace-nowrap"
                  style="box-shadow: -1px 0 0 #e2e8f0"
                >
                  <span class="sr-only">{{ t('common.actions') }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="subjects.isLoading">
                <td :colspan="7 + eventColumns.length" class="px-3 py-6 text-center text-slate-500 italic">
                  {{ t('common.loading') }}
                </td>
              </tr>
              <tr v-else-if="subjects.error">
                <td :colspan="7 + eventColumns.length" class="px-3 py-6 text-center text-rose-700">
                  {{ subjects.error }}
                </td>
              </tr>
              <tr v-else-if="subjects.visibleCount === 0">
                <td :colspan="7 + eventColumns.length" class="px-3 py-6 text-center text-slate-500">
                  {{ t('subjectMatrix.empty') }}
                </td>
              </tr>
              <tr
                v-for="subject in subjects.filtered"
                :key="subject.id"
                :aria-label="ariaSortLabel(subject)"
                class="group hover:bg-slate-50/60"
              >
                <td
                  class="sticky left-0 z-10 bg-white group-hover:bg-slate-50 px-3 py-2 font-medium border-t border-slate-100 whitespace-nowrap"
                  style="box-shadow: 1px 0 0 #e2e8f0"
                >
                  <RouterLink :to="`/subjects/${subject.id}`" class="text-muw-blue hover:underline">
                    {{ subject.id }}
                  </RouterLink>
                  <span v-if="subject.secondaryId" class="ml-1.5 text-slate-400 text-[11px] font-normal">
                    · {{ subject.secondaryId }}
                  </span>
                </td>
                <td class="px-3 py-2 text-slate-600 border-t border-slate-100 whitespace-nowrap">{{ subject.gender }}</td>
                <td class="px-3 py-2 text-slate-600 font-mono text-[11px] border-t border-slate-100 whitespace-nowrap">
                  <span v-if="subject.studyEye" class="px-1.5 py-0.5 rounded bg-muw-blue-50 text-muw-blue border border-muw-blue-100">
                    {{ subject.studyEye }}
                  </span>
                  <span v-else class="text-slate-400">—</span>
                </td>
                <td class="px-3 py-2 text-slate-600 border-t border-slate-100 whitespace-nowrap">{{ subject.groupLabel ?? '—' }}</td>
                <td class="px-3 py-2 text-slate-600 font-mono text-xs border-t border-slate-100 whitespace-nowrap">{{ formatDate(subject.enrolledOn) }}</td>

                <td
                  v-for="(_col, idx) in eventColumns"
                  :key="idx"
                  class="px-3 py-2 border-t border-slate-100"
                >
                  <div v-if="subject.events[idx]" class="flex items-center justify-center gap-1.5">
                    <StatusPill :variant="statusVariant(subject.events[idx]!.status)">
                      {{ statusLabel(subject.events[idx]!.status) }}
                    </StatusPill>
                    <span
                      v-if="(subject.events[idx]!.openQueries ?? 0) > 0"
                      class="text-[10px] font-semibold text-rose-700 bg-rose-50 border border-rose-200 rounded-full px-1.5"
                      :title="t('subjectMatrix.openQueriesTooltip', { count: subject.events[idx]!.openQueries })"
                    >
                      {{ subject.events[idx]!.openQueries }}
                    </span>
                  </div>
                  <span v-else class="text-slate-500 text-center block">—</span>
                </td>

                <td class="px-3 py-2 border-t border-slate-100 whitespace-nowrap">
                  <StatusPill v-if="subject.signed" variant="success">{{ t('subjectMatrix.signed') }}</StatusPill>
                  <span v-else class="text-slate-400">—</span>
                </td>

                <td
                  class="sticky right-0 z-10 bg-white group-hover:bg-slate-50 px-3 py-2 text-right border-t border-slate-100 whitespace-nowrap"
                  style="box-shadow: -1px 0 0 #e2e8f0"
                >
                  <RouterLink :to="`/subjects/${subject.id}`" class="text-muw-blue text-xs hover:underline">
                    {{ t('subjectMatrix.openSubject') }}
                  </RouterLink>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="flex items-center justify-between px-3 py-2 text-[11px] text-slate-500 bg-slate-50/40 border-t border-slate-100">
          <span>{{ t('subjectMatrix.showingCount', { visible: subjects.visibleCount, total: subjects.totalCount }) }}</span>
        </div>
      </div>
    </div>

    <StudyMetricsModal :open="metricsModalOpen" @close="metricsModalOpen = false" />
  </div>
</template>

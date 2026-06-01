<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'

import { useSubjectsStore } from '@/stores/subjects'
import type { EventStatus } from '@/types/subject'

const { t } = useI18n()
const route = useRoute()
const subjects = useSubjectsStore()

const subjectId = computed(() => String(route.params.subjectId))

/**
 * Phase E.4 M3 — load the subject from its dedicated detail endpoint.
 * Drops the previous `subjects.rows.find(...)` derivation so the
 * detail view no longer depends on the matrix list being preloaded.
 */
onMounted(() => {
  subjects.fetchOne(subjectId.value)
})

watch(subjectId, (next, prev) => {
  if (next !== prev) {
    subjects.fetchOne(next)
  }
})

const subject = computed(() => subjects.selected)
const isLoading = computed(() => subjects.isLoadingSelected)
const loadError = computed(() => subjects.selectedError)

function statusVariant(status: EventStatus): 'success' | 'info' | 'warning' | 'neutral' {
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

const MONTH_ABBR = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const [y, m, d] = iso.split('-').map((s) => Number.parseInt(s, 10))
  return `${String(d ?? 1).padStart(2, '0')}-${MONTH_ABBR[(m ?? 1) - 1] ?? '???'}-${y}`
}

function genderLabel(g: string): string {
  return t(`addSubject.gender.${g === 'F' ? 'female' : g === 'M' ? 'male' : g === 'O' ? 'other' : 'unknown'}`)
}

function dataEntryStageLabel(stage: string | null): string {
  if (!stage) return '—'
  return t(`subjectDetail.dataEntryStage.${stage}`)
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
          <polyline points="9 22 9 12 15 12 15 22" />
        </svg>
        {{ t('nav.home') }}
      </RouterLink>
      <RouterLink to="/subjects" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <rect width="18" height="18" x="3" y="3" rx="2" />
          <path d="M3 9h18M9 21V9" />
        </svg>
        {{ t('nav.subjectMatrix') }}
      </RouterLink>
      <RouterLink to="/subjects/new" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <circle cx="12" cy="8" r="5" />
          <path d="M20 21a8 8 0 1 0-16 0" />
          <path d="M19 16v6M22 19h-6" />
        </svg>
        {{ t('nav.addSubject') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-4xl px-8 py-6">
      <p v-if="isLoading && !subject" class="text-slate-500 italic">{{ t('common.loading') }}</p>

      <template v-else-if="!subject">
        <div class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800">
          {{ loadError ?? t('subjectDetail.notFound', { id: subjectId }) }}
          <RouterLink to="/subjects" class="ml-2 underline">{{ t('subjectDetail.backToMatrix') }}</RouterLink>
        </div>
      </template>

      <template v-else>
        <!-- Header -->
        <div class="mb-5">
          <div class="text-xs text-slate-500 mb-1">{{ subject.studyName }} · {{ t('subjectDetail.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight flex items-center gap-3 flex-wrap">
            {{ subject.id }}
            <span v-if="subject.secondaryId" class="text-slate-400 font-normal text-sm">· {{ subject.secondaryId }}</span>
            <StatusPill v-if="subject.signed" variant="success">{{ t('subjectMatrix.signed') }}</StatusPill>
            <StatusPill v-else variant="warning">{{ t('subjectDetail.notSigned') }}</StatusPill>
          </h1>
        </div>

        <!-- Identity / enrolment card -->
        <section class="bg-white border border-slate-200 rounded-muw p-5 mb-5">
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
            {{ t('subjectDetail.identityHeading') }}
          </h2>
          <dl class="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.subjectId') }}</dt><dd class="font-medium">{{ subject.id }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.secondaryId') }}</dt><dd class="font-medium">{{ subject.secondaryId ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.gender') }}</dt><dd class="font-medium">{{ genderLabel(subject.gender) }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.yearOfBirth') }}</dt><dd class="font-medium">{{ subject.yearOfBirth ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.groupLabel') }}</dt><dd class="font-medium">{{ subject.groupLabel ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.enrolledOn') }}</dt><dd class="font-medium font-mono text-xs">{{ formatDate(subject.enrolledOn) }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('subjectDetail.openQueries') }}</dt>
              <dd class="font-medium">
                <StatusPill v-if="subject.openQueries > 0" variant="danger">{{ subject.openQueries }}</StatusPill>
                <span v-else class="text-slate-400">0</span>
              </dd>
            </div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('subjectMatrix.studyCard.status') }}</dt>
              <dd class="font-medium">
                <StatusPill v-if="subject.signed" variant="success">{{ t('subjectMatrix.signed') }}</StatusPill>
                <StatusPill v-else variant="warning">{{ t('subjectDetail.notSigned') }}</StatusPill>
              </dd>
            </div>
          </dl>
        </section>

        <!-- Events / casebook -->
        <section class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5">
          <div class="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
            <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
              {{ t('subjectDetail.eventsHeading') }}
            </h2>
            <span class="text-xs text-slate-500">
              {{ subject.events.length }} {{ t('subjectDetail.eventsCount') }}
            </span>
          </div>
          <DenseTable :bordered="false">
            <template #header>
              <tr class="border-b border-slate-200">
                <th scope="col" class="px-5 py-2 font-medium">{{ t('subjectDetail.column.event') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-28">{{ t('subjectDetail.column.dateStart') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-40">{{ t('subjectDetail.column.status') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-44">{{ t('subjectDetail.column.dataEntryStage') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-24 text-right">{{ t('subjectDetail.column.openQueries') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-28 text-right"></th>
              </tr>
            </template>
            <tr v-for="ev in subject.events" :key="ev.eventDefinitionOid">
              <td class="px-5 py-2.5 font-medium">
                <div>{{ ev.label }}</div>
                <div v-if="ev.location" class="text-xs text-slate-500 mt-0.5">{{ ev.location }}</div>
              </td>
              <td class="px-5 py-2.5 text-xs font-mono text-slate-600">{{ formatDate(ev.dateStart) }}</td>
              <td class="px-5 py-2.5">
                <StatusPill :variant="statusVariant(ev.status)">{{ t(`subjectMatrix.status.${ev.status}`) }}</StatusPill>
              </td>
              <td class="px-5 py-2.5 text-xs text-slate-600">{{ dataEntryStageLabel(ev.dataEntryStage) }}</td>
              <td class="px-5 py-2.5 text-right">
                <StatusPill v-if="ev.openQueries > 0" compact variant="danger">{{ ev.openQueries }}</StatusPill>
                <span v-else class="text-slate-400">—</span>
              </td>
              <td class="px-5 py-2.5 text-right">
                <RouterLink
                  :to="`/event-crfs/EC_${subject.id.replace('-', '')}_${ev.eventDefinitionOid.replace('SE_', '')}_DEMO`"
                  class="text-muw-blue text-xs hover:underline"
                >
                  {{ t('subjectDetail.openEvent') }}
                </RouterLink>
              </td>
            </tr>
          </DenseTable>
        </section>

        <!-- Action row -->
        <div class="flex items-center justify-between flex-wrap gap-3">
          <RouterLink to="/subjects" class="text-xs text-slate-500 hover:text-slate-700">
            ← {{ t('subjectDetail.backToMatrix') }}
          </RouterLink>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-1.5"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" x2="12" y1="15" y2="3" />
              </svg>
              {{ t('subjectDetail.actions.downloadCasebook') }}
            </button>
            <RouterLink
              v-if="!subject.signed"
              :to="`/subjects/${subject.id}/sign`"
              class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5 font-medium"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <path d="M20 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
              {{ t('subjectDetail.actions.signSubject') }}
            </RouterLink>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

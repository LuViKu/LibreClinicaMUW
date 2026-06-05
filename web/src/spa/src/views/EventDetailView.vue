<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'

import { useEventDetailStore } from '@/stores/eventDetail'
import type { EventCrfRowStatus, StudyEventStatus } from '@/types/event'

/**
 * Phase E.6 — standalone Event Detail view (replaces the legacy
 * `/pages/EnterDataForStudyEvent` JSP redirect SubjectDetailView's
 * "Öffnen" link used to bridge into). Renders the event metadata
 * plus the CRF roster wired in by its event_definition_crf rows;
 * each row deep-links into the existing CrfEntryView when an
 * event_crf already exists, or surfaces a "Datenerfassung starten"
 * affordance that opens the legacy entry servlet in a new tab as
 * the v1 bridge.
 */

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useEventDetailStore()

const eventId = computed<string>(() => String(route.params.eventId))

/**
 * Phase E.6 — track which CRF row is currently starting so we can
 * disable just that row's button + render its inline spinner without
 * affecting siblings. Keyed by {@code eventDefinitionCrfId}.
 */
const startingEdcId = ref<number | null>(null)

onMounted(() => {
  void store.load(eventId.value)
})
watch(eventId, (id) => {
  void store.load(id)
})

const event = computed(() => store.event)

function statusVariant(s: StudyEventStatus): 'success' | 'info' | 'warning' | 'danger' | 'neutral' {
  switch (s) {
    case 'signed':
    case 'completed':
      return 'success'
    case 'scheduled':
      return 'info'
    case 'data-entry-started':
      return 'info'
    case 'stopped':
    case 'skipped':
    case 'locked':
      return 'warning'
    case 'not-scheduled':
      return 'neutral'
    default:
      return 'neutral'
  }
}

function rowStatusVariant(s: EventCrfRowStatus): 'success' | 'info' | 'warning' | 'neutral' {
  switch (s) {
    case 'completed':
    case 'signed':
      return 'success'
    case 'data-entry-started':
      return 'info'
    case 'stopped':
      return 'warning'
    default:
      return 'neutral'
  }
}

const MONTH_ABBR = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez']
function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const [y, m, d] = iso.split('-').map((s) => Number.parseInt(s, 10))
  return `${String(d ?? 1).padStart(2, '0')}-${MONTH_ABBR[(m ?? 1) - 1] ?? '???'}-${y}`
}

/**
 * Phase E.6 — start data entry for a CRF slot that has no
 * event_crf row yet. POSTs to the backend, then routes into the
 * existing CrfEntryView via the freshly-minted eventCrfOid. Inline
 * error toast (shared across rows) on failure; per-row disabled
 * state via {@link startingEdcId}.
 */
async function startCrf(eventDefinitionCrfId: number): Promise<void> {
  if (!event.value) return
  startingEdcId.value = eventDefinitionCrfId
  try {
    const oid = await store.startCrf(event.value.eventId, eventDefinitionCrfId)
    if (oid != null) {
      await router.push({ name: 'crf-entry', params: { eventCrfOid: oid } })
    }
  } finally {
    startingEdcId.value = null
  }
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.home') }}
      </RouterLink>
      <RouterLink to="/subjects" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.subjectMatrix') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-4xl px-8 py-6">
      <p v-if="store.isLoading && !event" class="text-slate-500 italic">
        {{ t('common.loading') }}
      </p>

      <template v-else-if="store.notFound">
        <div class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800" data-test="event-detail-not-found">
          {{ t('eventDetail.error.notFound') }}
          <RouterLink to="/subjects" class="ml-2 underline">{{ t('eventDetail.back') }}</RouterLink>
        </div>
      </template>

      <template v-else-if="store.forbidden">
        <div class="rounded-muw border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800" data-test="event-detail-forbidden">
          {{ t('eventDetail.error.forbidden') }}
          <RouterLink to="/subjects" class="ml-2 underline">{{ t('eventDetail.back') }}</RouterLink>
        </div>
      </template>

      <template v-else-if="store.network || store.error">
        <div class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800" data-test="event-detail-error">
          {{ store.network ? t('eventDetail.error.network') : store.error }}
        </div>
      </template>

      <template v-else-if="event">
        <!-- Breadcrumb / header -->
        <div class="mb-5">
          <div class="text-xs text-slate-500 mb-1">
            {{ event.studyName }}
            <span class="text-slate-300"> / </span>
            <RouterLink :to="`/subjects/${event.subjectLabel}`" class="underline">{{ event.subjectLabel }}</RouterLink>
            <span class="text-slate-300"> / </span>
            {{ event.eventDefinitionName }}
            <span v-if="event.repeating && event.ordinal > 1" class="text-slate-400">· #{{ event.ordinal }}</span>
          </div>
          <h1 class="text-xl font-semibold tracking-tight flex items-center gap-3 flex-wrap">
            {{ event.eventDefinitionName }}
            <StatusPill :variant="statusVariant(event.status)">{{ t(`subjectMatrix.status.${event.status}`) }}</StatusPill>
          </h1>
          <p class="text-xs text-slate-500 mt-1 font-mono">{{ formatDate(event.dateStart) }}</p>
        </div>

        <!-- Phase E.6 — inline error toast for the "start data entry" action.
             Sits above the CRF table so the operator sees it without losing
             the row context; auto-clears on the next startCrf attempt. -->
        <div
          v-if="store.startCrfError"
          class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-2 mb-3 text-xs text-rose-800"
          data-test="event-detail-start-error"
          role="alert"
        >
          {{ t('eventDetail.error.startFailed') }}
        </div>

        <!-- CRFs -->
        <section class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5">
          <div class="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
            <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
              {{ t('eventDetail.title') }}
            </h2>
            <span class="text-xs text-slate-500">
              {{ event.crfs.length }} {{ t('eventDetail.subTitle') }}
            </span>
          </div>

          <p v-if="event.crfs.length === 0" class="px-5 py-6 text-xs text-slate-500 italic" data-test="event-detail-no-crfs">
            {{ t('eventDetail.empty') }}
          </p>

          <DenseTable v-else :bordered="false">
            <template #header>
              <tr class="border-b border-slate-200">
                <th scope="col" class="px-5 py-2 font-medium">{{ t('eventDetail.table.crfName') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-32">{{ t('eventDetail.table.version') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-40">{{ t('eventDetail.table.status') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-20 text-center">{{ t('eventDetail.table.required') }}</th>
                <th scope="col" class="px-5 py-2 font-medium w-44 text-right">{{ t('eventDetail.table.action') }}</th>
              </tr>
            </template>
            <tr v-for="crf in event.crfs" :key="crf.eventDefinitionCrfId" data-test="event-detail-crf-row">
              <td class="px-5 py-2.5 font-medium">{{ crf.crfName }}</td>
              <td class="px-5 py-2.5 text-xs font-mono text-slate-600">{{ crf.crfVersionName || '—' }}</td>
              <td class="px-5 py-2.5">
                <StatusPill :variant="rowStatusVariant(crf.status)">{{ t(`eventDetail.statusLabels.${crf.status}`) }}</StatusPill>
              </td>
              <td class="px-5 py-2.5 text-center text-xs">
                <span v-if="crf.required" class="text-rose-700" aria-label="required">*</span>
                <span v-else class="text-slate-400">—</span>
              </td>
              <td class="px-5 py-2.5 text-right text-xs">
                <RouterLink
                  v-if="crf.eventCrfOid"
                  :to="`/event-crfs/${crf.eventCrfOid}`"
                  class="text-muw-blue hover:underline"
                  data-test="event-detail-open-crf"
                >
                  {{ t('eventDetail.action.open') }}
                </RouterLink>
                <template v-else>
                  <button
                    type="button"
                    class="text-muw-blue hover:underline disabled:text-slate-400 disabled:cursor-not-allowed"
                    :disabled="startingEdcId === crf.eventDefinitionCrfId || store.isStartingCrf"
                    data-test="event-detail-start-spa"
                    @click="startCrf(crf.eventDefinitionCrfId)"
                  >
                    {{
                      startingEdcId === crf.eventDefinitionCrfId
                        ? t('eventDetail.action.starting')
                        : t('eventDetail.action.startSpa')
                    }}
                  </button>
                </template>
              </td>
            </tr>
          </DenseTable>
        </section>

        <RouterLink :to="`/subjects/${event.subjectLabel}`" class="text-xs text-muw-blue underline" data-test="event-detail-back">
          {{ t('eventDetail.back') }}
        </RouterLink>
      </template>
    </main>
  </div>
</template>

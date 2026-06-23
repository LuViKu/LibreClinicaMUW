<script setup lang="ts">
/**
 * Wave 2B (retinal followups) — VisitPickerModal.
 *
 * Replaces the v1 no-op {@code pick-visit} emit on the {@code novisit}
 * row state AND backs the "Visite zuweisen" affordance on the parked-
 * scans tab. Lists every scheduled / started / completed event the
 * picked subject owns + lets the operator select one for the bind.
 *
 * <p>Backend handshake — two hops:
 *  - {@code GET /pages/api/v1/events?subjectId={subjectLabel}} — list
 *    visits for the subject. The endpoint identifies subjects by
 *    LABEL, not numeric id, so the parent must pass the label too.
 *  - {@code GET /pages/api/v1/events/{eventId}} — on click, resolve
 *    the picked event into an {@code eventCrfId} (the bind target).
 *    The picker emits the FIRST non-removed event_crf id; the bind
 *    rejects null targets so this is the only safe default.
 *
 * <p>If the picked event has no started CRFs yet, the picker emits
 * with {@code eventCrfId} set to the placeholder {@code -1} so the
 * parent can surface an error toast — this is a clinical-data-system,
 * we never paper over a backend invariant client-side.
 *
 * <p>Strings come from {@code octPortal.modals.visitPicker.*} in the
 * Wave 1C i18n registry — no hard-coded German.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import { ApiError, ApiNetworkError, apiGet } from '@/api/client'
import type { StudyEvent } from '@/types/event'
import type { EventDetailDto } from '@/types/event'
import {
  listPatientEventsPublic,
  OctPortalError,
} from '@/api/octPortal'

interface Props {
  open: boolean
  /** Numeric id of the picked study_subject — carried through to the
   *  bind so the parent can match the row state. */
  studySubjectId: number
  /** Subject LABEL — what {@code /api/v1/events?subjectId=...} expects
   *  on the AUTH'd branch. The {@code publicContext} branch uses the
   *  numeric {@code studySubjectId} instead. Required for the
   *  AssignParkedDialog admin flow; defaults to empty string for the
   *  public OCT-portal mount. */
  subjectLabel?: string
  /**
   * 2026-06-19 — when true, load events via the anonymous
   * {@code /api/v1/public/oct-upload/patients/{id}/events} endpoint
   * (single-hop; firstEventCrfId pre-resolved). The portal at
   * {@code /app/oct-upload} sets this to true; AssignParkedDialog in
   * the admin view leaves it false (auth'd two-hop path).
   */
  publicContext?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  subjectLabel: '',
  publicContext: false,
})

/**
 * Picked-event emit payload.
 *
 * 2026-06-23 — extended to carry {@code studyEventId} so the picker
 * can bind to planned-but-not-started visits (no event_crf yet).
 * Commit prefers {@code eventCrfId} when set and falls back to
 * {@code studyEventId}; the store handles the dispatch. With this
 * change, every selectable visit emits a valid binding — the legacy
 * {@code eventCrfId: -1} "Keine Eingabemaske" sentinel goes away.
 */
export interface PickedEvent {
  studyEventId: number
  eventCrfId: number | null
  definitionLabel: string
  dateStart: string
}

const emit = defineEmits<{
  (e: 'event-picked', evt: PickedEvent): void
  (e: 'close'): void
}>()

const { t } = useI18n()

const events = ref<StudyEvent[]>([])
const isLoading = ref(false)
const errorMessage = ref<string | null>(null)
/** Per-event-id flag — true while the second-hop GET /events/{id} is
 *  in flight to keep the operator from double-clicking a row. */
const resolvingId = ref<string | null>(null)
/**
 * 2026-06-19 — populated on the {@code publicContext} branch only,
 * mapping event_id (string) → first non-removed event_crf_id. The
 * public endpoint pre-resolves this in one query, saving the second
 * hop that the auth'd branch does via {@code GET /events/{id}}.
 * Auth'd branch leaves this empty + uses the original second-hop.
 */
const publicEventCrfIdByEventId = ref<Map<string, number | null>>(new Map())

const hasResults = computed(() => events.value.length > 0)

async function load(): Promise<void> {
  errorMessage.value = null
  events.value = []
  publicEventCrfIdByEventId.value = new Map()
  isLoading.value = true
  try {
    if (props.publicContext) {
      // 2026-06-19 — anonymous OCT-portal path. The auth'd /events
      // endpoint requires a session and the portal at /app/oct-upload
      // is unauthenticated by design. Use the public mirror that
      // also pre-resolves firstEventCrfId in one query.
      const list = await listPatientEventsPublic(props.studySubjectId)
      events.value = list.map<StudyEvent>((p) => ({
        id: p.id,
        subjectId: '',
        eventDefinitionOid: p.eventDefinitionOid,
        eventLabel: p.eventLabel,
        ordinal: p.ordinal,
        dateStarted: p.dateStarted,
        dateEnded: p.dateEnded,
        location: p.location,
        // Coerce the backend's lowercase-hyphenated status to the
        // StudyEvent union; unknown values render as plain text via
        // the {@link pillClass} default branch.
        status: p.status as StudyEvent['status'],
        repeating: p.repeating,
        // nAMD Slice 1 (#226) added these to the StudyEvent surface.
        // The public mirror endpoint doesn't carry them and the
        // portal flow never uses scheduling — stub with the empty
        // values the StudyEvent shape's Required<> wrapper demands.
        scheduledFor: '',
        scheduledIntervalDays: 0,
      }))
      const m = new Map<string, number | null>()
      for (const p of list) m.set(p.id, p.firstEventCrfId)
      publicEventCrfIdByEventId.value = m
      return
    }
    // Auth'd path (AssignParkedDialog → admin view).
    const params = new URLSearchParams()
    params.set('subjectId', props.subjectLabel)
    const list = await apiGet<StudyEvent[]>(
      `/pages/api/v1/events?${params.toString()}`,
    )
    events.value = list
  } catch (e) {
    errorMessage.value = describeLoadError(e)
  } finally {
    isLoading.value = false
  }
}

/** Centralises the "fall back to inner message else generic i18n"
 *  ladder so {@link load} and {@link pick} stay short. */
function describeLoadError(e: unknown): string {
  if (e instanceof ApiError || e instanceof OctPortalError) {
    const body = (e as ApiError | OctPortalError).body as { message?: string } | null
    if (body?.message) return body.message
    if (e.message) return e.message
    return t('octPortal.modals.visitPicker.loadError')
  }
  if (e instanceof ApiNetworkError) {
    return e.message || t('octPortal.modals.visitPicker.loadError')
  }
  if (e instanceof Error && e.message) return e.message
  return t('octPortal.modals.visitPicker.loadError')
}

async function pick(evt: StudyEvent): Promise<void> {
  if (resolvingId.value != null) return
  resolvingId.value = evt.id
  errorMessage.value = null
  try {
    // 2026-06-23 — evt.id IS String.valueOf(study_event_id) at the
    // backend (see PublicOctUploadController.listPatientEventsPublic).
    // Parse once + emit on every branch so a planned visit (no
    // event_crf) still binds via studyEventId.
    const studyEventId = Number.parseInt(evt.id, 10)
    if (!Number.isFinite(studyEventId)) {
      errorMessage.value = t('octPortal.modals.visitPicker.loadError')
      return
    }
    if (props.publicContext) {
      // Public path: firstEventCrfId was pre-resolved server-side at
      // load time. null is a real value — the visit is planned but
      // the CRF hasn't been opened yet. Commit binds the job via
      // studyEventId instead.
      const preResolved = publicEventCrfIdByEventId.value.get(evt.id)
      emit('event-picked', {
        studyEventId,
        eventCrfId: preResolved ?? null,
        definitionLabel: evt.eventLabel,
        dateStart: evt.dateStarted,
      })
      return
    }
    // Auth'd path: second-hop to /events/{id} to resolve the first
    // non-removed event_crf.
    const detail = await apiGet<EventDetailDto>(`/pages/api/v1/events/${evt.id}`)
    const firstCrf = detail.crfs.find(
      (c) => c.eventCrfId != null && c.status !== 'removed',
    )
    emit('event-picked', {
      studyEventId,
      eventCrfId: firstCrf?.eventCrfId ?? null,
      definitionLabel: evt.eventLabel,
      dateStart: evt.dateStarted,
    })
  } catch (e) {
    errorMessage.value = describeLoadError(e)
  } finally {
    resolvingId.value = null
  }
}

function onClose(): void {
  emit('close')
}

/** Status pill tones — visual only; the operator decides based on the
 *  label / date which event matches the parked scan. */
function pillClass(status: string): string {
  switch (status) {
    case 'completed':
    case 'signed':
    case 'locked':
      return 'bg-muw-teal-50 text-muw-teal-700 border-muw-teal-200'
    case 'data-entry-started':
      return 'bg-amber-50 text-amber-700 border-amber-200'
    case 'stopped':
    case 'skipped':
    case 'removed':
      return 'bg-rose-50 text-rose-700 border-rose-200'
    default:
      return 'bg-slate-50 text-slate-600 border-slate-200'
  }
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      void load()
    }
  },
  { immediate: true },
)

const labelledById = 'oct-portal-visit-picker-heading'
</script>

<template>
  <Modal
    :open="props.open"
    :labelled-by="labelledById"
    panel-class="max-w-xl"
    @close="onClose"
  >
    <template #header>
      <h2 :id="labelledById" class="text-[15px] font-semibold text-slate-900">
        {{ t('octPortal.modals.visitPicker.title') }}
      </h2>
    </template>

    <div class="flex flex-col gap-3">
      <div v-if="errorMessage" data-testid="visit-picker-error" class="text-[13px] text-rose-700">
        {{ errorMessage }}
      </div>

      <div v-else-if="isLoading" data-testid="visit-picker-loading" class="text-[13px] text-slate-500">
        {{ t('octPortal.modals.visitPicker.loading') }}
      </div>

      <div v-else-if="!hasResults" data-testid="visit-picker-empty" class="text-[13px] text-slate-500">
        {{ t('octPortal.modals.visitPicker.empty') }}
      </div>

      <ul v-else data-testid="visit-picker-results" class="flex flex-col gap-1.5 max-h-96 overflow-y-auto">
        <li v-for="evt in events" :key="evt.id">
          <button
            type="button"
            :data-testid="`visit-picker-result-${evt.id}`"
            class="w-full text-left rounded-md border border-slate-200 bg-white px-3 py-2 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-muw-blue disabled:opacity-60 disabled:cursor-wait"
            :disabled="resolvingId !== null && resolvingId !== evt.id"
            @click="pick(evt)"
          >
            <div class="flex items-center gap-2 justify-between">
              <div class="text-[13px] font-semibold text-slate-900 flex-1 min-w-0 truncate">
                {{ evt.eventLabel }}
              </div>
              <span
                class="text-[10.5px] uppercase tracking-wide font-medium border rounded px-1.5 py-0.5 shrink-0"
                :class="pillClass(evt.status)"
              >{{ evt.status }}</span>
            </div>
            <div class="text-[11.5px] text-slate-500 mt-0.5">
              {{ evt.dateStarted }}
              <span v-if="resolvingId === evt.id" class="ml-1 italic text-slate-400">…</span>
            </div>
          </button>
        </li>
      </ul>
    </div>

    <template #footer>
      <button
        type="button"
        data-testid="visit-picker-cancel"
        class="px-3 py-1.5 text-[12.5px] font-medium border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
        @click="onClose"
      >
        {{ t('octPortal.modals.visitPicker.cancel') }}
      </button>
    </template>
  </Modal>
</template>

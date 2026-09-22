<script setup lang="ts">
/**
 * Whether this patient is on the fundus camera's worklist today — and the two
 * fixes when not.
 *
 * The camera (Optomed Lumo, DR-025) has no patient list of its own: it pulls
 * a Modality Worklist, and that worklist *is* the visit schedule, filtered to
 * today. There is no worklist entry to create. So "the patient is not on the
 * camera" always means one of two things — no visit is scheduled for today,
 * or the visit sits on another date — and both are fixed here, on the page
 * the operator is already on, rather than discovered at the device with the
 * patient in the chair.
 *
 * The status comes from the server, which applies the same query and the
 * same study scope the worklist endpoint serves, so this strip and the camera
 * cannot disagree. Nothing renders for a study the worklist does not offer
 * (no receiver configured, or the study switched off): the strip is only
 * meaningful where a camera is.
 *
 * Direct scheduling is offered only when it cannot collide: the study has
 * exactly one visit definition, and either no open visit of it exists on
 * another day or the definition repeats. Otherwise the operator is handed the
 * ordinary schedule dialog, or the existing visit is moved to today instead.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import {
  getCameraWorklist,
  type CameraWorklistStatus,
  type CameraWorklistVisit,
} from '@/api/events'
import { useEventsStore } from '@/stores/events'
import { useEventDefinitionsStore } from '@/stores/eventDefinitions'
import { formatDate } from '@/lib/dateFormat'

interface Props {
  /** Subject label — the path segment the subject endpoints accept. */
  subjectId: string
  /** OID of the active study — drives the visit-definition load. */
  studyOid: string
  /** Whether the operator's role may schedule or move visits. */
  canSchedule: boolean
  /**
   * Anything that, when it changes, means the schedule changed. The parent
   * passes a signature of the subject's visits so a visit scheduled, moved or
   * cancelled elsewhere on the page refreshes this strip too.
   */
  eventsSignature?: string
}

const props = withDefaults(defineProps<Props>(), { eventsSignature: '' })

const emit = defineEmits<{
  /** The schedule changed here — the parent should re-fetch the subject. */
  changed: []
  /** More than one visit definition: the parent opens its schedule dialog. */
  'open-schedule': []
}>()

const { t } = useI18n()
const events = useEventsStore()
const eventDefinitions = useEventDefinitionsStore()

const status = ref<CameraWorklistStatus | null>(null)
const loadFailed = ref(false)
const busy = ref(false)
const actionError = ref<string | null>(null)

async function load(): Promise<void> {
  try {
    status.value = await getCameraWorklist(props.subjectId)
    loadFailed.value = false
  } catch {
    // A strip that cannot load says nothing rather than something wrong;
    // the visits table beside it still shows the schedule.
    status.value = null
    loadFailed.value = true
  }
}

onMounted(() => {
  void load()
  if (eventDefinitions.rows.length === 0 && props.studyOid) {
    // The store rethrows 401/403 so a caller can react; here nothing can
    // — the page's own subject load surfaces an expired session, and this
    // strip merely loses its schedule button.
    eventDefinitions.load(props.studyOid).catch(() => undefined)
  }
})
watch(() => props.subjectId, () => void load())
watch(() => props.eventsSignature, () => void load())

const offered = computed(() => status.value?.offered === true)
const today = computed<CameraWorklistVisit[]>(() => status.value?.today ?? [])
const otherOpen = computed<CameraWorklistVisit[]>(() => status.value?.otherOpen ?? [])
const onList = computed(() => today.value.length > 0)

const eligibleDefinitions = computed(() =>
  eventDefinitions.rows.filter((d) => d.status !== 'removed'),
)
const singleDefinition = computed(() =>
  eligibleDefinitions.value.length === 1 ? eligibleDefinitions.value[0]! : null,
)
/** One definition, and scheduling it today cannot produce a duplicate. */
const canScheduleDirect = computed(
  () =>
    singleDefinition.value !== null
    && (otherOpen.value.length === 0 || singleDefinition.value.repeating),
)
const canPickDefinition = computed(() => eligibleDefinitions.value.length > 1)

async function scheduleToday(): Promise<void> {
  const def = singleDefinition.value
  if (!def || !status.value || busy.value) return
  busy.value = true
  actionError.value = null
  try {
    const created = await events.schedule({
      subjectId: props.subjectId,
      eventDefinitionOid: def.oid,
      // The server's today, not the browser's: the worklist is served by
      // the server's clock.
      dateStarted: status.value.date,
    })
    if (created) {
      emit('changed')
      await load()
    } else {
      actionError.value = events.error ?? t('cameraWorklist.error.generic')
    }
  } finally {
    busy.value = false
  }
}

async function moveToToday(visit: CameraWorklistVisit): Promise<void> {
  if (!status.value || busy.value) return
  busy.value = true
  actionError.value = null
  try {
    const result = await events.updateEvent(String(visit.studyEventId), {
      dateStarted: status.value.date,
    })
    if (result.ok) {
      emit('changed')
      await load()
    } else {
      actionError.value = result.message
    }
  } finally {
    busy.value = false
  }
}

function otherOpenText(visit: CameraWorklistVisit): string {
  return visit.date
    ? t('cameraWorklist.otherDay', { event: visit.eventLabel, date: formatDate(visit.date) })
    : t('cameraWorklist.noDate', { event: visit.eventLabel })
}
</script>

<template>
  <div
    v-if="offered && !loadFailed"
    class="mx-5 mt-3 rounded-md border px-3 py-2 text-xs flex flex-wrap items-center gap-x-3 gap-y-1.5"
    :class="onList
      ? 'border-emerald-200 bg-emerald-50 text-emerald-900'
      : 'border-amber-200 bg-amber-50 text-amber-900'"
    role="status"
    data-testid="camera-worklist"
  >
    <svg
      width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
      class="shrink-0" aria-hidden="true"
    >
      <path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z" />
      <circle cx="12" cy="13" r="3" />
    </svg>

    <template v-if="onList">
      <span class="font-semibold" data-testid="camera-worklist-on">
        {{ t('cameraWorklist.on') }}
      </span>
      <span
        v-for="visit in today"
        :key="visit.studyEventId"
        class="inline-flex items-center gap-1.5"
        data-testid="camera-worklist-visit"
      >
        <span>{{ visit.eventLabel }}</span>
        <span v-if="visit.time" class="text-emerald-700">{{ visit.time }}</span>
        <code class="font-mono text-[11px] text-emerald-700/80">{{ visit.accession }}</code>
      </span>
    </template>

    <template v-else>
      <span class="font-semibold" data-testid="camera-worklist-off">
        {{ t('cameraWorklist.off') }}
      </span>
      <span v-if="otherOpen.length === 0">{{ t('cameraWorklist.noVisitToday') }}</span>
      <span
        v-for="visit in otherOpen"
        :key="visit.studyEventId"
        class="inline-flex flex-wrap items-center gap-2"
      >
        <span>{{ otherOpenText(visit) }}</span>
        <button
          v-if="canSchedule"
          type="button"
          class="px-2 py-0.5 border border-amber-300 rounded-md bg-white hover:bg-amber-100 text-amber-900 disabled:opacity-50"
          :disabled="busy"
          data-testid="camera-worklist-move"
          @click="moveToToday(visit)"
        >
          {{ t('cameraWorklist.moveToToday') }}
        </button>
      </span>
      <button
        v-if="canSchedule && canScheduleDirect"
        type="button"
        class="px-2 py-0.5 border border-amber-300 rounded-md bg-white hover:bg-amber-100 text-amber-900 font-medium disabled:opacity-50"
        :disabled="busy"
        data-testid="camera-worklist-schedule"
        @click="scheduleToday"
      >
        {{ busy
          ? t('common.saving')
          : t('cameraWorklist.scheduleToday', { event: singleDefinition!.name }) }}
      </button>
      <button
        v-else-if="canSchedule && canPickDefinition"
        type="button"
        class="px-2 py-0.5 border border-amber-300 rounded-md bg-white hover:bg-amber-100 text-amber-900 font-medium"
        data-testid="camera-worklist-pick"
        @click="emit('open-schedule')"
      >
        {{ t('cameraWorklist.scheduleTodayPick') }}
      </button>
      <span
        v-if="actionError"
        class="text-rose-700"
        role="alert"
        data-testid="camera-worklist-error"
      >
        {{ actionError }}
      </span>
    </template>
  </div>
</template>

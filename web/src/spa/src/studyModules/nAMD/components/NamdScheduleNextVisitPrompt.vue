<script setup lang="ts">
/**
 * P2-5 — after a treat-and-extend decision, put the next visit on the calendar.
 *
 * The decision panel records an interval and stopped there. Nothing scheduled
 * anything, so "extend to 10 weeks" was a note in a form and the appointment
 * existed only if someone remembered to make it. In this study a visit is an
 * injection, so a visit nobody scheduled is a treatment nobody gave.
 *
 * The prompt proposes the date the decision implies and asks. It does not
 * schedule on its own: the date is a clinical commitment and the clinician may
 * know the patient is away that week.
 *
 * It stays quiet when there is nothing useful to offer — no interval was
 * recorded, the visit definition does not repeat, or a visit of that definition
 * is already scheduled ahead.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useEventsStore } from '@/stores/events'

const props = defineProps<{
  /** Subject label, as POST /events expects it. */
  subjectLabel: string
  /** The visit the decision was made on. */
  studyEventId: number | null
  /** ISO date the decision was recorded for. */
  decisionDate: string
  /** Interval the clinician chose, in weeks. */
  intervalWeeks: number | null
}>()

const emit = defineEmits<{ (e: 'done'): void }>()

const { t } = useI18n()
const eventsStore = useEventsStore()

const loading = ref(true)
const saving = ref(false)
const error = ref<string | null>(null)
const skipped = ref(false)
const scheduled = ref(false)

/** The visit the decision was made on, resolved from the events list. */
const currentEvent = computed(() =>
  eventsStore.events.find(
    (e) => e.subjectId === props.subjectLabel && String(e.id) === String(props.studyEventId),
  ) ?? null,
)

/** A visit of the same definition already on the calendar, after the decision date. */
const alreadyScheduled = computed(() => {
  const def = currentEvent.value?.eventDefinitionOid
  if (!def) return null
  return (
    eventsStore.events.find(
      (e) =>
        e.subjectId === props.subjectLabel &&
        e.eventDefinitionOid === def &&
        e.status === 'scheduled' &&
        !!e.dateStarted &&
        e.dateStarted > props.decisionDate,
    ) ?? null
  )
})

/** decisionDate + intervalWeeks × 7, as an ISO date. */
const proposedDate = computed<string | null>(() => {
  if (props.intervalWeeks == null || props.intervalWeeks <= 0) return null
  const base = Date.parse(props.decisionDate)
  if (!Number.isFinite(base)) return null
  const d = new Date(base + props.intervalWeeks * 7 * 24 * 60 * 60 * 1000)
  return d.toISOString().slice(0, 10)
})

const canOffer = computed(
  () =>
    !loading.value &&
    !skipped.value &&
    !scheduled.value &&
    proposedDate.value !== null &&
    currentEvent.value !== null &&
    currentEvent.value.repeating &&
    alreadyScheduled.value === null,
)

onMounted(async () => {
  try {
    if (eventsStore.events.length === 0) await eventsStore.load()
  } finally {
    loading.value = false
  }
})

async function confirm(): Promise<void> {
  const def = currentEvent.value?.eventDefinitionOid
  const date = proposedDate.value
  if (!def || !date || props.intervalWeeks == null) return
  saving.value = true
  error.value = null
  try {
    const created = await eventsStore.schedule({
      subjectId: props.subjectLabel,
      eventDefinitionOid: def,
      dateStarted: date,
      // The interval travels with the visit so the schedule view can show
      // what it was planned against, not just when it lands.
      scheduledIntervalDays: props.intervalWeeks * 7,
    })
    if (created) {
      scheduled.value = true
      emit('done')
    } else {
      error.value = eventsStore.error
    }
  } finally {
    saving.value = false
  }
}

function skip(): void {
  skipped.value = true
  emit('done')
}
</script>

<template>
  <div
    v-if="canOffer"
    class="mt-4 rounded-xl ring-1 ring-muw-blue-200 bg-muw-blue-50 p-4"
    data-testid="namd-schedule-prompt"
  >
    <div class="text-[14px] font-semibold text-slate-800">
      {{ t('studyModules.namd.schedule.title') }}
    </div>
    <p class="mt-1 text-[13px] text-slate-700">
      {{ t('studyModules.namd.schedule.body', { weeks: props.intervalWeeks, date: proposedDate }) }}
    </p>
    <p v-if="error" class="mt-2 text-[12px] text-rose-700" data-testid="namd-schedule-error">
      {{ error }}
    </p>
    <div class="mt-3 flex gap-2">
      <button
        type="button"
        class="px-3 py-2 rounded-lg bg-muw-blue text-white text-[13px] font-medium hover:bg-muw-blue-700 disabled:bg-slate-300"
        :disabled="saving"
        data-testid="namd-schedule-confirm"
        @click="confirm"
      >
        {{ saving ? t('studyModules.namd.schedule.saving') : t('studyModules.namd.schedule.confirm') }}
      </button>
      <button
        type="button"
        class="px-3 py-2 rounded-lg ring-1 ring-slate-300 bg-white text-slate-700 text-[13px] hover:bg-slate-50"
        data-testid="namd-schedule-skip"
        @click="skip"
      >
        {{ t('studyModules.namd.schedule.skip') }}
      </button>
    </div>
  </div>

  <div
    v-else-if="!loading && !skipped && !scheduled && alreadyScheduled"
    class="mt-4 text-[13px] text-slate-500"
    data-testid="namd-schedule-existing"
  >
    {{ t('studyModules.namd.schedule.alreadyPlanned', { date: alreadyScheduled.dateStarted }) }}
  </div>
</template>

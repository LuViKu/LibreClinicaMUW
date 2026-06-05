<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import ScheduleEventDialog from '@/components/ScheduleEventDialog.vue'
import SubjectExportButton from '@/components/SubjectExportButton.vue'

import { useSubjectsStore } from '@/stores/subjects'
import { useEventsStore } from '@/stores/events'
import { useAuthStore } from '@/stores/auth'
import type { EventStatus, Gender } from '@/types/subject'
import { canManageSubjectLifecycle, canEditSubject } from '@/types/subject'
import type { StudyEventStatus } from '@/types/event'
import { canEditEvent, canCancelEvent } from '@/types/event'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const subjects = useSubjectsStore()
const events = useEventsStore()
const auth = useAuthStore()

/**
 * Phase E A3 — role-gated "Remove subject" action. The button only
 * renders for Data Manager + Administrator; the backend re-checks
 * the same predicate and 403s any other role. A native confirm()
 * makes the GCP impact explicit (the soft-delete cascades to every
 * event_crf + item_data row).
 */
const canRemove = computed(() => {
  const role = auth.user?.role ?? null
  return !!role && canManageSubjectLifecycle(role)
})

async function onRemove() {
  if (!subject.value) return
  if (!confirm(t('subjectDetail.actions.removeConfirm', { id: subject.value.id }))) return
  const ok = await subjects.removeSubject(subject.value.id)
  if (ok) router.push('/subjects')
}

/* -------------------------------------------------------------- */
/* Phase E A2 — editable identity card.                            */
/*                                                                 */
/* The identity panel switches between read-only (default) and an   */
/* edit form when `editing` is true. Form fields: secondaryId,     */
/* gender, yearOfBirth. id + enrolledOn stay read-only (legacy      */
/* invariants).                                                    */
/* -------------------------------------------------------------- */

const canEdit = computed(() => {
  const role = auth.user?.role ?? null
  return !!role && canEditSubject(role)
})

interface EditForm {
  secondaryId: string
  gender: Gender
  yearOfBirth: string
}
const editing = ref(false)
const isSaving = ref(false)
const form = ref<EditForm>({ secondaryId: '', gender: 'F', yearOfBirth: '' })
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)

function startEdit() {
  if (!subject.value) return
  form.value = {
    secondaryId: subject.value.secondaryId ?? '',
    gender: subject.value.gender as Gender,
    yearOfBirth:
      subject.value.yearOfBirth != null ? String(subject.value.yearOfBirth) : '',
  }
  fieldErrors.value = {}
  formError.value = null
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  fieldErrors.value = {}
  formError.value = null
}

async function submitEdit() {
  if (!subject.value) return
  fieldErrors.value = {}
  formError.value = null
  const yob = form.value.yearOfBirth.trim()
  const parsedYob = yob === '' ? null : Number.parseInt(yob, 10)
  if (yob !== '' && Number.isNaN(parsedYob as number)) {
    fieldErrors.value.yearOfBirth = t('subjectDetail.edit.yearOfBirthInvalid')
    return
  }
  isSaving.value = true
  try {
    const result = await subjects.updateSubject(subject.value.id, {
      secondaryId: form.value.secondaryId.trim() === '' ? null : form.value.secondaryId.trim(),
      gender: form.value.gender,
      yearOfBirth: parsedYob,
    })
    if (result.ok) {
      editing.value = false
    } else {
      fieldErrors.value = result.fieldErrors
      formError.value = result.message ?? null
    }
  } finally {
    isSaving.value = false
  }
}

/* ------------------------------------------------------------- */
/* Phase E A4 — per-event edit + cancel.                          */
/*                                                                */
/* Edit opens an inline composer (slides into the row below) with */
/* dateStarted / location / status. Cancel shows a native confirm */
/* with the GCP-impact text and triggers DELETE on confirm.       */
/* Both refresh the subject detail after success so the events    */
/* table re-renders from the now-current backend state.           */
/* ------------------------------------------------------------- */

interface EditEventState {
  eventId: string
  eventDefinitionOid: string
  dateStart: string
  location: string
  status: StudyEventStatus
  fieldError: string | null
}

const editEvent = ref<EditEventState | null>(null)
const isSavingEvent = ref(false)

function openEditEvent(ev: {
  eventId: string
  eventDefinitionOid: string
  dateStart: string | null
  location: string | null
  status: EventStatus
}) {
  if (!ev.eventId) return
  // SPA's EventStatus is a superset of editable StudyEventStatus —
  // map back. If status isn't directly editable, default to
  // 'scheduled' (user can still change the date / location).
  const editable: StudyEventStatus =
    ev.status === 'scheduled' ? 'scheduled'
    : (ev.status as StudyEventStatus) === 'stopped' ? 'stopped'
    : (ev.status as StudyEventStatus) === 'skipped' ? 'skipped'
    : 'scheduled'
  editEvent.value = {
    eventId: ev.eventId,
    eventDefinitionOid: ev.eventDefinitionOid,
    dateStart: ev.dateStart ?? '',
    location: ev.location ?? '',
    status: editable,
    fieldError: null,
  }
}

function cancelEditEvent() {
  editEvent.value = null
}

async function submitEditEvent() {
  if (!editEvent.value || !subject.value) return
  // ISO date sanity check at the form layer; the backend does the
  // authoritative parse + 400.
  const date = editEvent.value.dateStart.trim()
  if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    editEvent.value.fieldError = t('subjectDetail.event.dateInvalid')
    return
  }
  isSavingEvent.value = true
  try {
    const result = await events.updateEvent(editEvent.value.eventId, {
      dateStarted: date,
      location: editEvent.value.location.trim(),
      status: editEvent.value.status,
    })
    if (result.ok) {
      editEvent.value = null
      // Refresh subject detail so the events table flips to the
      // new state — the events store is separate from
      // subjects.selected.events, so we re-fetch.
      await subjects.fetchOne(subject.value.id)
    } else {
      editEvent.value.fieldError = result.message
    }
  } finally {
    isSavingEvent.value = false
  }
}

async function onCancelEvent(ev: { eventId: string; label: string }) {
  if (!ev.eventId || !subject.value) return
  if (!confirm(t('subjectDetail.event.cancelConfirm', { label: ev.label }))) return
  const ok = await events.cancelEvent(ev.eventId)
  if (ok) {
    await subjects.fetchOne(subject.value.id)
  }
}

function canEditEv(status: EventStatus): boolean {
  const role = auth.user?.role ?? null
  return !!role && canEditEvent(role, status as StudyEventStatus)
}

function canCancelEv(status: EventStatus): boolean {
  const role = auth.user?.role ?? null
  return !!role && canCancelEvent(role, status as StudyEventStatus)
}

/* ------------------------------------------------------------- */
/* Phase E.6 — Schedule-event dialog.                              */
/*                                                                */
/* Surfaces the long-wired but never-rendered POST /events flow.   */
/* The button only renders for roles that can plausibly schedule a */
/* visit (Investigator / CRC / Administrator); other roles see the */
/* existing per-event edit/cancel actions but no schedule entry    */
/* point. On success the subject detail is re-fetched so the events */
/* table reflects the new row without a manual reload.             */
/* ------------------------------------------------------------- */

const scheduleDialogOpen = ref(false)
const canScheduleEvent = computed(() => {
  const role = auth.user?.role ?? null
  return role === 'Investigator' || role === 'CRC' || role === 'Administrator'
})
const activeStudyOid = computed(() => auth.user?.activeStudy?.oid ?? '')

async function onEventScheduled() {
  if (subject.value) {
    await subjects.fetchOne(subject.value.id)
  }
}

/* Phase E A3-lock — DM/Admin only; visibility also gated by current
   state (lock only available when not locked, vice versa). */
const canLock = computed(() => canRemove.value && !subject.value?.locked)
const canUnlock = computed(() => canRemove.value && subject.value?.locked)

async function onLock() {
  if (!subject.value) return
  if (!confirm(t('subjectDetail.actions.lockConfirm', { id: subject.value.id }))) return
  await subjects.lockSubject(subject.value.id)
}

async function onUnlock() {
  if (!subject.value) return
  await subjects.unlockSubject(subject.value.id)
}

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
            <StatusPill v-if="subject.locked" variant="warning">{{ t('subjectDetail.locked') }}</StatusPill>
          </h1>
        </div>

        <!-- Identity / enrolment card -->
        <section class="bg-white border border-slate-200 rounded-muw p-5 mb-5">
          <div class="flex items-center justify-between mb-3">
            <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
              {{ t('subjectDetail.identityHeading') }}
            </h2>
            <button
              v-if="!editing && canEdit"
              type="button"
              class="text-xs text-muw-blue underline hover:text-muw-blue-700"
              @click="startEdit"
            >{{ t('subjectDetail.edit.start') }}</button>
          </div>

          <!-- Read-only mode -->
          <dl v-if="!editing" class="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.subjectId') }}</dt><dd class="font-medium">{{ subject.id }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.secondaryId') }}</dt><dd class="font-medium">{{ subject.secondaryId ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.gender') }}</dt><dd class="font-medium">{{ genderLabel(subject.gender) }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.yearOfBirth') }}</dt><dd class="font-medium">{{ subject.yearOfBirth ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.groupLabel') }}</dt><dd class="font-medium">{{ subject.groupLabel ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.enrolledOn') }}</dt><dd class="font-medium font-mono text-xs">{{ formatDate(subject.enrolledOn) }}</dd></div>
            <!-- Phase E.6 Tier 1 — ophthalmology study-eye + screening date.
                 Read-only here; editing arrives in a later milestone
                 along with the eye-scope per-arm overrides. -->
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('ophth.studyEye.label') }}</dt>
              <dd class="font-medium">
                <span v-if="subject.studyEye" class="font-mono text-xs px-1.5 py-0.5 rounded bg-muw-blue-50 text-muw-blue border border-muw-blue-100">{{ subject.studyEye }}</span>
                <span v-else class="text-slate-400">—</span>
              </dd>
            </div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('ophth.screeningDate.label') }}</dt><dd class="font-medium font-mono text-xs">{{ subject.screeningDate ? formatDate(subject.screeningDate) : '—' }}</dd></div>
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

          <!-- Edit mode (Phase E A2) -->
          <form v-else class="grid grid-cols-2 gap-x-6 gap-y-4 text-sm" @submit.prevent="submitEdit">
            <!-- read-only fields -->
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.subjectId') }}</dt><dd class="font-medium">{{ subject.id }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">{{ t('addSubject.field.enrolledOn') }}</dt><dd class="font-medium font-mono text-xs">{{ formatDate(subject.enrolledOn) }}</dd></div>

            <div>
              <FieldLabel for="edit-secondary-id">{{ t('addSubject.field.secondaryId') }}</FieldLabel>
              <TextInput
                id="edit-secondary-id"
                v-model="form.secondaryId"
                :placeholder="t('addSubject.placeholder.secondaryId')"
                :error="!!fieldErrors.secondaryId"
              />
              <ErrorText v-if="fieldErrors.secondaryId">{{ fieldErrors.secondaryId }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="edit-gender">{{ t('addSubject.field.gender') }}</FieldLabel>
              <SelectInput
                id="edit-gender"
                v-model="form.gender"
                :error="!!fieldErrors.gender"
              >
                <option value="F">{{ t('addSubject.gender.female') }}</option>
                <option value="M">{{ t('addSubject.gender.male') }}</option>
                <option value="O">{{ t('addSubject.gender.other') }}</option>
                <option value="U">{{ t('addSubject.gender.unknown') }}</option>
              </SelectInput>
              <ErrorText v-if="fieldErrors.gender">{{ fieldErrors.gender }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="edit-yob">{{ t('addSubject.field.yearOfBirth') }}</FieldLabel>
              <TextInput
                id="edit-yob"
                v-model="form.yearOfBirth"
                inputmode="numeric"
                :placeholder="t('addSubject.placeholder.yearOfBirth')"
                :error="!!fieldErrors.yearOfBirth"
              />
              <ErrorText v-if="fieldErrors.yearOfBirth">{{ fieldErrors.yearOfBirth }}</ErrorText>
            </div>

            <p v-if="formError" class="col-span-2 text-xs text-rose-600">{{ formError }}</p>

            <div class="col-span-2 flex justify-end gap-2 pt-2">
              <button
                type="button"
                class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
                :disabled="isSaving"
                @click="cancelEdit"
              >{{ t('common.cancel') }}</button>
              <button
                type="submit"
                class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:opacity-50"
                :disabled="isSaving"
              >{{ isSaving ? t('common.saving') : t('subjectDetail.edit.save') }}</button>
            </div>
          </form>
        </section>

        <!-- Events / casebook -->
        <section class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5">
          <div class="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
            <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
              {{ t('subjectDetail.eventsHeading') }}
            </h2>
            <div class="flex items-center gap-3">
              <button
                v-if="canScheduleEvent"
                type="button"
                class="px-2.5 py-1 text-xs border border-muw-blue-200 rounded-md bg-muw-blue-50 hover:bg-muw-blue-100 text-muw-blue inline-flex items-center gap-1.5"
                @click="scheduleDialogOpen = true"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <line x1="12" x2="12" y1="5" y2="19" />
                  <line x1="5" x2="19" y1="12" y2="12" />
                </svg>
                {{ t('subjectDetail.scheduleEvent') }}
              </button>
              <span class="text-xs text-slate-500">
                {{ subject.events.length }} {{ t('subjectDetail.eventsCount') }}
              </span>
            </div>
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
            <template v-for="ev in subject.events" :key="ev.eventDefinitionOid">
              <tr>
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
                <td class="px-5 py-2.5 text-right text-xs space-x-2">
                  <!-- Phase E A4: edit + cancel buttons. eventId is empty
                       when no study_event row exists yet (event-definition
                       slot is unscheduled) — hide both actions in that
                       case; the user would use the Schedule button instead. -->
                  <button
                    v-if="ev.eventId && canEditEv(ev.status)"
                    type="button"
                    class="text-muw-blue hover:underline"
                    @click="openEditEvent(ev)"
                  >{{ t('subjectDetail.event.edit') }}</button>
                  <button
                    v-if="ev.eventId && canCancelEv(ev.status)"
                    type="button"
                    class="text-rose-700 hover:underline"
                    @click="onCancelEvent(ev)"
                  >{{ t('subjectDetail.event.cancel') }}</button>
                  <!-- Phase E.6: link straight to the SPA's Event
                       Detail view (see EventDetailView.vue) so the
                       operator stays in-shell. v0 sent users into the
                       legacy /pages/EnterDataForStudyEvent JSP which
                       was jarring + often errored for non-Investigator
                       roles. eventId is empty until the event row is
                       actually scheduled — render nothing in that
                       case (Schedule button covers the path). -->
                  <RouterLink
                    v-if="ev.eventId"
                    :to="`/events/${ev.eventId}`"
                    class="text-muw-blue hover:underline"
                  >
                    {{ t('subjectDetail.openEvent') }}
                  </RouterLink>
                </td>
              </tr>

              <!-- Phase E A4: inline edit composer (only the row matching
                   the open editEvent state shows this). -->
              <tr v-if="editEvent && editEvent.eventId === ev.eventId" class="bg-slate-50">
                <td :colspan="6" class="px-5 py-3">
                  <div class="grid grid-cols-3 gap-3">
                    <div>
                      <FieldLabel for="edit-event-date">{{ t('subjectDetail.column.dateStart') }}</FieldLabel>
                      <TextInput
                        id="edit-event-date"
                        v-model="editEvent.dateStart"
                        placeholder="YYYY-MM-DD"
                      />
                    </div>
                    <div>
                      <FieldLabel for="edit-event-location">{{ t('subjectDetail.event.location') }}</FieldLabel>
                      <TextInput
                        id="edit-event-location"
                        v-model="editEvent.location"
                      />
                    </div>
                    <div>
                      <FieldLabel for="edit-event-status">{{ t('subjectDetail.column.status') }}</FieldLabel>
                      <SelectInput
                        id="edit-event-status"
                        v-model="editEvent.status"
                      >
                        <option value="scheduled">{{ t('subjectMatrix.status.scheduled') }}</option>
                        <option value="stopped">{{ t('subjectDetail.event.status.stopped') }}</option>
                        <option value="skipped">{{ t('subjectDetail.event.status.skipped') }}</option>
                      </SelectInput>
                    </div>
                  </div>
                  <ErrorText v-if="editEvent.fieldError" class="mt-2">{{ editEvent.fieldError }}</ErrorText>
                  <div class="flex justify-end gap-2 mt-3">
                    <button
                      type="button"
                      class="px-3 py-1.5 text-xs border border-slate-300 rounded-md hover:bg-slate-100"
                      :disabled="isSavingEvent"
                      @click="cancelEditEvent"
                    >{{ t('common.cancel') }}</button>
                    <button
                      type="button"
                      class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:opacity-50"
                      :disabled="isSavingEvent"
                      @click="submitEditEvent"
                    >{{ isSavingEvent ? t('common.saving') : t('subjectDetail.event.save') }}</button>
                  </div>
                </td>
              </tr>
            </template>
          </DenseTable>
        </section>

        <!-- Action row -->
        <div class="flex items-center justify-between flex-wrap gap-3">
          <RouterLink to="/subjects" class="text-xs text-slate-500 hover:text-slate-700">
            ← {{ t('subjectDetail.backToMatrix') }}
          </RouterLink>
          <div class="flex items-center gap-2">
            <!-- Phase E.6 P5 — per-subject data snapshot (ODM/CSV/PDF). -->
            <SubjectExportButton
              :study-oid="auth.user?.activeStudy?.oid ?? null"
              :subject-label="subject.id"
            />
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
            <!-- Phase E A3-lock: lock / unlock subject. DM/Admin only;
                 mutually exclusive based on current locked state. -->
            <button
              v-if="canLock"
              type="button"
              class="px-3 py-2 text-xs border border-amber-300 rounded-md bg-amber-50 hover:bg-amber-100 text-amber-800 inline-flex items-center gap-1.5"
              @click="onLock"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <rect width="18" height="11" x="3" y="11" rx="2" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
              {{ t('subjectDetail.actions.lock') }}
            </button>
            <button
              v-if="canUnlock"
              type="button"
              class="px-3 py-2 text-xs border border-emerald-300 rounded-md bg-emerald-50 hover:bg-emerald-100 text-emerald-800 inline-flex items-center gap-1.5"
              @click="onUnlock"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <rect width="18" height="11" x="3" y="11" rx="2" />
                <path d="M7 11V7a5 5 0 0 1 9.9-1" />
              </svg>
              {{ t('subjectDetail.actions.unlock') }}
            </button>
            <!-- Phase E A3: soft-delete the subject. DM/Admin only;
                 hidden for everyone else. Backend cascades to nested
                 events + CRFs + item_data via AUTO_DELETED. -->
            <button
              v-if="canRemove"
              type="button"
              class="px-3 py-2 text-xs border border-rose-200 rounded-md bg-rose-50 hover:bg-rose-100 text-rose-700 inline-flex items-center gap-1.5"
              @click="onRemove"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6l-2 14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2L5 6" />
                <path d="M10 11v6M14 11v6" />
                <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
              </svg>
              {{ t('subjectDetail.actions.remove') }}
            </button>
          </div>
        </div>
      </template>
    </main>

    <!-- Phase E.6 — Schedule-event dialog (mounted regardless of
         data-load state so the open binding can stay simple). -->
    <ScheduleEventDialog
      v-if="subject && canScheduleEvent"
      v-model:open="scheduleDialogOpen"
      :subject-id="subject.id"
      :study-oid="activeStudyOid"
      @scheduled="onEventScheduled"
    />
  </div>
</template>

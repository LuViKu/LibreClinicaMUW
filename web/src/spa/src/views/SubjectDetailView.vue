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

import { useSubjectsStore } from '@/stores/subjects'
import { useAuthStore } from '@/stores/auth'
import type { EventStatus, Gender } from '@/types/subject'
import { canManageSubjectLifecycle, canEditSubject } from '@/types/subject'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const subjects = useSubjectsStore()
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
  </div>
</template>

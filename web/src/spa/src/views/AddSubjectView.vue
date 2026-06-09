<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'
import StatusPill from '@/components/StatusPill.vue'
import PatientMatchDialog from '@/components/PatientMatchDialog.vue'

import {
  useSubjectsStore,
  validateAddSubject,
  AddSubjectValidationError,
  type AddSubjectError,
  type AddSubjectErrorField,
  type AddSubjectInput,
  type SubjectMatchCandidate,
} from '@/stores/subjects'
import { useAuthStore } from '@/stores/auth'
import type { Gender, StudyEye } from '@/types/subject'

const { t } = useI18n()
const router = useRouter()
const subjects = useSubjectsStore()
const auth = useAuthStore()

const todayIso = computed(() => new Date().toISOString().slice(0, 10))

const form = reactive<AddSubjectInput>({
  id: '',
  secondaryId: '',
  // Site context is derived from the session-bound active study.
  // The backend infers site from the session and ignores siteOid /
  // siteLabel in the body; these mirror auth.user.activeStudy for
  // breadcrumb display only. Empty defaults are replaced when
  // activeStudy resolves.
  siteOid: auth.user?.activeStudy?.oid ?? '',
  siteLabel: auth.user?.activeStudy?.name ?? '',
  gender: '' as Gender, // empty until user picks
  yearOfBirth: null,
  groupLabel: null, // retained for type compat; UI dropdown removed (no real group_class wiring)
  enrolledOn: todayIso.value,
  // Phase E.6 Tier 1 — ophthalmology domain fields. Both optional;
  // null keeps non-ophth studies free of forced eye scope.
  studyEye: null,
  screeningDate: '',
  // Phase E.6 retrospective-backfill — PHI triplet for dedup.
  firstName: '',
  lastName: '',
  dateOfBirth: '',
  acknowledgeMatchSubjectId: null,
})

const submitAttempted = ref(false)
const serverError = ref<string | null>(null)
// Once the server's authoritative error list arrives, mirror it here.
// When populated this replaces the client-side `liveErrors` source
// (server-authoritative — DR-008). Cleared on the next submit attempt
// or whenever the user edits any field.
const serverFieldErrors = ref<AddSubjectError[] | null>(null)

const liveErrors = computed(() => validateAddSubject(form, subjects.rows, { today: todayIso.value }))

/* ----------------------------------------------------------------- */
/* Phase E.6 — live Study-Subject-ID availability check (label-taken)*/
/*                                                                    */
/* Debounced watch on form.id fires the backend's check-label preflight*/
/* — surfaces "already taken" as an inline error before the operator  */
/* clicks submit. The submit-time backend validation stays as the     */
/* authoritative gate; the live check is purely UX.                  */
/* ----------------------------------------------------------------- */

const labelTakenError = ref<string | null>(null)
const labelCheckDebounce = ref<number | null>(null)

function maybeCheckLabel() {
  if (labelCheckDebounce.value !== null) {
    window.clearTimeout(labelCheckDebounce.value)
    labelCheckDebounce.value = null
  }
  const value = form.id.trim()
  if (value === '') {
    labelTakenError.value = null
    return
  }
  // Reset between debounce window and the actual check so the inline
  // error doesn't flash stale state during typing.
  labelTakenError.value = null
  labelCheckDebounce.value = window.setTimeout(() => {
    void runLabelCheck(value)
  }, 350)
}

async function runLabelCheck(value: string) {
  try {
    const result = await subjects.checkLabelAvailability(value)
    // Drop the result if the operator has typed something else since.
    if (form.id.trim() !== value) return
    if (!result.available) {
      labelTakenError.value = t('addSubject.error.labelTaken', { id: value })
    } else {
      labelTakenError.value = null
    }
  } catch {
    // Backend hiccup — clear the inline marker; the submit-time
    // check is the authoritative gate.
    labelTakenError.value = null
  }
}

watch(() => form.id, maybeCheckLabel)

function errorFor(field: AddSubjectErrorField): string | null {
  // Server-side errors take precedence (authoritative): if the latest
  // submit returned a structured 400, surface those per-field messages.
  if (serverFieldErrors.value && serverFieldErrors.value.length > 0) {
    const fromServer = serverFieldErrors.value.find((e) => e.field === field)
    if (fromServer) return fromServer.message
  }
  // Live label-taken check rides on the `id` field error slot so the
  // existing red-ring + ErrorText wiring renders it for free.
  if (field === 'id' && labelTakenError.value) return labelTakenError.value
  if (!submitAttempted.value) return null
  return liveErrors.value.find((e) => e.field === field)?.message ?? null
}

function setGender(value: Gender) {
  form.gender = value
  // User picked a gender — wipe any stale server-side gender error so the
  // red ring clears immediately rather than waiting for the next submit.
  clearServerErrorFor('gender')
}

/**
 * Phase E.6 Tier 1 — translate the empty-string select value into a
 * proper `null` so the StudyEye union stays narrow ('OD' | 'OS' | 'OU').
 */
function onStudyEyeChange(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  form.studyEye = v === '' ? null : (v as StudyEye)
}

function clearServerErrorFor(field: AddSubjectErrorField) {
  if (!serverFieldErrors.value) return
  serverFieldErrors.value = serverFieldErrors.value.filter((e) => e.field !== field)
  if (serverFieldErrors.value.length === 0) serverFieldErrors.value = null
}

/* -------------------------------------------------------------- */
/* Phase E.6 retrospective-backfill — match-preflight + dialog.   */
/*                                                                 */
/* Fires once the operator has filled all three of first/last/DoB. */
/* If the backend returns at least one candidate, the dialog opens */
/* and the form's submit is gated until the operator either picks  */
/* "Use existing" (route to existing subject's detail) or "Create  */
/* new anyway" (which records the acknowledgement on the form so   */
/* the backend's audit log can see the operator chose to override).*/
/* -------------------------------------------------------------- */

const matchCandidates = ref<SubjectMatchCandidate[]>([])
const matchDialogOpen = ref(false)
const isPreflighting = ref(false)
const preflightDebounce = ref<number | null>(null)

/**
 * Debounced preflight — when all three PHI fields are populated, fire
 * the lookup. The 350ms window keeps keypress-by-keypress noise off
 * the wire without making the UX feel laggy.
 */
function maybePreflight() {
  if (preflightDebounce.value !== null) {
    window.clearTimeout(preflightDebounce.value)
    preflightDebounce.value = null
  }
  const first = (form.firstName ?? '').trim()
  const last = (form.lastName ?? '').trim()
  const dob = (form.dateOfBirth ?? '').trim()
  if (!first || !last || !/^\d{4}-\d{2}-\d{2}$/.test(dob)) {
    matchCandidates.value = []
    matchDialogOpen.value = false
    return
  }
  // The operator may already have acknowledged; if they edit the
  // triplet, drop the acknowledgement so the next match is fresh.
  form.acknowledgeMatchSubjectId = null
  preflightDebounce.value = window.setTimeout(() => {
    void runPreflight(first, last, dob)
  }, 350)
}

async function runPreflight(first: string, last: string, dob: string) {
  isPreflighting.value = true
  try {
    const candidates = await subjects.preflightMatch(first, last, dob)
    matchCandidates.value = candidates
    matchDialogOpen.value = candidates.length > 0
  } catch {
    // Preflight is best-effort — never block enrolment on a 5xx.
    matchCandidates.value = []
    matchDialogOpen.value = false
  } finally {
    isPreflighting.value = false
  }
}

watch(() => form.firstName, maybePreflight)
watch(() => form.lastName, maybePreflight)
watch(() => form.dateOfBirth, maybePreflight)

function onMatchLink(candidate: SubjectMatchCandidate) {
  matchDialogOpen.value = false
  if (candidate.uniqueIdentifier) {
    router.push({ path: `/subjects/${encodeURIComponent(candidate.uniqueIdentifier)}` })
  }
}

function onMatchCreateNew() {
  // Echo the first candidate's id back on submit so the backend audit
  // captures which match the operator overrode. A multi-candidate
  // override only echoes the topmost — the dialog already lists them.
  form.acknowledgeMatchSubjectId = matchCandidates.value[0]?.subjectId ?? null
  matchDialogOpen.value = false
}

function onMatchCancel() {
  matchDialogOpen.value = false
}

async function submit(redirect: 'matrix' | 'addNext' | 'schedule') {
  submitAttempted.value = true
  serverError.value = null
  // Re-running submit invalidates any stale server-side errors.
  serverFieldErrors.value = null
  if (liveErrors.value.length > 0) return
  // Phase E.6 — short-circuit the submit when the live label check
  // has already surfaced a collision. The backend submit-time check
  // still owns the authoritative gate (a transient backend hiccup
  // would clear the inline marker but the 400 path catches it), but
  // surfacing the same field error inline saves the operator a
  // failed POST round trip.
  if (labelTakenError.value) return
  // Block the submit if a match dialog is open and the operator
  // hasn't acknowledged.
  if (matchCandidates.value.length > 0 && form.acknowledgeMatchSubjectId == null) {
    matchDialogOpen.value = true
    return
  }

  try {
    const subject = await subjects.add({ ...form })
    if (redirect === 'matrix') {
      router.push({ name: 'subject-matrix' })
    } else if (redirect === 'addNext') {
      // Reset for next subject; preserve site context.
      form.id = ''
      form.secondaryId = ''
      form.gender = '' as Gender
      form.yearOfBirth = null
      form.groupLabel = null
      form.enrolledOn = todayIso.value
      // Phase E.6 Tier 1 — reset ophth fields too.
      form.studyEye = null
      form.screeningDate = ''
      submitAttempted.value = false
    } else if (redirect === 'schedule') {
      router.push({ path: `/subjects/${encodeURIComponent(subject.id)}` })
    }
  } catch (err) {
    if (err instanceof AddSubjectValidationError) {
      // Server-authoritative: replace any client-side error state with
      // the server's list so the user sees exactly what the backend
      // rejected (e.g. duplicate ID at this site — the SPA's local
      // check only catches in-memory dupes; the server is the truth).
      serverFieldErrors.value = err.errors.length > 0 ? err.errors : null
      return
    }
    serverError.value = err instanceof Error ? err.message : 'Unknown server error'
  }
}

const genderOptions: { code: Gender; label: () => string }[] = [
  { code: 'F', label: () => t('addSubject.gender.female') },
  { code: 'M', label: () => t('addSubject.gender.male') },
  { code: 'O', label: () => t('addSubject.gender.other') },
  { code: 'U', label: () => t('addSubject.gender.unknown') },
]

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
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <rect width="18" height="18" x="3" y="3" rx="2" />
          <path d="M3 9h18M9 21V9" />
        </svg>
        {{ t('nav.subjectMatrix') }}
      </RouterLink>

      <RouterLink
        to="/subjects/new"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium"
        aria-current="page"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <circle cx="12" cy="8" r="5" />
          <path d="M20 21a8 8 0 1 0-16 0" />
          <path d="M19 16v6M22 19h-6" />
        </svg>
        {{ t('nav.addSubject') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-3xl px-8 py-8">
      <div class="mb-6">
        <div class="text-xs text-slate-500 mb-1">{{ form.siteLabel }} · {{ t('addSubject.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('addSubject.title') }}</h1>
        <p class="text-slate-500 text-xs mt-1 leading-relaxed">{{ t('addSubject.intro') }}</p>
      </div>

      <form
        class="bg-white border border-slate-200 rounded-muw p-6 space-y-6"
        novalidate
        @submit.prevent="submit('matrix')"
      >
        <!-- Identification section -->
        <section>
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
            {{ t('addSubject.section.identification') }}
          </h2>

          <div class="grid grid-cols-2 gap-x-6 gap-y-4">
            <div>
              <FieldLabel for="subject-id" required>{{ t('addSubject.field.subjectId') }}</FieldLabel>
              <TextInput
                id="subject-id"
                v-model="form.id"
                :placeholder="t('addSubject.placeholder.subjectId')"
                :error="errorFor('id') != null"
                autocomplete="off"
              />
              <HelperText>{{ t('addSubject.helper.subjectId') }}</HelperText>
              <ErrorText v-if="errorFor('id')">{{ errorFor('id') }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="secondary-id">{{ t('addSubject.field.secondaryId') }}</FieldLabel>
              <TextInput
                id="secondary-id"
                v-model="form.secondaryId"
                :placeholder="t('addSubject.placeholder.secondaryId')"
                :error="errorFor('secondaryId') != null"
                autocomplete="off"
              />
              <p class="mt-1 text-[11px] text-rose-600 flex items-start gap-1">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
                  <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                  <line x1="12" x2="12" y1="9" y2="13" />
                  <line x1="12" x2="12.01" y1="17" y2="17" />
                </svg>
                {{ t('addSubject.phiWarning') }}
              </p>
              <ErrorText v-if="errorFor('secondaryId')">{{ errorFor('secondaryId') }}</ErrorText>
            </div>
          </div>
        </section>

        <hr class="border-slate-200" />

        <!-- Enrolment section -->
        <section>
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
            {{ t('addSubject.section.enrolment') }}
          </h2>

          <div class="grid grid-cols-2 gap-x-6 gap-y-4">
            <div>
              <FieldLabel for="enrolled-on" required>{{ t('addSubject.field.enrolledOn') }}</FieldLabel>
              <TextInput
                id="enrolled-on"
                v-model="form.enrolledOn"
                type="text"
                placeholder="YYYY-MM-DD"
                :error="errorFor('enrolledOn') != null"
                inputmode="numeric"
              />
              <ErrorText v-if="errorFor('enrolledOn')">{{ errorFor('enrolledOn') }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="gender-group" required>{{ t('addSubject.field.gender') }}</FieldLabel>
              <div
                id="gender-group"
                class="grid grid-cols-4 gap-2"
                role="radiogroup"
                :aria-label="t('addSubject.field.gender')"
                :aria-invalid="errorFor('gender') != null"
              >
                <label
                  v-for="opt in genderOptions"
                  :key="opt.code"
                  class="flex items-center justify-center gap-2 px-3 py-2 border rounded-md cursor-pointer text-xs font-medium transition-colors"
                  :class="form.gender === opt.code
                    ? 'border-muw-blue-200 bg-muw-blue-50 text-muw-blue'
                    : 'border-slate-300 hover:bg-slate-50 text-slate-700'"
                >
                  <input
                    type="radio"
                    name="gender"
                    class="sr-only"
                    :value="opt.code"
                    :checked="form.gender === opt.code"
                    @change="setGender(opt.code)"
                  />
                  <span>{{ opt.label() }}</span>
                </label>
              </div>
              <ErrorText v-if="errorFor('gender')">{{ errorFor('gender') }}</ErrorText>
            </div>

            <!-- Phase E.6 retrospective-backfill — PHI triplet. Both
                 names + the full DoB drive the cross-study dedup
                 match-preflight; the operator can still submit with
                 them blank for legacy non-retrospective flows. -->
            <div>
              <FieldLabel for="first-name">{{ t('addSubject.field.firstName') }}</FieldLabel>
              <TextInput
                id="first-name"
                v-model="form.firstName"
                :placeholder="t('addSubject.placeholder.firstName')"
                data-testid="add-subject-first-name"
              />
              <ErrorText v-if="errorFor('firstName')">{{ errorFor('firstName') }}</ErrorText>
            </div>
            <div>
              <FieldLabel for="last-name">{{ t('addSubject.field.lastName') }}</FieldLabel>
              <TextInput
                id="last-name"
                v-model="form.lastName"
                :placeholder="t('addSubject.placeholder.lastName')"
                data-testid="add-subject-last-name"
              />
              <ErrorText v-if="errorFor('lastName')">{{ errorFor('lastName') }}</ErrorText>
            </div>
            <div>
              <FieldLabel for="date-of-birth">{{ t('addSubject.field.dateOfBirth') }}</FieldLabel>
              <TextInput
                id="date-of-birth"
                v-model="form.dateOfBirth"
                type="date"
                :max="todayIso"
                data-testid="add-subject-date-of-birth"
              />
              <HelperText>{{ t('addSubject.helper.dateOfBirth') }}</HelperText>
              <ErrorText v-if="errorFor('dateOfBirth')">{{ errorFor('dateOfBirth') }}</ErrorText>
            </div>

            <!-- Group assignment dropdown is deliberately omitted.
                 The historical hardcoded "Arm A / Arm B" stub
                 (pre-Phase-E.6) never read from the active study's
                 actual group_class definitions, and MUW's iAMD/GA
                 cohorts are observational — no randomisation arm.
                 The proper group_class-driven UI surfaces on the
                 SubjectDetailView per-group picker for studies that
                 declare groups; the AddSubject form posts
                 groupAssignments=null which the backend accepts. -->
          </div>
        </section>

        <hr class="border-slate-200" />

        <!-- Ophthalmology section (Phase E.6 Tier 1).
             Both fields optional — non-ophth deployments leave them
             blank and the form posts null to the backend. The studyEye
             value drives the matrix's at-a-glance eye column and the
             per-visit CRF rendering (the OPHTH_VISIT template can be
             rendered as one-eye when the scope is OD or OS). -->
        <section>
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
            Ophthalmology
          </h2>

          <div class="grid grid-cols-2 gap-x-6 gap-y-4">
            <div>
              <FieldLabel for="study-eye">{{ t('ophth.studyEye.label') }}</FieldLabel>
              <!-- Native select for null-aware modelling: the value
                   attribute on the empty option is the empty string,
                   and we project ''→null at the v-model layer so the
                   bean carries `null` not an empty StudyEye literal.
                   The SelectInput primitive's typed string emit doesn't
                   model `null` cleanly, so we drop down here. -->
              <select
                id="study-eye"
                :value="form.studyEye ?? ''"
                class="w-full px-3 py-2 rounded-md text-sm border border-slate-300 bg-white focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus appearance-none cursor-pointer pr-8"
                @change="onStudyEyeChange"
              >
                <option value="">{{ t('ophth.studyEye.notSet') }}</option>
                <option value="OD">{{ t('ophth.studyEye.od') }}</option>
                <option value="OS">{{ t('ophth.studyEye.os') }}</option>
                <option value="OU">{{ t('ophth.studyEye.ou') }}</option>
              </select>
              <HelperText>{{ t('ophth.studyEye.helper') }}</HelperText>
            </div>

            <div>
              <FieldLabel for="screening-date">{{ t('ophth.screeningDate.label') }}</FieldLabel>
              <TextInput
                id="screening-date"
                v-model="form.screeningDate"
                type="date"
                placeholder="YYYY-MM-DD"
                autocomplete="off"
              />
              <HelperText>{{ t('ophth.screeningDate.helper') }}</HelperText>
            </div>
          </div>
        </section>

        <hr class="border-slate-200" />

        <!-- Live preview pill -->
        <div class="flex items-center gap-2 text-xs">
          <span class="text-slate-500">{{ t('addSubject.preview') }}</span>
          <StatusPill variant="info">
            {{ form.id || '—' }} · {{ form.gender || '?' }} · {{ form.enrolledOn || '—' }}
          </StatusPill>
        </div>

        <!-- Server error region -->
        <div
          v-if="serverError"
          class="rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800"
          role="alert"
        >
          {{ serverError }}
        </div>

        <!-- Save action row -->
        <div class="flex items-center justify-between pt-1">
          <RouterLink to="/subjects" class="text-xs text-slate-500 hover:text-slate-700">
            {{ t('addSubject.cancelLink') }}
          </RouterLink>

          <div class="flex items-center gap-2">
            <button
              type="button"
              class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
              :disabled="subjects.isLoading"
              @click="submit('addNext')"
            >
              {{ t('addSubject.action.saveAndAddNext') }}
            </button>
            <button
              type="submit"
              class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
              :disabled="subjects.isLoading"
            >
              {{ t('addSubject.action.saveAndFinish') }}
            </button>
            <button
              type="button"
              class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5 font-medium"
              :disabled="subjects.isLoading"
              @click="submit('schedule')"
            >
              {{ t('addSubject.action.saveAndSchedule') }}
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </button>
          </div>
        </div>
      </form>

      <div class="mt-4 rounded-md bg-muw-blue-50 border border-muw-blue-100 px-4 py-3 text-xs text-muw-blue-900 flex items-start gap-2.5">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <path d="M12 16v-4M12 8h.01" />
        </svg>
        <p class="leading-relaxed">{{ t('addSubject.modesHelp') }}</p>
      </div>
    </main>

    <!-- Phase E.6 retrospective-backfill — match-preflight prompt. -->
    <PatientMatchDialog
      :open="matchDialogOpen"
      :candidates="matchCandidates"
      @link="onMatchLink"
      @create-new="onMatchCreateNew"
      @cancel="onMatchCancel"
    />
  </div>
</template>

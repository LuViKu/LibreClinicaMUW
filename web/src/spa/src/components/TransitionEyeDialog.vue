<script setup lang="ts">
/**
 * Phase E.6 — Per-eye cohort transition dialog (iAMD → GA).
 *
 * <p>Modal that captures operator intent when moving a single eye (OD
 * or OS) of an observational-cohort subject from one study to another
 * — most commonly the iAMD → GA hand-off when an eye crosses the
 * geographic-atrophy threshold mid-follow-up. The contralateral eye
 * stays in the source cohort, so the SubjectDetailView opens this
 * dialog once per eye rather than promoting the whole subject.
 *
 * <p>2026-06-10 — new-enrollment branch wired. When the operator picks
 * a target study where the subject is NOT yet enrolled, a mandatory
 * "Subject-ID in der Zielstudie" input appears, with the same
 * debounced uniqueness check pattern as AddSubjectView's label check.
 * When the subject IS already enrolled in the target, the input is
 * hidden + replaced by a one-liner so the operator knows the transition
 * lands in the existing target row. Both flavours emit the same
 * TransitionEyeRequest shape — the new-enrollment branch sets
 * {@code targetLabel}, the upgrade branch leaves it unset.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from '@/components/FieldLabel.vue'
import SelectInput from '@/components/SelectInput.vue'
import TextInput from '@/components/TextInput.vue'
import DateInput from '@/components/DateInput.vue'
import ErrorText from '@/components/ErrorText.vue'
import { useSubjectsStore } from '@/stores/subjects'
import type {
  StudyOption,
  TransitionEyeRequest,
  TransitionPreflight,
} from '@/types/subject'

interface Props {
  /** Source subject label, e.g. "M-001". Rendered into the heading. */
  subjectLabel: string
  /** Which eye is being moved. Rendered into the heading. */
  eye: 'OD' | 'OS'
  /**
   * Active study OID. Used for display only — the parent has already
   * filtered it out of {@code availableStudies}.
   */
  currentStudyOid: string
  /**
   * Candidate target studies. Parent must NOT include the current
   * study — this dialog does not filter.
   */
  availableStudies: StudyOption[]
  /** Controls visibility (v-if pattern from BuildStudyView). */
  open: boolean
  /**
   * In-flight POST indicator. The parent owns the request lifecycle;
   * the dialog renders a spinner + disables the confirm button while
   * this is true so the operator can't double-fire the submit.
   */
  isSubmitting?: boolean
}

const props = withDefaults(defineProps<Props>(), { isSubmitting: false })

const emit = defineEmits<{
  submit: [payload: TransitionEyeRequest]
  cancel: []
}>()

const { t } = useI18n()
const subjects = useSubjectsStore()

const targetStudyOid = ref('')
const targetLabel = ref('')
/**
 * Phase E.6 follow-up 2026-06-10 — track whether the operator has
 * actually typed into the targetLabel field, so we can prefill the
 * field with the target study's "{uniqueIdentifier}-" prefix on first
 * reveal without ever clobbering an edit they've already made. Flips
 * to true on the input's @input event (only fires for real DOM input,
 * not for programmatic assignment), and is reset by resetForm() when
 * the dialog is closed/reopened.
 */
const userTouchedTargetLabel = ref(false)
const reason = ref('')
const transitionedAt = ref('')
const submitted = ref(false)

const trimmedReason = computed(() => reason.value.trim())
const trimmedTargetLabel = computed(() => targetLabel.value.trim())
const trimmedTransitionedAt = computed(() => transitionedAt.value.trim())

const reasonInvalid = computed(() => submitted.value && trimmedReason.value === '')

// Pre-launch retrospective backfill: the operator pastes a date from
// paper records. Default to today (prospective case). Reject obviously
// junk strings + any future date so the audit row never claims a
// hand-off that hasn't happened. The backend re-validates.
function todayIso(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const transitionedAtInvalid = computed(() => {
  if (!submitted.value) return false
  const v = trimmedTransitionedAt.value
  if (v === '') return false
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return true
  return v > todayIso()
})

/* ----------------------------------------------------------------- */
/* 2026-06-10 — branched UI: alreadyEnrolled vs new-enrollment       */
/*                                                                    */
/* preflightState carries the most-recent server answer for the      */
/* picked target study (refreshed on every study change AND on every */
/* debounced targetLabel keystroke). When alreadyEnrolled=true, the  */
/* dialog hides the label input + shows an info line. When false,    */
/* the input is mandatory + bound to a live uniqueness check.        */
/* ----------------------------------------------------------------- */

const preflightState = ref<TransitionPreflight | null>(null)
const preflightLoading = ref(false)
// In-flight token used to drop stale preflight responses when the
// operator switches studies / types more characters before the prior
// request resolves.
const preflightToken = ref(0)
const labelCheckDebounce = ref<number | null>(null)

const targetStudyName = computed(() => {
  const m = props.availableStudies.find((s) => s.oid === targetStudyOid.value)
  return m ? m.name : ''
})

const showNewEnrollmentPanel = computed(() => {
  if (targetStudyOid.value === '') return false
  if (preflightState.value === null) return false
  return preflightState.value.alreadyEnrolled === false
})

const showAlreadyEnrolledInfo = computed(() => {
  if (targetStudyOid.value === '') return false
  if (preflightState.value === null) return false
  return preflightState.value.alreadyEnrolled === true
})

const targetLabelRequired = computed(
  () => submitted.value && showNewEnrollmentPanel.value && trimmedTargetLabel.value === '',
)

const targetLabelTaken = computed(() => {
  if (!showNewEnrollmentPanel.value) return false
  if (trimmedTargetLabel.value === '') return false
  if (preflightState.value === null) return false
  return preflightState.value.labelAvailable === false
})

const targetLabelAvailable = computed(() => {
  if (!showNewEnrollmentPanel.value) return false
  if (trimmedTargetLabel.value === '') return false
  if (preflightLoading.value) return false
  if (preflightState.value === null) return false
  return preflightState.value.labelAvailable === true
})

const canSubmit = computed(() => {
  if (targetStudyOid.value === '') return false
  if (trimmedReason.value === '') return false
  if (
    trimmedTransitionedAt.value !== ''
    && (!/^\d{4}-\d{2}-\d{2}$/.test(trimmedTransitionedAt.value)
      || trimmedTransitionedAt.value > todayIso())
  ) {
    return false
  }
  // New-enrollment branch: targetLabel mandatory AND must be available.
  if (showNewEnrollmentPanel.value) {
    if (trimmedTargetLabel.value === '') return false
    if (preflightState.value === null) return false
    if (preflightState.value.labelAvailable === false) return false
  }
  return true
})

async function runPreflight(candidateLabel: string | null) {
  if (props.subjectLabel === '' || targetStudyOid.value === '') {
    preflightState.value = null
    return
  }
  const token = ++preflightToken.value
  preflightLoading.value = true
  try {
    const result = await subjects.transitionPreflight(
      props.subjectLabel,
      props.eye,
      targetStudyOid.value,
      candidateLabel,
    )
    if (preflightToken.value !== token) return // stale
    preflightState.value = result
  } catch {
    // Backend hiccup — drop the local state so the submit-time check
    // remains the authoritative gate. The dialog will fall back to
    // the legacy "no preflight" behaviour (submit allowed with any
    // targetLabel; backend gates).
    if (preflightToken.value === token) {
      preflightState.value = null
    }
  } finally {
    if (preflightToken.value === token) {
      preflightLoading.value = false
    }
  }
}

function scheduleLabelCheck() {
  if (labelCheckDebounce.value !== null) {
    window.clearTimeout(labelCheckDebounce.value)
    labelCheckDebounce.value = null
  }
  if (!showNewEnrollmentPanel.value) return
  const value = trimmedTargetLabel.value
  if (value === '') return
  labelCheckDebounce.value = window.setTimeout(() => {
    void runPreflight(value)
  }, 300)
}

watch(targetStudyOid, (next) => {
  // When the operator switches the target study, drop the stale
  // preflight + re-fire with no candidate label so the dialog can
  // surface the alreadyEnrolled branch correctly.
  preflightState.value = null
  if (next !== '') {
    void runPreflight(null)
  }
})

watch(targetLabel, () => {
  scheduleLabelCheck()
})

/**
 * Phase E.6 follow-up 2026-06-10 — prefill the targetLabel with the
 * institutional protocol short-code ({uniqueIdentifier}-) the moment
 * the new-enrollment panel is revealed AND the operator hasn't
 * already typed anything. This frees the operator from manually
 * retyping the prefix every time and matches the AddSubjectView
 * prefill pattern. If the operator clears the field or types over the
 * prefix, userTouchedTargetLabel locks the prefill out for the
 * lifetime of the dialog open.
 */
watch(
  [
    () => preflightState.value?.alreadyEnrolled,
    targetStudyOid,
  ],
  () => {
    if (userTouchedTargetLabel.value) return
    if (preflightState.value?.alreadyEnrolled !== false) return
    if (targetLabel.value !== '') return
    const study = props.availableStudies.find((s) => s.oid === targetStudyOid.value)
    const ident = study?.uniqueIdentifier?.trim()
    if (!ident) return
    targetLabel.value = `${ident}-`
  },
)

function resetForm() {
  targetStudyOid.value = ''
  targetLabel.value = ''
  userTouchedTargetLabel.value = false
  reason.value = ''
  transitionedAt.value = todayIso()
  submitted.value = false
  preflightState.value = null
  preflightLoading.value = false
  if (labelCheckDebounce.value !== null) {
    window.clearTimeout(labelCheckDebounce.value)
    labelCheckDebounce.value = null
  }
}

function onCancel() {
  emit('cancel')
}

function onSubmit() {
  submitted.value = true
  if (!canSubmit.value) return
  const payload: TransitionEyeRequest = {
    targetStudyOid: targetStudyOid.value,
    reason: trimmedReason.value,
  }
  // Only include targetLabel when the new-enrollment panel is active.
  // alreadyEnrolled=true reuses the existing target row.
  if (showNewEnrollmentPanel.value && trimmedTargetLabel.value !== '') {
    payload.targetLabel = trimmedTargetLabel.value
  }
  if (trimmedTransitionedAt.value !== '') {
    payload.transitionedAt = trimmedTransitionedAt.value
  }
  emit('submit', payload)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) {
    e.preventDefault()
    emit('cancel')
  }
}

function onBackdropClick() {
  emit('cancel')
}

// Wire/unwire the document-level Escape listener with the open state.
watch(
  () => props.open,
  (isOpen, wasOpen) => {
    if (typeof document === 'undefined') return
    if (isOpen && !wasOpen) {
      resetForm()
      document.addEventListener('keydown', onKeydown)
    } else if (!isOpen && wasOpen) {
      document.removeEventListener('keydown', onKeydown)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (typeof document === 'undefined') return
  document.removeEventListener('keydown', onKeydown)
  if (labelCheckDebounce.value !== null) {
    window.clearTimeout(labelCheckDebounce.value)
    labelCheckDebounce.value = null
  }
})
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-30 flex items-center justify-center bg-slate-900/30"
    role="dialog"
    aria-modal="true"
    aria-labelledby="transition-eye-dialog-title"
    data-testid="transition-eye-dialog"
    @click.self="onBackdropClick"
  >
    <div
      class="bg-white rounded-muw shadow-xl border border-slate-200 max-w-md w-full p-5"
      @click.stop
    >
      <h2
        id="transition-eye-dialog-title"
        class="text-sm font-semibold mb-3"
        data-testid="transition-eye-dialog-title"
      >
        {{ t('subjectDetail.eyeTransition.title', { eye, subjectLabel }) }}
      </h2>

      <form class="space-y-4" novalidate @submit.prevent="onSubmit">
        <div>
          <FieldLabel for="transition-eye-target-study" required>
            {{ t('subjectDetail.eyeTransition.field.targetStudy') }}
          </FieldLabel>
          <SelectInput
            id="transition-eye-target-study"
            v-model="targetStudyOid"
            data-testid="transition-eye-target-study"
          >
            <option value="" disabled>—</option>
            <option
              v-for="study in availableStudies"
              :key="study.oid"
              :value="study.oid"
            >
              {{ study.name }}
            </option>
          </SelectInput>
        </div>

        <!-- 2026-06-10 — new-enrollment branch: visible when the
             subject is NOT yet enrolled in the picked target study. -->
        <div
          v-if="showNewEnrollmentPanel"
          class="rounded-md border border-slate-200 bg-slate-50 p-3 space-y-2"
          data-testid="transition-eye-new-enrollment-panel"
        >
          <div class="text-xs font-medium text-slate-700">
            {{ t('subjectDetail.eyeTransition.newEnrollment.title', { study: targetStudyName }) }}
          </div>
          <FieldLabel for="transition-eye-target-label" required>
            {{ t('subjectDetail.eyeTransition.newEnrollment.targetLabel.label') }}
          </FieldLabel>
          <TextInput
            id="transition-eye-target-label"
            v-model="targetLabel"
            data-testid="transition-eye-target-label"
            :aria-invalid="(targetLabelRequired || targetLabelTaken) || undefined"
            @input="userTouchedTargetLabel = true"
          />
          <ErrorText v-if="targetLabelRequired">
            {{ t('subjectDetail.eyeTransition.newEnrollment.targetLabel.required') }}
          </ErrorText>
          <ErrorText v-else-if="targetLabelTaken">
            {{ t('subjectDetail.eyeTransition.newEnrollment.targetLabel.taken', { study: targetStudyName }) }}
          </ErrorText>
          <p
            v-else-if="targetLabelAvailable"
            class="text-xs text-emerald-700"
            data-testid="transition-eye-target-label-available"
          >
            {{ t('subjectDetail.eyeTransition.newEnrollment.targetLabel.available') }}
          </p>
        </div>

        <!-- 2026-06-10 — already-enrolled branch: visible when the
             subject already has a study_subject row in the target. -->
        <div
          v-if="showAlreadyEnrolledInfo"
          class="rounded-md border border-blue-200 bg-blue-50 p-3 text-xs text-blue-900"
          data-testid="transition-eye-already-enrolled-info"
        >
          {{
            t('subjectDetail.eyeTransition.alreadyEnrolled', {
              study: targetStudyName,
              label: preflightState?.existingTargetLabel ?? '',
            })
          }}
        </div>

        <div>
          <FieldLabel for="transition-eye-transitioned-at">
            {{ t('subjectDetail.eyeTransition.field.transitionedAt') }}
          </FieldLabel>
          <DateInput
            id="transition-eye-transitioned-at"
            v-model="transitionedAt"
            :max="todayIso()"
            :error="transitionedAtInvalid"
            data-testid="transition-eye-transitioned-at"
          />
          <ErrorText v-if="transitionedAtInvalid">
            {{ t('subjectDetail.eyeTransition.error.transitionedAtFuture') }}
          </ErrorText>
        </div>

        <div>
          <FieldLabel for="transition-eye-reason" required>
            {{ t('subjectDetail.eyeTransition.field.reason') }}
          </FieldLabel>
          <textarea
            id="transition-eye-reason"
            v-model="reason"
            rows="3"
            class="w-full text-xs px-2 py-1.5 border rounded-md focus:outline-none transition-colors muw-focus"
            :class="[
              reasonInvalid
                ? 'border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100'
                : 'border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100',
            ]"
            :aria-invalid="reasonInvalid || undefined"
            data-testid="transition-eye-reason"
          />
          <ErrorText v-if="reasonInvalid">
            {{ t('subjectDetail.eyeTransition.error.reasonRequired') }}
          </ErrorText>
        </div>

        <div class="flex items-center justify-end gap-2 pt-1">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            data-testid="transition-eye-cancel"
            @click="onCancel"
          >
            {{ t('subjectDetail.eyeTransition.action.cancel') }}
          </button>
          <button
            type="submit"
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-1.5"
            :disabled="!canSubmit || isSubmitting"
            :aria-busy="isSubmitting || undefined"
            data-testid="transition-eye-submit"
          >
            <svg
              v-if="isSubmitting"
              class="animate-spin h-3.5 w-3.5"
              viewBox="0 0 24 24"
              aria-hidden="true"
            >
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none" />
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
            </svg>
            {{ t('subjectDetail.eyeTransition.action.confirm') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

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
 * <p>The look-and-feel mirrors the status-change modal in
 * BuildStudyView (inline fixed-overlay panel) rather than the shared
 * {@code Modal} primitive, deliberately: the host view places this
 * inside its own context column and the harmonization PR will wire
 * the resulting payload into a backend POST. The shared `Modal`
 * teleports to body, which the operator-facing detail screen does not
 * want here — the dialog must stay anchored to the patient frame.
 *
 * <p>Types ({@code StudyOption}, {@code TransitionEyeRequest}) are
 * imported from the canonical shared module {@code @/types/subject};
 * the parallel-worktree stubs the dialog branch originally inlined
 * were replaced during the Phase E.6 integration merge.
 */
import { computed, onBeforeUnmount, watch } from 'vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from '@/components/FieldLabel.vue'
import SelectInput from '@/components/SelectInput.vue'
import TextInput from '@/components/TextInput.vue'
import ErrorText from '@/components/ErrorText.vue'
import type { StudyOption, TransitionEyeRequest } from '@/types/subject'

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

const targetStudyOid = ref('')
const targetLabel = ref('')
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

const canSubmit = computed(
  () =>
    targetStudyOid.value !== '' &&
    trimmedReason.value !== '' &&
    !(
      trimmedTransitionedAt.value !== '' &&
      (!/^\d{4}-\d{2}-\d{2}$/.test(trimmedTransitionedAt.value) ||
        trimmedTransitionedAt.value > todayIso())
    ),
)

function resetForm() {
  targetStudyOid.value = ''
  targetLabel.value = ''
  reason.value = ''
  transitionedAt.value = todayIso()
  submitted.value = false
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
  if (trimmedTargetLabel.value !== '') {
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

        <div>
          <FieldLabel for="transition-eye-target-label">
            {{ t('subjectDetail.eyeTransition.field.targetLabel') }}
          </FieldLabel>
          <TextInput
            id="transition-eye-target-label"
            v-model="targetLabel"
            placeholder="automatisch zugewiesen wenn leer"
            data-testid="transition-eye-target-label"
          />
        </div>

        <div>
          <FieldLabel for="transition-eye-transitioned-at">
            {{ t('subjectDetail.eyeTransition.field.transitionedAt') }}
          </FieldLabel>
          <TextInput
            id="transition-eye-transitioned-at"
            v-model="transitionedAt"
            type="date"
            :max="todayIso()"
            :aria-invalid="transitionedAtInvalid || undefined"
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

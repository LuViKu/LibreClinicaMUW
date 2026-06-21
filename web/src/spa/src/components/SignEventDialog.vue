<script setup lang="ts">
/**
 * 2026-06-21 user-feedback round 7 — per-visit electronic signature.
 *
 * <p>The whole-subject sign dialog (SignSubjectDialog) collects the
 * password + attestation and POSTs to
 * {@code /api/v1/subjects/{oid}/sign}. This dialog mirrors that
 * contract but scoped to a single study_event so an investigator
 * can attest one visit at a time. The wire payload + 401-on-bad-
 * password contract is the same.
 *
 * <p>The parent owns the submit lifecycle: this component emits
 * {@code submit} with {password, attestation}; the parent calls
 * the store's signEvent() and feeds the resulting error message
 * back via the {@code errorMessage} prop so it renders inline
 * inside the modal (not behind it — see TransitionEyeDialog for
 * the rationale).
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldLabel from './FieldLabel.vue'
import TextInput from './TextInput.vue'
import ErrorText from './ErrorText.vue'

interface Props {
  /** Visit label rendered into the heading (e.g. "V01 Inclusion"). */
  eventLabel: string
  /** Subject label rendered as context. */
  subjectLabel: string
  /** Modal open / closed. */
  open: boolean
  /** In-flight POST indicator. */
  isSubmitting?: boolean
  /** Parent-supplied error message rendered inline. */
  errorMessage?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  isSubmitting: false,
  errorMessage: null,
})
const emit = defineEmits<{
  submit: [payload: { password: string; attestation: boolean }]
  cancel: []
}>()

const { t } = useI18n()

const password = ref('')
const attestation = ref(false)
const showPassword = ref(false)
const submitted = ref(false)

const passwordInvalid = computed(() => submitted.value && password.value.trim().length === 0)
const attestationInvalid = computed(() => submitted.value && !attestation.value)
const canSubmit = computed(
  () => password.value.trim().length > 0 && attestation.value && !props.isSubmitting,
)

// Reset internal state every time the dialog re-opens so a stale
// password + attestation flag from a previous open doesn't leak.
watch(() => props.open, (next) => {
  if (next) {
    password.value = ''
    attestation.value = false
    showPassword.value = false
    submitted.value = false
  }
})

function onSubmit(): void {
  submitted.value = true
  if (!canSubmit.value) return
  emit('submit', { password: password.value, attestation: attestation.value })
}

function onCancel(): void {
  emit('cancel')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center px-4"
    role="dialog"
    aria-modal="true"
    data-testid="sign-event-dialog"
    @click.self="onCancel"
  >
    <div class="bg-white rounded-md shadow-xl max-w-md w-full p-5">
      <h2 class="text-base font-semibold text-slate-900 mb-1">
        {{ t('eventSign.title') }}
      </h2>
      <p class="text-xs text-slate-500 mb-4">
        {{ t('eventSign.subtitle', { event: eventLabel, subject: subjectLabel }) }}
      </p>

      <form class="space-y-4" novalidate @submit.prevent="onSubmit">
        <div>
          <FieldLabel for="sign-event-password" required>
            {{ t('eventSign.passwordLabel') }}
          </FieldLabel>
          <TextInput
            id="sign-event-password"
            v-model="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            :error="passwordInvalid"
          />
          <ErrorText v-if="passwordInvalid">
            {{ t('eventSign.passwordRequired') }}
          </ErrorText>
          <label class="mt-1.5 inline-flex items-center gap-1.5 text-[11px] text-slate-500 cursor-pointer">
            <input
              type="checkbox"
              :checked="showPassword"
              data-testid="sign-event-show-password"
              @change="showPassword = !showPassword"
            />
            {{ t('eventSign.showPassword') }}
          </label>
        </div>

        <label class="flex items-start gap-2 cursor-pointer">
          <input
            type="checkbox"
            class="mt-0.5"
            :checked="attestation"
            :aria-invalid="attestationInvalid || undefined"
            data-testid="sign-event-attestation"
            @change="attestation = !attestation"
          />
          <span class="text-xs text-slate-700 leading-relaxed">
            {{ t('eventSign.attestation') }}
          </span>
        </label>
        <ErrorText v-if="attestationInvalid">
          {{ t('eventSign.attestationRequired') }}
        </ErrorText>

        <div
          v-if="props.errorMessage"
          class="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800"
          role="alert"
          data-testid="sign-event-submit-error"
        >
          {{ props.errorMessage }}
        </div>

        <div class="flex items-center justify-end gap-2 pt-1">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            data-testid="sign-event-cancel"
            @click="onCancel"
          >
            {{ t('eventSign.cancel') }}
          </button>
          <button
            type="submit"
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-1.5"
            :disabled="!canSubmit"
            :aria-busy="isSubmitting || undefined"
            data-testid="sign-event-submit"
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
            {{ t('eventSign.confirm') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Phase E.6 — Forced password change.
 *
 * Restores parity with the legacy {@code ResetPassword.jsp} +
 * {@code SecureController.passwdTimeOut()} gate: every authenticated
 * request bounces here when {@code mustChangePassword=true}, and the
 * user can't leave until they submit a valid new credential.
 *
 * The view reads {@code auth.user.passwordChangeReason} to switch the
 * banner copy between the two legacy cases:
 *   - {@code first-login} — root or a freshly-invited user logging in
 *     for the first time (passwd_timestamp IS NULL);
 *   - {@code rotation} — an existing user whose passwd_timestamp is
 *     older than the {@code passwd_expiration_time} setting.
 *
 * On success the backend returns a refreshed MeDto with
 * {@code mustChangePassword=false}, the router guard releases, and we
 * push to `/`.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useAuthStore, PasswordChangeValidationError } from '@/stores/auth'
import type { PasswordChangeFieldError } from '@/types/auth'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const currentPassword = ref('')
const newPassword = ref('')
const newPasswordRepeat = ref('')

const isSubmitting = ref(false)
const fieldErrors = ref<Partial<Record<PasswordChangeFieldError['field'], string>>>({})
const submitError = ref<string | null>(null)

const reason = computed(() => auth.user?.passwordChangeReason ?? 'first-login')

const bannerTitle = computed(() =>
  reason.value === 'rotation'
    ? t('changePassword.banner.rotation.title')
    : t('changePassword.banner.firstLogin.title'),
)

const bannerBody = computed(() =>
  reason.value === 'rotation'
    ? t('changePassword.banner.rotation.body')
    : t('changePassword.banner.firstLogin.body'),
)

const canSubmit = computed(() =>
  currentPassword.value.length > 0
    && newPassword.value.length > 0
    && newPasswordRepeat.value.length > 0
    && newPassword.value === newPasswordRepeat.value
    && newPassword.value !== currentPassword.value,
)

async function submit() {
  if (!canSubmit.value || isSubmitting.value) return
  isSubmitting.value = true
  fieldErrors.value = {}
  submitError.value = null
  try {
    await auth.changePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
      newPasswordRepeat: newPasswordRepeat.value,
    })
    router.push({ name: 'home' })
  } catch (e) {
    if (e instanceof PasswordChangeValidationError) {
      const next: Partial<Record<PasswordChangeFieldError['field'], string>> = {}
      for (const err of e.errors) next[err.field] = err.message
      fieldErrors.value = next
    } else {
      submitError.value = auth.error ?? t('changePassword.errors.saveFailed')
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="min-h-[calc(100vh-3.5rem)] flex flex-col items-center justify-center px-6 py-10 bg-slate-50">
    <div class="w-full max-w-md">
      <div class="text-center mb-6">
        <h1 class="muw-display text-lg font-semibold tracking-tight text-muw-blue">
          {{ t('changePassword.title') }}
        </h1>
        <p class="text-xs text-slate-500 mt-1">
          {{ t('changePassword.subtitle', { username: auth.user?.username ?? '' }) }}
        </p>
      </div>

      <!-- Reason banner — copy switches on auth.user.passwordChangeReason -->
      <div
        class="mb-5 rounded-md border px-3 py-2.5 text-xs leading-relaxed"
        :class="reason === 'rotation'
          ? 'border-amber-200 bg-amber-50 text-amber-900'
          : 'border-muw-blue-100 bg-muw-blue-50 text-muw-blue-900'"
        role="status"
      >
        <p class="font-semibold mb-0.5">{{ bannerTitle }}</p>
        <p>{{ bannerBody }}</p>
      </div>

      <form class="space-y-4 rounded-muw border border-slate-200 bg-white p-6" @submit.prevent="submit">
        <div>
          <FieldLabel for="cp-current" required>
            {{ t('changePassword.field.currentPassword') }}
          </FieldLabel>
          <TextInput
            id="cp-current"
            v-model="currentPassword"
            type="password"
            autocomplete="current-password"
            :error="!!fieldErrors.currentPassword"
          />
          <ErrorText v-if="fieldErrors.currentPassword">
            {{ fieldErrors.currentPassword }}
          </ErrorText>
        </div>

        <div>
          <FieldLabel for="cp-new" required>
            {{ t('changePassword.field.newPassword') }}
          </FieldLabel>
          <TextInput
            id="cp-new"
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            :error="!!fieldErrors.newPassword"
          />
          <ErrorText v-if="fieldErrors.newPassword">
            {{ fieldErrors.newPassword }}
          </ErrorText>
          <p v-else class="mt-1 text-[11px] text-slate-500">
            {{ t('changePassword.field.newPasswordHelper') }}
          </p>
        </div>

        <div>
          <FieldLabel for="cp-repeat" required>
            {{ t('changePassword.field.newPasswordRepeat') }}
          </FieldLabel>
          <TextInput
            id="cp-repeat"
            v-model="newPasswordRepeat"
            type="password"
            autocomplete="new-password"
            :error="!!fieldErrors.newPasswordRepeat"
          />
          <ErrorText v-if="fieldErrors.newPasswordRepeat">
            {{ fieldErrors.newPasswordRepeat }}
          </ErrorText>
          <p
            v-else-if="newPasswordRepeat && newPassword !== newPasswordRepeat"
            class="mt-1 text-xs text-rose-700"
          >
            {{ t('changePassword.errors.repeatMismatch') }}
          </p>
        </div>

        <p
          v-if="submitError"
          class="text-xs text-rose-700 bg-rose-50 border border-rose-200 rounded-md px-3 py-2"
        >
          {{ submitError }}
        </p>

        <button
          type="submit"
          class="block w-full text-center px-4 py-2 text-sm bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 muw-focus"
          :disabled="!canSubmit || isSubmitting"
        >
          {{ isSubmitting
            ? t('changePassword.submitting')
            : t('changePassword.submit') }}
        </button>
      </form>

      <p class="mt-4 text-center text-[11px] text-slate-500">
        {{ t('changePassword.complianceTell') }}
      </p>
    </div>
  </div>
</template>

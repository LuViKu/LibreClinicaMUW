<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import Wizard from '@/components/Wizard.vue'
import type { WizardStep } from '@/components/Wizard.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useAuthStore, ProfileValidationError } from '@/stores/auth'
import type { ProfileFieldError } from '@/types/auth'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const step = ref(0)
const isSubmitting = ref(false)
const fieldErrors = ref<Partial<Record<ProfileFieldError['field'], string>>>({})
const submitError = ref<string | null>(null)

const steps = computed<WizardStep[]>(() => [
  { id: 'profile', title: t('firstLogin.step.confirmProfile'), clickable: true },
  { id: 'terms',   title: t('firstLogin.step.acceptTerms'),    clickable: profileComplete.value },
])

/* Form state — pre-fills from the SSO-supplied identity. */
const displayName = ref(auth.user?.displayName ?? '')
const locale = ref<'de-AT' | 'en'>('de-AT')
const timezone = ref<'Europe/Vienna' | 'UTC'>('Europe/Vienna')
const eSignatureAcknowledged = ref(true)

const profileComplete = computed(() => displayName.value.trim().length > 0)

/* Terms step. */
const acceptedTerms = ref(false)
const acceptedAuditing = ref(false)
const canFinish = computed(() => acceptedTerms.value && acceptedAuditing.value)

async function finish() {
  if (!auth.user || !canFinish.value || isSubmitting.value) return
  isSubmitting.value = true
  fieldErrors.value = {}
  submitError.value = null
  try {
    await auth.completeProfile({
      displayName: displayName.value.trim(),
      locale: locale.value,
      timezone: timezone.value,
    })
    router.push({ name: 'home' })
  } catch (e) {
    if (e instanceof ProfileValidationError) {
      const next: Partial<Record<ProfileFieldError['field'], string>> = {}
      for (const err of e.errors) next[err.field] = err.message
      fieldErrors.value = next
      step.value = 0
    } else {
      submitError.value = auth.error ?? t('firstLogin.errors.saveFailed')
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="min-h-[calc(100vh-3.5rem)] px-6 py-10 bg-slate-50">
    <div class="max-w-2xl mx-auto">
      <div class="mb-6">
        <div class="text-xs text-slate-500 mb-1">{{ t('firstLogin.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('firstLogin.title', { name: auth.user?.displayName ?? '' }) }}</h1>
        <p class="text-xs text-slate-500 mt-1 leading-relaxed">{{ t('firstLogin.intro') }}</p>
      </div>

      <Wizard v-model:step="step" :steps="steps">
        <template #default="{ currentStep }">
          <!-- Step 1: Confirm profile -->
          <section v-if="currentStep === 0" class="rounded-muw border border-slate-200 bg-white p-6 space-y-5">
            <!-- IdP-supplied attributes (read-only) -->
            <div class="rounded-md border border-slate-200 bg-slate-50 p-3 space-y-2.5">
              <div class="flex items-center justify-between text-[10px] uppercase tracking-wider">
                <span class="font-semibold text-slate-500">{{ t('firstLogin.fromIdp') }}</span>
                <StatusPill compact variant="neutral">{{ t('firstLogin.readOnly') }}</StatusPill>
              </div>
              <dl class="text-xs space-y-1.5" v-if="auth.user">
                <div class="grid grid-cols-3 gap-2"><dt class="text-slate-500">{{ t('firstLogin.field.email') }}</dt><dd class="col-span-2 font-mono text-slate-900">{{ auth.user.email }}</dd></div>
                <div class="grid grid-cols-3 gap-2"><dt class="text-slate-500">{{ t('firstLogin.field.role') }}</dt><dd class="col-span-2 text-slate-900">{{ t(`manageUsers.role.${auth.user.role}`) }}</dd></div>
                <div class="grid grid-cols-3 gap-2"><dt class="text-slate-500">{{ t('firstLogin.field.source') }}</dt><dd class="col-span-2 text-slate-900">{{ t(`manageUsers.auth.${auth.user.source}`) }}</dd></div>
                <div class="grid grid-cols-3 gap-2">
                  <dt class="text-slate-500">{{ t('firstLogin.field.mfa') }}</dt>
                  <dd class="col-span-2">
                    <StatusPill v-if="auth.user.mfaSatisfied" compact variant="success">{{ t('firstLogin.mfaVerified') }}</StatusPill>
                    <StatusPill v-else compact variant="warning">{{ t('firstLogin.mfaNotVerified') }}</StatusPill>
                  </dd>
                </div>
              </dl>
            </div>

            <div>
              <FieldLabel for="fl-display" required>{{ t('firstLogin.field.displayName') }}</FieldLabel>
              <TextInput id="fl-display" v-model="displayName" />
              <HelperText v-if="!fieldErrors.displayName">{{ t('firstLogin.helper.displayName') }}</HelperText>
              <p v-else class="mt-1 text-xs text-red-700">{{ fieldErrors.displayName }}</p>
            </div>

            <div class="grid grid-cols-2 gap-4">
              <div>
                <FieldLabel for="fl-locale">{{ t('firstLogin.field.locale') }}</FieldLabel>
                <SelectInput id="fl-locale" v-model="locale">
                  <option value="de-AT">Deutsch (Österreich)</option>
                  <option value="en">English</option>
                </SelectInput>
                <p v-if="fieldErrors.locale" class="mt-1 text-xs text-red-700">{{ fieldErrors.locale }}</p>
              </div>
              <div>
                <FieldLabel for="fl-tz">{{ t('firstLogin.field.timezone') }}</FieldLabel>
                <SelectInput id="fl-tz" v-model="timezone">
                  <option value="Europe/Vienna">Europe/Vienna (CEST)</option>
                  <option value="UTC">UTC</option>
                </SelectInput>
                <p v-if="fieldErrors.timezone" class="mt-1 text-xs text-red-700">{{ fieldErrors.timezone }}</p>
              </div>
            </div>

            <div class="rounded-md border border-slate-200 bg-white p-3 space-y-2">
              <div class="text-[10px] font-semibold uppercase tracking-wider text-slate-500">{{ t('firstLogin.eSignatureHeading') }}</div>
              <p class="text-[11px] text-slate-600 leading-relaxed">{{ t('firstLogin.eSignatureExplain') }}</p>
              <label class="flex items-start gap-2 text-xs text-slate-700">
                <input v-model="eSignatureAcknowledged" type="checkbox" class="mt-0.5 rounded text-muw-blue" />
                <span>{{ t('firstLogin.eSignatureAck') }}</span>
              </label>
            </div>

            <div class="flex items-center justify-end">
              <button
                class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
                :disabled="!profileComplete"
                @click="step = 1"
              >{{ t('common.next') }} →</button>
            </div>
          </section>

          <!-- Step 2: Accept terms -->
          <section v-else class="rounded-muw border border-slate-200 bg-white p-6 space-y-5">
            <h2 class="text-sm font-semibold">{{ t('firstLogin.terms.heading') }}</h2>
            <div class="rounded-md bg-slate-50 border border-slate-200 px-4 py-3 text-xs text-slate-700 leading-relaxed max-h-48 overflow-y-auto">
              <p>{{ t('firstLogin.terms.body1') }}</p>
              <p class="mt-2">{{ t('firstLogin.terms.body2') }}</p>
              <p class="mt-2">{{ t('firstLogin.terms.body3') }}</p>
            </div>

            <label class="flex items-start gap-2 text-xs text-slate-700">
              <input v-model="acceptedTerms" type="checkbox" class="mt-0.5 rounded text-muw-blue" />
              <span>{{ t('firstLogin.terms.ackTerms') }}</span>
            </label>
            <label class="flex items-start gap-2 text-xs text-slate-700">
              <input v-model="acceptedAuditing" type="checkbox" class="mt-0.5 rounded text-muw-blue" />
              <span>{{ t('firstLogin.terms.ackAuditing') }}</span>
            </label>

            <p v-if="submitError" class="text-xs text-red-700 bg-red-50 border border-red-200 rounded-md px-3 py-2">{{ submitError }}</p>

            <div class="flex items-center justify-between">
              <button class="text-xs text-slate-500 hover:text-slate-700" @click="step = 0">← {{ t('common.back') }}</button>
              <button
                class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
                :disabled="!canFinish || isSubmitting"
                @click="finish"
              >{{ isSubmitting ? t('common.saving') : t('firstLogin.terms.finishCta') }}</button>
            </div>
          </section>
        </template>
      </Wizard>
    </div>
  </div>
</template>

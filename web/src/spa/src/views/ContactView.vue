<script setup lang="ts">
/**
 * Phase E.8 Slice L2 (2026-06-20) — SPA replacement for /pages/Contact.
 *
 * Unauthenticated form for visitors who can't reach the institutional
 * admin directly. POSTs to /api/v1/contact (whitelisted in
 * SecurityConfig). Field-level errors come back as
 * ValidationErrorBody.fieldErrors[] and render under each input.
 *
 * Keep the layout intentionally close to LoginView so the unauthenticated
 * brand chrome stays consistent — same card / heading / muw-blue brand
 * lockup.
 */
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'

import { apiPost, ApiError, ApiNetworkError } from '@/api/client'

const { t } = useI18n()

const name = ref('')
const email = ref('')
const subject = ref('')
const message = ref('')

const submitting = ref(false)
const submitted = ref(false)
const formError = ref<string | null>(null)

// Per-field errors keyed on the field name returned by the backend.
const fieldErrors = ref<Record<string, string>>({})

const messageRemaining = computed(() => 5000 - message.value.length)

interface ContactResponse {
  delivered: boolean
}

interface ValidationErrorBody {
  message?: string
  errors?: Array<{ field: string; message: string }>
}

async function onSubmit() {
  if (submitting.value) return
  submitting.value = true
  formError.value = null
  fieldErrors.value = {}

  try {
    await apiPost<ContactResponse>('/pages/api/v1/contact', {
      name: name.value,
      email: email.value,
      subject: subject.value,
      message: message.value,
    })
    submitted.value = true
  } catch (err) {
    if (err instanceof ApiError) {
      const body = err.body as ValidationErrorBody | undefined
      if (err.status === 400 && body?.errors?.length) {
        const errs: Record<string, string> = {}
        for (const fe of body.errors) errs[fe.field] = fe.message
        fieldErrors.value = errs
        formError.value = body.message ?? t('contact.errorValidation')
      } else if (err.status === 503) {
        formError.value = body?.message ?? t('contact.errorUnavailable')
      } else {
        formError.value = body?.message ?? t('contact.errorGeneric')
      }
    } else if (err instanceof ApiNetworkError) {
      formError.value = t('contact.errorNetwork')
    } else {
      formError.value = t('contact.errorGeneric')
    }
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  name.value = ''
  email.value = ''
  subject.value = ''
  message.value = ''
  submitted.value = false
  formError.value = null
  fieldErrors.value = {}
}
</script>

<template>
  <div class="min-h-[calc(100vh-3.5rem)] flex flex-col items-center justify-center px-6 py-10 bg-slate-50">
    <div class="w-full max-w-lg">
      <div class="text-center mb-8">
        <div class="inline-flex items-center gap-2 mb-3">
          <svg class="w-10 h-10 text-muw-blue" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
            <path d="M2.6 16 C9 8.2 23 8.2 29.4 16 C23 23.8 9 23.8 2.6 16 Z" stroke-width="2.2" />
            <circle cx="16" cy="16" r="5" stroke-width="2.2" />
            <circle cx="16" cy="16" r="1.9" fill="#d96849" stroke="none" />
          </svg>
          <span class="muw-display text-xl font-semibold tracking-tight text-muw-blue">
            LibreClinica<em class="not-italic font-medium text-muw-coral-700 text-[0.7em] uppercase tracking-[0.08em] ml-1.5 align-middle">MUW</em>
          </span>
        </div>
        <p class="text-xs text-slate-500 leading-relaxed">{{ t('contact.brandLine') }}</p>
      </div>

      <div v-if="submitted" class="rounded-md bg-emerald-50 border border-emerald-200 px-4 py-4 text-sm text-emerald-900" role="status">
        <div class="flex items-start gap-3">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
            <path d="M22 11.08V12a10 10 0 11-5.93-9.14" />
            <polyline points="22 4 12 14.01 9 11.01" />
          </svg>
          <div>
            <p class="font-medium">{{ t('contact.successTitle') }}</p>
            <p class="text-xs mt-1 text-emerald-800">{{ t('contact.successBody') }}</p>
            <div class="mt-3 flex gap-2">
              <button type="button" class="px-3 py-1.5 text-xs border border-emerald-300 rounded bg-white hover:bg-emerald-50 text-emerald-900 muw-focus" @click="resetForm">
                {{ t('contact.sendAnother') }}
              </button>
              <RouterLink to="/login" class="px-3 py-1.5 text-xs border border-slate-300 rounded bg-white hover:bg-slate-50 text-slate-700 muw-focus">
                {{ t('contact.backToLogin') }}
              </RouterLink>
            </div>
          </div>
        </div>
      </div>

      <template v-else>
        <h1 class="text-lg font-semibold tracking-tight mb-1">{{ t('contact.title') }}</h1>
        <p class="text-xs text-slate-500 mb-5">{{ t('contact.subtitle') }}</p>

        <div v-if="formError" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800 flex items-start gap-2" role="alert">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" x2="12" y1="8" y2="12" />
            <line x1="12" x2="12.01" y1="16" y2="16" />
          </svg>
          <span>{{ formError }}</span>
        </div>

        <form class="space-y-4 bg-white rounded-md border border-slate-200 px-4 py-4" @submit.prevent="onSubmit">
          <div>
            <FieldLabel for="contact-name" required>{{ t('contact.name') }}</FieldLabel>
            <TextInput id="contact-name" v-model="name" autocomplete="name" :aria-invalid="!!fieldErrors.name" />
            <p v-if="fieldErrors.name" class="mt-1 text-[11px] text-rose-700">{{ fieldErrors.name }}</p>
          </div>
          <div>
            <FieldLabel for="contact-email" required>{{ t('contact.email') }}</FieldLabel>
            <TextInput id="contact-email" v-model="email" type="email" autocomplete="email" :aria-invalid="!!fieldErrors.email" />
            <p v-if="fieldErrors.email" class="mt-1 text-[11px] text-rose-700">{{ fieldErrors.email }}</p>
          </div>
          <div>
            <FieldLabel for="contact-subject" required>{{ t('contact.subject') }}</FieldLabel>
            <TextInput id="contact-subject" v-model="subject" :aria-invalid="!!fieldErrors.subject" />
            <p v-if="fieldErrors.subject" class="mt-1 text-[11px] text-rose-700">{{ fieldErrors.subject }}</p>
          </div>
          <div>
            <FieldLabel for="contact-message" required>{{ t('contact.message') }}</FieldLabel>
            <textarea
              id="contact-message"
              v-model="message"
              rows="6"
              maxlength="5000"
              class="block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm placeholder-slate-400 muw-focus"
              :aria-invalid="!!fieldErrors.message"
            ></textarea>
            <div class="mt-1 flex items-center justify-between">
              <p v-if="fieldErrors.message" class="text-[11px] text-rose-700">{{ fieldErrors.message }}</p>
              <p class="ml-auto text-[11px] text-slate-400">{{ messageRemaining }}</p>
            </div>
          </div>

          <div class="flex items-center gap-3 pt-1">
            <button
              type="submit"
              :disabled="submitting"
              class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium muw-focus disabled:opacity-60 disabled:cursor-not-allowed inline-flex items-center justify-center gap-2"
            >
              <svg v-if="submitting" class="h-3 w-3 animate-spin text-white" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle class="opacity-30" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-90" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"></path>
              </svg>
              <span>{{ submitting ? t('contact.sending') : t('contact.send') }}</span>
            </button>
            <RouterLink to="/login" class="text-[11px] text-slate-500 hover:text-slate-700">{{ t('contact.backToLogin') }}</RouterLink>
          </div>
        </form>

        <p class="mt-3 text-[11px] text-slate-400 leading-relaxed">{{ t('contact.privacyNote') }}</p>
      </template>
    </div>
  </div>
</template>

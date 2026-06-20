<script setup lang="ts">
/**
 * Phase E.8 Slice L3 (2026-06-20) — sysadmin password-policy view.
 *
 * GETs / PUTs /api/v1/admin/password-policy. Sysadmin-only. PUT echoes
 * the canonical persisted shape back so we can rebind the form without
 * a second round-trip.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'

import { apiGet, apiPut, ApiError } from '@/api/client'

const { t } = useI18n()

interface PasswordPolicy {
  requireLower: boolean
  requireUpper: boolean
  requireDigits: boolean
  requireSpecials: boolean
  minLength: number
  maxLength: number
  expirationDays: number
  changeRequiredOnFirstLogin: boolean
  specialsAlphabet: string
}

const data = ref<PasswordPolicy | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const success = ref(false)
const fieldErrors = ref<Record<string, string>>({})

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await apiGet<PasswordPolicy>('/pages/api/v1/admin/password-policy')
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminPasswordPolicy.loadFailed')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!data.value || saving.value) return
  saving.value = true
  error.value = null
  success.value = false
  fieldErrors.value = {}
  try {
    data.value = await apiPut<PasswordPolicy>('/pages/api/v1/admin/password-policy', { ...data.value })
    success.value = true
  } catch (err) {
    if (err instanceof ApiError && err.status === 400) {
      const body = err.body as { message?: string; errors?: { field: string; message: string }[] } | undefined
      if (body?.errors?.length) {
        const errs: Record<string, string> = {}
        for (const fe of body.errors) errs[fe.field] = fe.message
        fieldErrors.value = errs
      }
      error.value = body?.message ?? err.message
    } else if (err instanceof ApiError) {
      error.value = `${err.status}: ${err.message}`
    } else {
      error.value = t('adminPasswordPolicy.saveFailed')
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="max-w-2xl mx-auto px-6 py-6">
    <div class="flex items-baseline justify-between mb-4">
      <h1 class="text-base font-semibold tracking-tight">{{ t('adminPasswordPolicy.title') }}</h1>
    </div>

    <p class="text-xs text-slate-500 mb-4">{{ t('adminPasswordPolicy.subtitle') }}</p>

    <div v-if="error" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800" role="alert">{{ error }}</div>
    <div v-if="success" class="mb-3 rounded-md bg-emerald-50 border border-emerald-200 px-3 py-2 text-xs text-emerald-800" role="status">{{ t('adminPasswordPolicy.saved') }}</div>

    <form v-if="data" class="space-y-4 bg-white rounded-md border border-slate-200 px-4 py-4 text-xs" @submit.prevent="save">
      <fieldset class="space-y-2">
        <legend class="text-xs font-medium text-slate-700">{{ t('adminPasswordPolicy.classes') }}</legend>
        <label class="flex items-center gap-2"><input v-model="data.requireLower" type="checkbox" /> {{ t('adminPasswordPolicy.requireLower') }}</label>
        <label class="flex items-center gap-2"><input v-model="data.requireUpper" type="checkbox" /> {{ t('adminPasswordPolicy.requireUpper') }}</label>
        <label class="flex items-center gap-2"><input v-model="data.requireDigits" type="checkbox" /> {{ t('adminPasswordPolicy.requireDigits') }}</label>
        <label class="flex items-center gap-2"><input v-model="data.requireSpecials" type="checkbox" /> {{ t('adminPasswordPolicy.requireSpecials') }}<span class="text-slate-400 ml-1">({{ data.specialsAlphabet }})</span></label>
      </fieldset>

      <div class="grid grid-cols-3 gap-3">
        <div>
          <FieldLabel for="minLength" required>{{ t('adminPasswordPolicy.minLength') }}</FieldLabel>
          <TextInput id="minLength" v-model.number="data.minLength" type="number" :aria-invalid="!!fieldErrors.minLength" />
          <p v-if="fieldErrors.minLength" class="mt-1 text-[11px] text-rose-700">{{ fieldErrors.minLength }}</p>
        </div>
        <div>
          <FieldLabel for="maxLength" required>{{ t('adminPasswordPolicy.maxLength') }}</FieldLabel>
          <TextInput id="maxLength" v-model.number="data.maxLength" type="number" :aria-invalid="!!fieldErrors.maxLength" />
          <p v-if="fieldErrors.maxLength" class="mt-1 text-[11px] text-rose-700">{{ fieldErrors.maxLength }}</p>
        </div>
        <div>
          <FieldLabel for="expirationDays" required>{{ t('adminPasswordPolicy.expirationDays') }}</FieldLabel>
          <TextInput id="expirationDays" v-model.number="data.expirationDays" type="number" :aria-invalid="!!fieldErrors.expirationDays" />
          <p v-if="fieldErrors.expirationDays" class="mt-1 text-[11px] text-rose-700">{{ fieldErrors.expirationDays }}</p>
          <p class="mt-1 text-[10px] text-slate-400">{{ t('adminPasswordPolicy.zeroDaysNote') }}</p>
        </div>
      </div>

      <label class="flex items-center gap-2"><input v-model="data.changeRequiredOnFirstLogin" type="checkbox" /> {{ t('adminPasswordPolicy.changeRequiredOnFirstLogin') }}</label>

      <div class="flex items-center gap-3 pt-1">
        <button type="submit" :disabled="saving" class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium muw-focus disabled:opacity-60">
          {{ saving ? t('common.saving') : t('common.save') }}
        </button>
        <button type="button" :disabled="loading" class="px-3 py-2 text-xs border border-slate-300 rounded bg-white hover:bg-slate-50 text-slate-700 muw-focus" @click="load">
          {{ t('adminPasswordPolicy.discard') }}
        </button>
      </div>
    </form>
  </div>
</template>

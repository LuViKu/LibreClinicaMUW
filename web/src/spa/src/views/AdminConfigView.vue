<script setup lang="ts">
/**
 * Phase E.8 Slice L3 (2026-06-20) — sysadmin read-only config view.
 *
 * At MUW deployment-time config lives in env vars (per the production
 * scope memory). This view just surfaces what the running JVM actually
 * sees — useful for sysadmin diagnostics after a restart, NOT a write
 * surface.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { apiGet, ApiError } from '@/api/client'

const { t } = useI18n()

interface AdminConfig {
  defaultTimezone: string
  userLanguage: string | null
  userCountry: string | null
  fileEncoding: string | null
  osName: string | null
  osArch: string | null
  javaOpts: string | null
  retinalInferenceRemotePushUrl: string | null
  ssoEnabled: boolean
  readOnly: boolean
}

const data = ref<AdminConfig | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await apiGet<AdminConfig>('/pages/api/v1/admin/config')
  } catch (err) {
    error.value = err instanceof ApiError
      ? `${err.status}: ${err.message}`
      : t('adminConfig.loadFailed')
  } finally {
    loading.value = false
  }
}

function display(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return value
}

onMounted(load)
</script>

<template>
  <div class="max-w-2xl mx-auto px-6 py-6">
    <div class="flex items-baseline justify-between mb-4">
      <h1 class="text-base font-semibold tracking-tight">{{ t('adminConfig.title') }}</h1>
      <button type="button" class="px-3 py-1.5 border border-slate-300 rounded bg-white hover:bg-slate-50 text-xs muw-focus" :disabled="loading" @click="load">
        {{ loading ? t('common.loading') : t('adminConfig.refresh') }}
      </button>
    </div>

    <p class="text-xs text-slate-500 mb-4">{{ t('adminConfig.subtitle') }}</p>

    <div v-if="error" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800" role="alert">{{ error }}</div>

    <dl v-if="data" class="text-xs bg-white rounded-md border border-slate-200 divide-y divide-slate-100">
      <div v-for="(entry, i) in [
        { k: 'adminConfig.defaultTimezone',    v: data.defaultTimezone },
        { k: 'adminConfig.userLanguage',       v: data.userLanguage },
        { k: 'adminConfig.userCountry',        v: data.userCountry },
        { k: 'adminConfig.fileEncoding',       v: data.fileEncoding },
        { k: 'adminConfig.osName',             v: data.osName + ' (' + data.osArch + ')' },
        { k: 'adminConfig.javaOpts',           v: data.javaOpts },
        { k: 'adminConfig.retinalPushUrl',     v: data.retinalInferenceRemotePushUrl },
        { k: 'adminConfig.ssoEnabled',         v: data.ssoEnabled ? 'true' : 'false' },
      ]" :key="i" class="flex justify-between px-4 py-2">
        <dt class="text-slate-500">{{ t(entry.k) }}</dt>
        <dd class="ml-3 text-right break-all">{{ display(typeof entry.v === 'boolean' ? String(entry.v) : entry.v) }}</dd>
      </div>
    </dl>
  </div>
</template>

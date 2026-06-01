<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'

import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')

async function onLocalLogin() {
  await auth.localLogin(username.value, password.value)
  if (!auth.isAuthenticated && !auth.needsProfile) return // error already in auth.error
  if (auth.needsProfile) {
    router.push({ name: 'first-login' })
    return
  }
  if (!auth.user?.activeStudy) router.push({ name: 'pick-study' })
  else router.push({ name: 'home' })
}

function onSsoBounce() {
  // Triggers the actual browser navigation to the IdP entry URL —
  // no router push needed; the page leaves.
  auth.ssoBounce()
}
</script>

<template>
  <div class="min-h-[calc(100vh-3.5rem)] flex flex-col items-center justify-center px-6 py-10 bg-slate-50">
    <div class="w-full max-w-sm">
      <!-- Brand lockup -->
      <div class="text-center mb-8">
        <div class="inline-flex items-center gap-2 mb-3">
          <svg class="w-10 h-10 text-muw-blue" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="16" cy="16" r="14" stroke-width="1.4" />
            <path d="M12 8v16M20 8v16M8 12h16M8 20h16" stroke-width="1.75" />
          </svg>
          <span class="muw-display text-xl font-semibold tracking-tight text-muw-blue">
            LibreClinica<em class="not-italic font-medium text-muw-coral-700 text-[0.7em] uppercase tracking-[0.08em] ml-1.5 align-middle">MUW</em>
          </span>
        </div>
        <p class="text-xs text-slate-500 leading-relaxed">{{ t('login.brandLine') }}</p>
      </div>

      <h1 class="text-lg font-semibold tracking-tight mb-1">{{ t('login.title') }}</h1>
      <p class="text-xs text-slate-500 mb-5">{{ t('login.subtitle') }}</p>

      <!-- SSO primary CTA (DR-014 §3) -->
      <button
        v-if="auth.ssoConfig.enabled"
        type="button"
        class="block w-full text-center px-4 py-3 text-sm bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium muw-focus"
        @click="onSsoBounce"
      >
        <span class="inline-flex items-center justify-center gap-2.5">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
          </svg>
          {{ auth.ssoConfig.buttonLabel }}
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <line x1="5" x2="19" y1="12" y2="12" />
            <polyline points="12 5 19 12 12 19" />
          </svg>
        </span>
      </button>
      <p v-if="auth.ssoConfig.enabled && auth.ssoConfig.providerHint" class="mt-3 text-[11px] text-slate-500 leading-relaxed">
        {{ auth.ssoConfig.providerHint }}
        <strong>{{ t('login.delegatedHint') }}</strong>
      </p>

      <!-- Divider -->
      <div v-if="auth.ssoConfig.enabled" class="relative my-7">
        <div class="absolute inset-0 flex items-center"><div class="w-full border-t border-slate-200"></div></div>
        <div class="relative flex justify-center"><span class="bg-slate-50 px-3 text-[11px] uppercase tracking-wider text-slate-400">{{ t('login.orLocal') }}</span></div>
      </div>

      <!-- Inline error -->
      <div v-if="auth.error" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800 flex items-start gap-2" role="alert">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" x2="12" y1="8" y2="12" />
          <line x1="12" x2="12.01" y1="16" y2="16" />
        </svg>
        <span>{{ auth.error }}</span>
      </div>

      <!-- Local account form -->
      <details :open="!auth.ssoConfig.enabled" class="text-xs bg-white rounded-md border border-slate-200 px-3 py-3">
        <summary class="cursor-pointer text-slate-700 hover:text-slate-900 font-medium inline-flex items-center gap-1">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <polyline points="6 9 12 15 18 9" />
          </svg>
          {{ t('login.localAccountSummary') }}
        </summary>
        <form class="space-y-3 mt-3" @submit.prevent="onLocalLogin">
          <div>
            <FieldLabel for="login-username" required>{{ t('login.username') }}</FieldLabel>
            <TextInput id="login-username" v-model="username" autocomplete="username" />
          </div>
          <div>
            <FieldLabel for="login-password" required>{{ t('login.password') }}</FieldLabel>
            <TextInput id="login-password" v-model="password" type="password" autocomplete="current-password" />
          </div>
          <button type="submit" class="w-full px-4 py-2 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700 font-medium muw-focus">
            {{ t('login.signInLocal') }}
          </button>
          <p class="text-[11px] text-slate-500 leading-relaxed">{{ t('login.localPurpose') }}</p>
        </form>
      </details>

      <div class="mt-8 flex items-center justify-center gap-2 text-[11px] text-slate-400">
        <span>v1.4.0rc1-muw</span>
        <span>·</span>
        <a href="#" class="hover:text-slate-600">{{ t('login.terms') }}</a>
        <span>·</span>
        <a href="#" class="hover:text-slate-600">{{ t('login.privacy') }}</a>
      </div>

      <div class="mt-3 rounded-md bg-muw-blue-50 border border-muw-blue-100 px-3 py-2 text-[11px] text-muw-blue-900 flex items-start gap-2">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
          <rect width="18" height="11" x="3" y="11" rx="2" />
          <path d="M7 11V7a5 5 0 0 1 10 0v4" />
        </svg>
        <span>{{ t('login.complianceTell') }}</span>
      </div>
    </div>
  </div>
</template>

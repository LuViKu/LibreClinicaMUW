<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'
import ConfirmationWithPreflight from '@/components/ConfirmationWithPreflight.vue'
import ESignatureBlock from '@/components/ESignatureBlock.vue'
import type { PreflightRow } from '@/components/ConfirmationWithPreflight.vue'
import type { ESignaturePayload } from '@/components/ESignatureBlock.vue'

import { useSubjectsStore } from '@/stores/subjects'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/client'

/**
 * Phase E.4 M8 — Sign Subject view (real-backend wired).
 *
 * On mount: fetch the subject detail + sign-preflight in parallel.
 * Renders the preflight rows above the casebook + attestation block;
 * the submit button stays disabled until attestation is acknowledged,
 * a password is entered, and `preflight.blockingFailures === 0`.
 *
 * On submit: POST `/sign` via `subjects.signSubject()`. On 200 we
 * route to `/subjects/{id}` so the detail page renders the now-signed
 * state from the freshly-updated `selected` ref. On error, the inline
 * banner shows the server message.
 *
 * Preflight semantics (`pass | warn | fail`) collapse to the
 * `ConfirmationWithPreflight` primitive's union
 * (`pass | warn | blocker | info`) — fail → blocker. The
 * `subject-not-signed` check is rendered as `info` because it
 * communicates the precondition, not a regulatory failure.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const subjects = useSubjectsStore()
const auth = useAuthStore()

const subjectId = computed(() => String(route.params.subjectId))

const submitError = ref<string | null>(null)
const submitting = ref(false)
const justSigned = ref(false)

onMounted(async () => {
  // Fetch detail + preflight in parallel. Both end up in the store;
  // the view binds against the reactive refs below.
  await Promise.all([
    subjects.fetchOne(subjectId.value),
    subjects.loadPreflight(subjectId.value),
  ])
})

const subject = computed(() => subjects.selected)
const preflight = computed(() => subjects.preflight)

/**
 * Render the M3 preflight rows. The `subject-not-signed` check is
 * mapped to `info` (rather than `pass`/`blocker`) because it
 * communicates the precondition for the action; failing this check
 * means "subject is already signed" which the 409 guard catches
 * separately. The other four collapse pass → pass, warn → warn,
 * fail → blocker.
 */
const preflightRows = computed<PreflightRow[]>(() => {
  if (!preflight.value) return []
  return preflight.value.checks.map((c) => {
    let status: PreflightRow['status']
    if (c.id === 'subject-not-signed') {
      status = 'info'
    } else if (c.status === 'pass') {
      status = 'pass'
    } else if (c.status === 'warn') {
      status = 'warn'
    } else {
      status = 'blocker'
    }
    return {
      id: c.id,
      status,
      title: c.title,
      detail: c.detail || undefined,
    }
  })
})

const blockingPreflightExists = computed(() => {
  if (!preflight.value) return false
  // Subject-not-signed fail is the expected precondition state, not
  // a blocker. Mirrors the backend's M8 logic.
  return preflight.value.checks.some(
    (c) => c.status === 'fail' && c.id !== 'subject-not-signed',
  )
})

const username = computed(() => auth.user?.username ?? '')

async function onSign(payload: ESignaturePayload) {
  if (!subject.value) return
  if (!payload.acknowledged) return
  if (!payload.password) {
    submitError.value = t('signSubject.error.passwordRequired')
    return
  }
  submitError.value = null
  submitting.value = true
  try {
    await subjects.signSubject(subjectId.value, payload.password, true)
    justSigned.value = true
    // Brief delay so the user sees the success toast before nav.
    setTimeout(() => {
      router.push({ name: 'subject-detail', params: { subjectId: subjectId.value } })
    }, 1_200)
  } catch (e) {
    // The store re-throws ApiError on every non-network failure;
    // surface the server-supplied message verbatim. 401 / 409 / 412
    // all land here.
    if (e instanceof ApiError) {
      if (e.status === 401) {
        submitError.value = t('signSubject.error.badPassword')
      } else if (e.status === 409) {
        submitError.value = t('signSubject.error.alreadySigned')
      } else if (e.status === 412) {
        submitError.value = t('signSubject.error.preflightBlocked')
      } else {
        submitError.value = e.message
      }
    } else if (e instanceof Error) {
      submitError.value = e.message
    } else {
      submitError.value = t('signSubject.error.unknown')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/subjects" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <rect width="18" height="18" x="3" y="3" rx="2" />
          <path d="M3 9h18M9 21V9" />
        </svg>
        {{ t('nav.subjectMatrix') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-4xl px-8 py-6">
      <p v-if="subjects.isLoadingSelected || subjects.isLoadingPreflight" class="text-slate-500 italic">
        {{ t('common.loading') }}
      </p>

      <div
        v-else-if="subjects.selectedError || subjects.preflightError"
        class="rounded-muw bg-rose-50 border border-rose-200 px-4 py-3 text-xs text-rose-700"
        role="alert"
      >
        {{ subjects.selectedError ?? subjects.preflightError }}
      </div>

      <template v-else-if="subject">
        <div class="mb-5">
          <div class="text-xs text-slate-500 mb-1">{{ t('signSubject.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight flex items-center gap-3">
            {{ t('signSubject.title', { id: subject.id }) }}
            <StatusPill v-if="subject.signed" variant="success">{{ t('subjectMatrix.signed') }}</StatusPill>
          </h1>
          <p class="text-slate-500 text-xs mt-1 leading-relaxed max-w-2xl">
            {{ t('signSubject.intro') }}
          </p>
        </div>

        <!-- Preflight rows from the M3 endpoint -->
        <ConfirmationWithPreflight
          :heading="t('signSubject.preflightHeading')"
          :rows="preflightRows"
          class="mb-5"
        />

        <!-- Casebook snapshot from the subject's events[] -->
        <section class="bg-white border border-slate-200 rounded-muw overflow-hidden mb-5">
          <div class="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
            <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">{{ t('signSubject.casebookHeading') }}</h2>
            <a href="#" class="text-xs text-muw-blue hover:underline">{{ t('signSubject.casebookPdf') }}</a>
          </div>
          <DenseTable :bordered="false">
            <template #header>
              <tr class="border-b border-slate-200">
                <th scope="col" class="px-4 py-2 font-medium">{{ t('signSubject.column.event') }}</th>
                <th scope="col" class="px-4 py-2 font-medium">{{ t('signSubject.column.status') }}</th>
                <th scope="col" class="px-4 py-2 font-medium w-32 text-right">{{ t('signSubject.column.openQueries') }}</th>
              </tr>
            </template>
            <tr v-for="ev in subject.events" :key="ev.eventDefinitionOid">
              <td class="px-4 py-2.5 font-medium">{{ ev.label }}</td>
              <td class="px-4 py-2.5">
                <StatusPill
                  :variant="ev.status === 'complete' || ev.status === 'signed' ? 'success' : ev.status === 'in-progress' || ev.status === 'scheduled' ? 'info' : 'neutral'"
                >
                  {{ t(`subjectMatrix.status.${ev.status}`) }}
                </StatusPill>
              </td>
              <td class="px-4 py-2.5 text-right">
                <StatusPill v-if="ev.openQueries > 0" compact variant="danger">{{ ev.openQueries }}</StatusPill>
                <span v-else class="text-slate-400">—</span>
              </td>
            </tr>
          </DenseTable>
        </section>

        <!-- E-signature block — wired to the real /sign endpoint -->
        <ESignatureBlock
          :username="username"
          signature-mode="local"
          :submit-label="t('signSubject.submitLabel', { id: subject.id })"
          :disabled="blockingPreflightExists || subject.signed || submitting"
          @submit="onSign"
        >
          <template #attestation>
            {{ t('signSubject.attestation', { id: subject.id, name: username, site: subject.siteLabel }) }}
          </template>
          <template #acknowledgement>
            {{ t('signSubject.acknowledgement') }}
          </template>
          <template #cancel>
            <RouterLink :to="`/subjects/${subject.id}`" class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700">
              {{ t('common.cancel') }}
            </RouterLink>
          </template>
        </ESignatureBlock>

        <!-- Inline submit error -->
        <div
          v-if="submitError"
          class="mt-4 rounded-muw bg-rose-50 border border-rose-200 px-4 py-3 text-xs text-rose-700"
          role="alert"
        >
          {{ submitError }}
        </div>

        <!-- Success toast on signed -->
        <div
          v-if="justSigned"
          class="mt-5 rounded-muw bg-muw-teal-50 border border-muw-teal-200 px-4 py-3 text-xs text-muw-teal-700 flex items-center gap-2.5"
          role="status"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          {{ t('signSubject.signedToast', { id: subject.id }) }}
        </div>
      </template>
    </main>
  </div>
</template>

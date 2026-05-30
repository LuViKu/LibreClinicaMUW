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

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const subjects = useSubjectsStore()

const subjectId = computed(() => String(route.params.subjectId))

onMounted(async () => {
  if (subjects.rows.length === 0) await subjects.load()
})

const subject = computed(() => subjects.rows.find((s) => s.id === subjectId.value) ?? null)

const totalOpenQueries = computed(() => subject.value?.openQueries ?? 0)
const allEventsComplete = computed(() => subject.value?.events.every((e) => e.status === 'complete' || e.status === 'signed' || e.status === 'locked') ?? false)
const allCrfsComplete = allEventsComplete // proxy for v0 — refined when CRF-level state lands

const preflight = computed<PreflightRow[]>(() => {
  const rows: PreflightRow[] = []
  if (allEventsComplete.value) {
    rows.push({ id: 'events', status: 'pass', title: t('signSubject.preflight.eventsCompleteTitle'), detail: t('signSubject.preflight.eventsCompleteDetail') })
  } else {
    rows.push({ id: 'events', status: 'warn', title: t('signSubject.preflight.eventsIncompleteTitle'), detail: t('signSubject.preflight.eventsIncompleteDetail') })
  }
  if (allCrfsComplete.value) {
    rows.push({ id: 'crfs', status: 'pass', title: t('signSubject.preflight.crfsCompleteTitle') })
  } else {
    rows.push({ id: 'crfs', status: 'warn', title: t('signSubject.preflight.crfsIncompleteTitle') })
  }
  if (totalOpenQueries.value > 0) {
    rows.push({ id: 'queries', status: 'warn', title: t('signSubject.preflight.openQueriesTitle', { count: totalOpenQueries.value }), detail: t('signSubject.preflight.openQueriesDetail') })
  } else {
    rows.push({ id: 'queries', status: 'pass', title: t('signSubject.preflight.noOpenQueriesTitle') })
  }
  rows.push({ id: 'history', status: 'info', title: subject.value?.signed ? t('signSubject.preflight.alreadySigned') : t('signSubject.preflight.firstSignature') })
  return rows
})

const blockingPreflightExists = computed(() => preflight.value.some((r) => r.status === 'blocker'))

const justSigned = ref(false)

function onSign(payload: ESignaturePayload) {
  // Optimistic local update — flips the subject to signed; backend POST lands
  // alongside the E.4 adapter (see api-surface.md row 13 — both the preflight
  // GET and the POST /sign endpoint).
  if (!subject.value || !payload.acknowledged) return
  subject.value.signed = true
  justSigned.value = true
  setTimeout(() => {
    router.push({ name: 'subject-matrix' })
  }, 1_200)
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
      <p v-if="!subject" class="text-slate-500 italic">{{ t('common.loading') }}</p>

      <template v-else>
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

        <!-- Preflight -->
        <ConfirmationWithPreflight :heading="t('signSubject.preflightHeading')" :rows="preflight" class="mb-5" />

        <!-- Casebook snapshot — mini DenseTable of events -->
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

        <!-- E-signature block -->
        <ESignatureBlock
          :username="'user_demo'"
          signature-mode="local"
          :submit-label="t('signSubject.submitLabel', { id: subject.id })"
          :disabled="blockingPreflightExists || subject.signed"
          @submit="onSign"
        >
          <template #attestation>
            {{ t('signSubject.attestation', { id: subject.id, name: 'Dr. user_demo', site: 'München' }) }}
          </template>
          <template #acknowledgement>
            {{ t('signSubject.acknowledgement') }}
          </template>
          <template #cancel>
            <RouterLink to="/subjects" class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700">
              {{ t('common.cancel') }}
            </RouterLink>
          </template>
        </ESignatureBlock>

        <!-- Toast on successful sign -->
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

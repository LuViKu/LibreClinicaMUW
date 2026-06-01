<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import Wizard from '@/components/Wizard.vue'
import type { WizardStep } from '@/components/Wizard.vue'
import StatusPill from '@/components/StatusPill.vue'
import DiffCard from '@/components/DiffCard.vue'
import DenseTable from '@/components/DenseTable.vue'

const { t } = useI18n()

const step = ref(0)

const steps = computed<WizardStep[]>(() => [
  { id: 'upload',  title: t('importCrf.step.upload'),  clickable: true },
  { id: 'map',     title: t('importCrf.step.map'),     clickable: uploadComplete.value },
  { id: 'preview', title: t('importCrf.step.preview'), clickable: mapComplete.value },
  { id: 'commit',  title: t('importCrf.step.commit'),  clickable: false },
])

/* Step 1 — Upload */
const fileName = ref<string | null>(null)
const fileSize = ref<number | null>(null)
function pickFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  fileName.value = file.name
  fileSize.value = file.size
}
const uploadComplete = computed(() => fileName.value != null)

/* Step 2 — Map (auto-detected) */
const detectedSubjects = ref(3)
const detectedEvents = ref(8)
const detectedCrfs = ref(17)
const detectedRows = ref(412)
const mapComplete = computed(() => uploadComplete.value)

/* Step 3 — Preview */
interface PreviewRow {
  id: string
  status: 'ready' | 'overwrite' | 'error' | 'warning'
  subject: string
  event: string
  item: string
  before?: string
  after?: string
  detail?: string
  action?: 'insert' | 'overwrite' | 'skip' | 'flag'
}
const preview = ref<PreviewRow[]>([
  { id: 'p-001', status: 'overwrite', subject: 'M-001', event: 'V2 Day 30',    item: 'AE · ae_severity',           before: 'Severe',  after: 'Moderate', action: 'overwrite' },
  { id: 'p-002', status: 'overwrite', subject: 'M-002', event: 'V1 Inclusion', item: 'Vitals · weight_kg',         before: '68.0',    after: '67.5',     action: 'overwrite' },
  { id: 'p-003', status: 'error',     subject: 'M-003', event: 'V2 Day 30',    item: 'AE · ae_severity',           detail: t('importCrf.preview.errorBadValue'), action: 'skip' },
  { id: 'p-004', status: 'error',     subject: 'M-007', event: 'V1 Inclusion', item: 'Demographics · sex_at_birth', detail: t('importCrf.preview.errorUnknownItem'), action: 'skip' },
  { id: 'p-005', status: 'warning',   subject: 'M-001', event: 'V2 Day 30',    item: 'Vitals · heart_rate',         detail: t('importCrf.preview.warnRange'), action: 'flag' },
  { id: 'p-006', status: 'ready',     subject: 'M-001', event: 'V1 Inclusion', item: 'Demographics · year_of_birth', detail: '1962', action: 'insert' },
])

const readyCount     = computed(() => preview.value.filter((r) => r.status === 'ready').length)
const overwriteCount = computed(() => preview.value.filter((r) => r.status === 'overwrite').length)
const errorCount     = computed(() => preview.value.filter((r) => r.status === 'error').length)
const warningCount   = computed(() => preview.value.filter((r) => r.status === 'warning').length)

const reasonForChange = ref('')

/* Step 4 — Commit */
const committed = ref(false)
async function commit() {
  await new Promise((resolve) => setTimeout(resolve, 50))
  committed.value = true
}

function variantFor(s: PreviewRow['status']): 'success' | 'warning' | 'danger' | 'info' {
  switch (s) {
    case 'ready':     return 'success'
    case 'warning':   return 'warning'
    case 'error':     return 'danger'
    case 'overwrite': return 'info'
  }
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M3 7h18M3 12h18M3 17h12" />
        </svg>
        {{ t('nav.buildStudy') }}
      </RouterLink>
      <RouterLink to="/manage-users" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z" />
        </svg>
        {{ t('nav.manageUsers') }}
      </RouterLink>
      <RouterLink to="/import-crf-data" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8 12 3 7 8M12 3v15" />
        </svg>
        {{ t('nav.importCrfData') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-5xl px-8 py-6">
      <div class="mb-5">
        <div class="text-xs text-slate-500 mb-1">{{ t('importCrf.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('importCrf.title') }}</h1>
        <p class="text-xs text-slate-500 mt-1 max-w-3xl leading-relaxed">{{ t('importCrf.intro') }}</p>
      </div>

      <Wizard v-model:step="step" :steps="steps">
        <template #default="{ currentStep }">
          <!-- Step 1: Upload -->
          <section v-if="currentStep === 0" class="rounded-muw border border-slate-200 bg-white p-6">
            <h2 class="text-sm font-semibold mb-2">{{ t('importCrf.upload.heading') }}</h2>
            <p class="text-xs text-slate-500 mb-4">{{ t('importCrf.upload.description') }}</p>
            <label class="block rounded-muw border-2 border-dashed border-slate-300 bg-slate-50 px-6 py-10 text-center cursor-pointer hover:border-muw-blue-300 hover:bg-muw-blue-50/30 transition">
              <input type="file" accept=".xml" class="sr-only" @change="pickFile" />
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="mx-auto text-slate-400" aria-hidden="true">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8 12 3 7 8M12 3v15" />
              </svg>
              <p class="text-sm text-slate-700 mt-3 font-medium">{{ t('importCrf.upload.cta') }}</p>
              <p class="text-xs text-slate-500 mt-1">{{ t('importCrf.upload.hint') }}</p>
              <div v-if="fileName" class="mt-3 inline-flex items-center gap-2 bg-white border border-slate-200 rounded-md px-3 py-1.5 text-xs">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                </svg>
                <span class="font-mono">{{ fileName }}</span>
                <span class="text-slate-400">·</span>
                <span class="text-slate-500">{{ fileSize ? `${(fileSize / 1024).toFixed(1)} kB` : '' }}</span>
              </div>
            </label>
            <div class="mt-5 flex justify-end">
              <button
                class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
                :disabled="!uploadComplete"
                @click="step = 1"
              >{{ t('common.next') }} →</button>
            </div>
          </section>

          <!-- Step 2: Map -->
          <section v-else-if="currentStep === 1" class="rounded-muw border border-slate-200 bg-white p-6">
            <h2 class="text-sm font-semibold mb-2">{{ t('importCrf.map.heading') }}</h2>
            <p class="text-xs text-slate-500 mb-4">{{ t('importCrf.map.description') }}</p>
            <dl class="grid grid-cols-2 gap-4 text-sm bg-slate-50 rounded-muw p-4">
              <div class="flex justify-between"><dt class="text-slate-500">{{ t('importCrf.map.subjects') }}</dt><dd class="font-semibold">{{ detectedSubjects }}</dd></div>
              <div class="flex justify-between"><dt class="text-slate-500">{{ t('importCrf.map.events') }}</dt><dd class="font-semibold">{{ detectedEvents }}</dd></div>
              <div class="flex justify-between"><dt class="text-slate-500">{{ t('importCrf.map.crfs') }}</dt><dd class="font-semibold">{{ detectedCrfs }}</dd></div>
              <div class="flex justify-between"><dt class="text-slate-500">{{ t('importCrf.map.rows') }}</dt><dd class="font-semibold">{{ detectedRows }}</dd></div>
            </dl>
            <div class="mt-5 flex justify-between items-center">
              <button class="text-xs text-slate-500 hover:text-slate-700" @click="step = 0">← {{ t('common.back') }}</button>
              <button class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium" @click="step = 2">{{ t('common.next') }} →</button>
            </div>
          </section>

          <!-- Step 3: Preview & resolve -->
          <section v-else-if="currentStep === 2" class="space-y-4">
            <div class="grid grid-cols-4 gap-2">
              <div class="rounded-muw border border-muw-teal-200 bg-muw-teal-50 p-3">
                <div class="text-[10px] uppercase tracking-wider text-muw-teal-700 font-semibold">{{ t('importCrf.preview.readyHeading') }}</div>
                <div class="mt-1 text-xl font-semibold text-muw-teal-900">{{ readyCount }}</div>
              </div>
              <div class="rounded-muw border border-muw-sky-200 bg-muw-sky-50 p-3">
                <div class="text-[10px] uppercase tracking-wider text-muw-sky-700 font-semibold">{{ t('importCrf.preview.overwriteHeading') }}</div>
                <div class="mt-1 text-xl font-semibold text-muw-sky-900">{{ overwriteCount }}</div>
              </div>
              <div class="rounded-muw border border-amber-200 bg-amber-50 p-3">
                <div class="text-[10px] uppercase tracking-wider text-amber-700 font-semibold">{{ t('importCrf.preview.warningHeading') }}</div>
                <div class="mt-1 text-xl font-semibold text-amber-900">{{ warningCount }}</div>
              </div>
              <div class="rounded-muw border border-rose-200 bg-rose-50 p-3">
                <div class="text-[10px] uppercase tracking-wider text-rose-700 font-semibold">{{ t('importCrf.preview.errorHeading') }}</div>
                <div class="mt-1 text-xl font-semibold text-rose-900">{{ errorCount }}</div>
              </div>
            </div>

            <DenseTable>
              <template #header>
                <tr class="border-b border-slate-200">
                  <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('importCrf.preview.column.status') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('importCrf.preview.column.subject') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('importCrf.preview.column.event') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium w-44">{{ t('importCrf.preview.column.item') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium">{{ t('importCrf.preview.column.delta') }}</th>
                </tr>
              </template>
              <tr v-for="row in preview" :key="row.id">
                <td class="px-3 py-2"><StatusPill :variant="variantFor(row.status)">{{ t(`importCrf.preview.status.${row.status}`) }}</StatusPill></td>
                <td class="px-3 py-2 font-medium">{{ row.subject }}</td>
                <td class="px-3 py-2 text-slate-700">{{ row.event }}</td>
                <td class="px-3 py-2 font-mono text-xs text-slate-600">{{ row.item }}</td>
                <td class="px-3 py-2">
                  <DiffCard v-if="row.before != null && row.after != null" compact>
                    <template #before>{{ row.before }}</template>
                    <template #after>{{ row.after }}</template>
                  </DiffCard>
                  <span v-else-if="row.detail" class="text-xs text-slate-700">{{ row.detail }}</span>
                </td>
              </tr>
            </DenseTable>

            <div class="bg-white border border-slate-200 rounded-muw p-4">
              <h3 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-2">{{ t('importCrf.preview.reasonHeading') }}</h3>
              <p class="text-xs text-slate-600 mb-2">{{ t('importCrf.preview.reasonHint', { count: overwriteCount }) }}</p>
              <textarea
                v-model="reasonForChange"
                rows="2"
                class="w-full px-3 py-2 border border-slate-300 rounded-md text-sm focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 focus:outline-none muw-focus"
                :placeholder="t('importCrf.preview.reasonPlaceholder')"
              ></textarea>
            </div>

            <div class="flex items-center justify-between">
              <button class="text-xs text-slate-500 hover:text-slate-700" @click="step = 1">← {{ t('common.back') }}</button>
              <button
                class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
                :disabled="overwriteCount > 0 && reasonForChange.trim().length === 0"
                @click="(step = 3, commit())"
              >
                {{ t('importCrf.preview.cta') }} →
              </button>
            </div>
          </section>

          <!-- Step 4: Commit -->
          <section v-else class="rounded-muw border border-muw-teal-200 bg-muw-teal-50 p-6 text-center">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="mx-auto text-muw-teal-700" aria-hidden="true">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            <h2 class="text-sm font-semibold mt-3 text-muw-teal-900">
              {{ committed ? t('importCrf.commit.successHeading') : t('importCrf.commit.workingHeading') }}
            </h2>
            <p class="text-xs text-muw-teal-700 mt-1">
              {{ committed ? t('importCrf.commit.successDetail', { ready: readyCount, overwrite: overwriteCount, error: errorCount }) : t('importCrf.commit.workingDetail') }}
            </p>
          </section>
        </template>
      </Wizard>
    </main>
  </div>
</template>

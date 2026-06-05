<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { storeToRefs } from 'pinia'

import SideRail from '@/components/SideRail.vue'
import Wizard from '@/components/Wizard.vue'
import type { WizardStep } from '@/components/Wizard.vue'
import StatusPill from '@/components/StatusPill.vue'
import DiffCard from '@/components/DiffCard.vue'
import DenseTable from '@/components/DenseTable.vue'

import { useImportCrfStore } from '@/stores/importCrf'
import type { ImportCrfPreviewRow, ImportOverwriteMode } from '@/types/importCrf'

const { t } = useI18n()

const store = useImportCrfStore()
const { preview, extraRows, commitResult, isUploading, isCommitting, error, tokenExpired } = storeToRefs(store)

const step = ref(0)

const steps = computed<WizardStep[]>(() => [
  { id: 'upload',  title: t('importCrf.step.upload'),  clickable: true },
  { id: 'map',     title: t('importCrf.step.map'),     clickable: uploadComplete.value },
  { id: 'preview', title: t('importCrf.step.preview'), clickable: uploadComplete.value },
  { id: 'commit',  title: t('importCrf.step.commit'),  clickable: false },
])

/* Step 1 — Upload */
const fileName = ref<string | null>(null)
const fileSize = ref<number | null>(null)
const uploadComplete = computed(() => preview.value != null)

async function pickFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  fileName.value = file.name
  fileSize.value = file.size
  const res = await store.uploadFile(file)
  if (res.ok) step.value = 1
}

/* Step 2 — Map (real counts from preview, fallback to '—' before upload) */
const detectedSubjects = computed(() => preview.value?.subjectCount ?? 0)
const detectedEvents = computed(() => preview.value?.eventCount ?? 0)
const detectedCrfs = computed(() => preview.value?.crfCount ?? 0)
const detectedRows = computed(() => preview.value?.rowCount ?? 0)

/* Step 3 — Preview */
const allRows = computed<ImportCrfPreviewRow[]>(() => {
  if (preview.value == null) return []
  // Inline rows (first 200) + any windowed rows fetched via the
  // /rows endpoint. The view appends as the operator scrolls.
  return [...preview.value.rows, ...extraRows.value]
})

const readyCount = computed(() => preview.value?.insertCount ?? 0)
const overwriteCount = computed(() => preview.value?.overwriteCount ?? 0)
const errorCount = computed(() => preview.value?.errorCount ?? 0)
const warningCount = computed(() => preview.value?.warningCount ?? 0)

const reasonForChange = ref('')
const overwriteMode = ref<ImportOverwriteMode>('replace')

/* Step 4 — Commit */
async function commit() {
  const res = await store.commit(
    reasonForChange.value || null,
    overwriteMode.value,
  )
  // step 4 already active by the time we land here; result + error live
  // on the store refs so the template reflects whichever branch fires.
  void res
}

function startOver() {
  store.reset()
  fileName.value = null
  fileSize.value = null
  reasonForChange.value = ''
  overwriteMode.value = 'replace'
  step.value = 0
}

function variantFor(s: ImportCrfPreviewRow['status']): 'success' | 'warning' | 'danger' | 'info' {
  switch (s) {
    case 'ready':     return 'success'
    case 'warning':   return 'warning'
    case 'error':     return 'danger'
    case 'overwrite': return 'info'
    default:          return 'info'
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
              <input type="file" accept=".xml" class="sr-only" :disabled="isUploading" @change="pickFile" />
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
              <p v-if="isUploading" class="mt-3 text-xs text-muw-blue">{{ t('importCrf.upload.uploading') }}</p>
            </label>
            <p v-if="error && !uploadComplete" class="mt-3 text-xs text-rose-700">{{ error }}</p>
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

            <DenseTable v-if="allRows.length">
              <template #header>
                <tr class="border-b border-slate-200">
                  <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('importCrf.preview.column.status') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('importCrf.preview.column.subject') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('importCrf.preview.column.event') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium w-44">{{ t('importCrf.preview.column.item') }}</th>
                  <th scope="col" class="px-3 py-2 font-medium">{{ t('importCrf.preview.column.delta') }}</th>
                </tr>
              </template>
              <tr v-for="(row, idx) in allRows" :key="idx">
                <td class="px-3 py-2"><StatusPill :variant="variantFor(row.status)">{{ t(`importCrf.preview.status.${row.status}`) }}</StatusPill></td>
                <td class="px-3 py-2 font-medium">{{ row.subjectOid }}</td>
                <td class="px-3 py-2 text-slate-700">{{ row.eventOid }}</td>
                <td class="px-3 py-2 font-mono text-xs text-slate-600">{{ row.crfOid }} · {{ row.itemOid }}</td>
                <td class="px-3 py-2">
                  <DiffCard v-if="row.before != null && row.after != null" compact>
                    <template #before>{{ row.before }}</template>
                    <template #after>{{ row.after }}</template>
                  </DiffCard>
                  <span v-else-if="row.detail" class="text-xs text-slate-700">{{ row.detail }}</span>
                  <span v-else-if="row.after != null" class="text-xs font-mono text-slate-600">{{ row.after }}</span>
                </td>
              </tr>
            </DenseTable>
            <p v-else class="text-xs text-slate-500 italic px-1">{{ t('importCrf.preview.noRows') }}</p>

            <p v-if="preview && preview.rowCount > allRows.length" class="text-xs text-slate-500 px-1">
              {{ t('importCrf.preview.loadingMoreRows', { shown: allRows.length, total: preview.rowCount }) }}
            </p>

            <div v-if="preview && preview.issues.length" class="bg-amber-50 border border-amber-200 rounded-muw p-4">
              <h3 class="text-xs font-semibold uppercase tracking-wider text-amber-800 mb-2">{{ t('importCrf.preview.issuesHeading') }}</h3>
              <ul class="text-xs text-amber-900 space-y-1">
                <li v-for="(i, idx) in preview.issues" :key="idx" class="font-mono">
                  <span class="font-semibold">[{{ i.severity }}]</span> {{ i.identifier }} — {{ i.message }}
                </li>
              </ul>
            </div>

            <div class="bg-white border border-slate-200 rounded-muw p-4 space-y-3">
              <div>
                <h3 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-2">{{ t('importCrf.preview.overwriteModeHeading') }}</h3>
                <label class="inline-flex items-center gap-2 mr-4 text-xs">
                  <input type="radio" v-model="overwriteMode" value="replace" /> {{ t('importCrf.overwriteMode.replace') }}
                </label>
                <label class="inline-flex items-center gap-2 text-xs">
                  <input type="radio" v-model="overwriteMode" value="skip" /> {{ t('importCrf.overwriteMode.skip') }}
                </label>
              </div>
              <div v-if="overwriteCount > 0 && overwriteMode === 'replace'">
                <h3 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-2">{{ t('importCrf.preview.reasonHeading') }}</h3>
                <p class="text-xs text-slate-600 mb-2">{{ t('importCrf.preview.reasonHint', { count: overwriteCount }) }}</p>
                <textarea
                  v-model="reasonForChange"
                  rows="2"
                  class="w-full px-3 py-2 border border-slate-300 rounded-md text-sm focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 focus:outline-none muw-focus"
                  :placeholder="t('importCrf.preview.reasonPlaceholder')"
                ></textarea>
              </div>
            </div>

            <div class="flex items-center justify-between">
              <button class="text-xs text-slate-500 hover:text-slate-700" @click="step = 1">← {{ t('common.back') }}</button>
              <button
                class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
                :disabled="errorCount > 0 || (overwriteCount > 0 && overwriteMode === 'replace' && reasonForChange.trim().length === 0)"
                @click="(step = 3, commit())"
              >
                {{ t('importCrf.preview.cta') }} →
              </button>
            </div>
          </section>

          <!-- Step 4: Commit -->
          <section v-else>
            <div v-if="isCommitting" class="rounded-muw border border-muw-teal-200 bg-muw-teal-50 p-6 text-center">
              <h2 class="text-sm font-semibold mt-3 text-muw-teal-900">{{ t('importCrf.commit.workingHeading') }}</h2>
              <p class="text-xs text-muw-teal-700 mt-1">{{ t('importCrf.commit.workingDetail') }}</p>
            </div>

            <div v-else-if="tokenExpired" class="rounded-muw border border-amber-200 bg-amber-50 p-6 text-center">
              <h2 class="text-sm font-semibold mt-3 text-amber-900">{{ t('importCrf.commit.tokenExpired') }}</h2>
              <p class="text-xs text-amber-700 mt-1">{{ error }}</p>
              <button
                class="mt-3 px-4 py-1.5 text-xs bg-amber-700 text-white rounded-md hover:bg-amber-800 font-medium"
                @click="startOver"
              >{{ t('importCrf.commit.reupload') }}</button>
            </div>

            <div v-else-if="commitResult" class="rounded-muw border border-muw-teal-200 bg-muw-teal-50 p-6 text-center">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="mx-auto text-muw-teal-700" aria-hidden="true">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              <h2 class="text-sm font-semibold mt-3 text-muw-teal-900">{{ t('importCrf.commit.successHeading') }}</h2>
              <dl class="mt-4 grid grid-cols-2 gap-x-6 gap-y-1 text-xs text-muw-teal-900 max-w-md mx-auto">
                <dt class="text-left text-muw-teal-700">{{ t('importCrf.commit.rowsInserted') }}</dt>
                <dd class="text-right font-mono font-semibold">{{ commitResult.rowsInserted }}</dd>
                <dt class="text-left text-muw-teal-700">{{ t('importCrf.commit.rowsOverwritten') }}</dt>
                <dd class="text-right font-mono font-semibold">{{ commitResult.rowsOverwritten }}</dd>
                <dt class="text-left text-muw-teal-700">{{ t('importCrf.commit.rowsSkipped') }}</dt>
                <dd class="text-right font-mono font-semibold">{{ commitResult.rowsSkipped }}</dd>
                <dt class="text-left text-muw-teal-700">{{ t('importCrf.commit.discrepancyNotes') }}</dt>
                <dd class="text-right font-mono font-semibold">{{ commitResult.discrepancyNotes }}</dd>
              </dl>
              <button
                class="mt-4 px-4 py-1.5 text-xs bg-muw-teal-700 text-white rounded-md hover:bg-muw-teal-800 font-medium"
                @click="startOver"
              >{{ t('importCrf.commit.startOver') }}</button>
            </div>

            <div v-else-if="error" class="rounded-muw border border-rose-200 bg-rose-50 p-6 text-center">
              <h2 class="text-sm font-semibold mt-3 text-rose-900">{{ t('importCrf.commit.failedHeading') }}</h2>
              <p class="text-xs text-rose-700 mt-1">{{ error }}</p>
              <div class="mt-4 flex justify-center gap-3">
                <button class="text-xs text-slate-500 hover:text-slate-700" @click="step = 2">← {{ t('common.back') }}</button>
                <button
                  class="px-4 py-1.5 text-xs bg-rose-700 text-white rounded-md hover:bg-rose-800 font-medium"
                  @click="startOver"
                >{{ t('importCrf.commit.startOver') }}</button>
              </div>
            </div>
          </section>
        </template>
      </Wizard>
    </main>
  </div>
</template>

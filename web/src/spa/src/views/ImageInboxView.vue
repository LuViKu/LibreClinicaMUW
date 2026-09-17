<script setup lang="ts">
/**
 * DR-025 — fundus-image reconciliation inbox (authenticated, staff).
 *
 * Lists UNBOUND image_ingest rows from BOTH ingress paths (Optomed C-STORE +
 * Remidio upload), shows each preview, and lets a Data Manager / Investigator
 * bind it to a subject/event/CRF — one-click when the PatientID resolves to a
 * single visible subject, otherwise via the two-step AssignImageDialog — or
 * dismiss it. Modelled on RetinalParkedAdminView.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import AssignImageDialog from '@/components/imageinbox/AssignImageDialog.vue'
import { useConfirm } from '@/composables/useConfirm'
import { bindImage, dismissImage, listImageInbox, type ImageInboxRow } from '@/api/imageInbox'

const { t } = useI18n()
const confirm = useConfirm()

// The API client prepends CONTEXT_PATH for fetches, but a raw <img src> does not.
const CONTEXT_PATH = '/LibreClinica'

const rows = ref<ImageInboxRow[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const busyId = ref<number | null>(null)

const dialogOpen = ref(false)
const dialogRow = ref<ImageInboxRow | null>(null)

function previewSrc(row: ImageInboxRow): string {
  return `${CONTEXT_PATH}${row.previewUrl}`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const res = await listImageInbox()
    rows.value = res.images
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

function removeRow(id: number): void {
  rows.value = rows.value.filter((r) => r.id !== id)
}

async function runBind(id: number, studySubjectId: number, studyEventId: number | null, eventCrfId: number | null): Promise<void> {
  busyId.value = id
  error.value = null
  try {
    await bindImage(id, { studySubjectId, studyEventId, eventCrfId })
    removeRow(id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busyId.value = null
  }
}

function bindSuggested(row: ImageInboxRow): void {
  const s = row.suggestion
  if (!s) return
  void runBind(row.id, s.studySubjectId, s.studyEventId, s.eventCrfId)
}

function openAssign(row: ImageInboxRow): void {
  dialogRow.value = row
  dialogOpen.value = true
}

function onDialogBind(payload: {
  imageIngestId: number
  studySubjectId: number
  studyEventId: number | null
  eventCrfId: number | null
}): void {
  dialogOpen.value = false
  void runBind(payload.imageIngestId, payload.studySubjectId, payload.studyEventId, payload.eventCrfId)
}

async function onDismiss(row: ImageInboxRow): Promise<void> {
  if (!(await confirm({ message: t('imageInbox.dismissConfirm'), danger: true }))) return
  busyId.value = row.id
  error.value = null
  try {
    await dismissImage(row.id)
    removeRow(row.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="p-6 max-w-6xl mx-auto">
    <header class="mb-5">
      <h1 class="text-xl font-semibold text-slate-800">{{ t('imageInbox.title') }}</h1>
      <p class="text-[13px] text-slate-500 mt-1">{{ t('imageInbox.subtitle', { count: rows.length }) }}</p>
    </header>

    <p v-if="error" class="mb-4 text-[13px] text-rose-700" data-testid="inbox-error">{{ error }}</p>

    <p v-if="loading" class="text-[13px] text-slate-500" data-testid="inbox-loading">{{ t('imageInbox.loading') }}</p>

    <p v-else-if="rows.length === 0" class="text-[13px] text-slate-500 italic" data-testid="inbox-empty">
      {{ t('imageInbox.empty') }}
    </p>

    <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4" data-testid="inbox-grid">
      <div v-for="row in rows" :key="row.id" class="bg-white rounded-2xl ring-1 ring-slate-200 overflow-hidden flex flex-col">
        <div class="aspect-[4/3] bg-slate-100 flex items-center justify-center overflow-hidden">
          <img v-if="row.hasPreview" :src="previewSrc(row)" alt="" class="w-full h-full object-contain" loading="lazy" />
          <span v-else class="text-[12px] text-slate-400">{{ t('imageInbox.noPreview') }}</span>
        </div>
        <div class="p-4 flex-1 flex flex-col">
          <div class="text-[13px] text-slate-700">
            <span class="font-medium">{{ t('imageInbox.patientId') }}:</span> {{ row.patientId || '—' }}
          </div>
          <div class="text-[12px] text-slate-500 mt-0.5">
            {{ t('imageInbox.eye') }}: {{ row.laterality || '—' }} · {{ row.studyDate || '—' }}
          </div>
          <div class="text-[11px] text-slate-400 mt-0.5 uppercase tracking-wide">
            {{ row.sourceKind }}<span v-if="row.receivedAt"> · {{ row.receivedAt.slice(0, 10) }}</span>
          </div>

          <p v-if="row.suggestion" class="mt-2 text-[12px] text-muw-teal-700">
            {{ t('imageInbox.suggestion', { label: row.suggestion.subjectLabel, study: row.suggestion.studyName }) }}
          </p>

          <div class="mt-auto pt-3 flex flex-wrap gap-2">
            <button
              v-if="row.suggestion && row.suggestion.eventCrfId"
              type="button"
              class="px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-teal-600 text-white hover:bg-muw-teal-700 disabled:bg-slate-300"
              :disabled="busyId === row.id"
              @click="bindSuggested(row)"
            >{{ t('imageInbox.bindSuggested') }}</button>
            <button
              type="button"
              class="px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-blue text-white hover:bg-muw-blue-700 disabled:bg-slate-300"
              :disabled="busyId === row.id"
              @click="openAssign(row)"
            >{{ t('imageInbox.bind') }}</button>
            <button
              type="button"
              class="px-3 py-1.5 text-[12px] font-medium rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:opacity-50"
              :disabled="busyId === row.id"
              @click="onDismiss(row)"
            >{{ t('imageInbox.dismiss') }}</button>
          </div>
        </div>
      </div>
    </div>

    <AssignImageDialog
      v-if="dialogRow"
      :open="dialogOpen"
      :image-ingest-id="dialogRow.id"
      :initial-patient-id="dialogRow.patientId ?? ''"
      @bind="onDialogBind"
      @close="dialogOpen = false"
    />
  </div>
</template>

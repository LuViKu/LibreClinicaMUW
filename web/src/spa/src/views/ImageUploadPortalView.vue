<script setup lang="ts">
/**
 * DR-025 — public image-upload portal view (Remidio FOP).
 *
 * The Remidio FOP is an iPhone + browser with no DICOM export, so the operator
 * uploads the captured fundus JPEG/PNG here. Unauthenticated (reverse-proxy
 * gated), phone-friendly. Mirrors the OCT/BCVA portals' posture; the image
 * lands in image_ingest(source_kind='upload', UNBOUND) for the reconciliation
 * inbox. An optional PatientID is stored as a match hint (confirmed on-page via
 * the resolve endpoint) but nothing binds here.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ImageDropzone from '@/components/imageportal/ImageDropzone.vue'
import { commitImage, resolvePatient, type ResolveState } from '@/api/imagePortal'

const { t } = useI18n()

const file = ref<File | null>(null)
const previewUrl = ref<string | null>(null)
const patientId = ref('')
const laterality = ref('')
const studyDate = ref('')
const uploading = ref(false)
const done = ref(false)
const error = ref<string | null>(null)

const resolveState = ref<ResolveState | null>(null)
const resolveLabel = ref('')
const resolveStudy = ref('')

function onFileSelected(f: File): void {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  file.value = f
  previewUrl.value = URL.createObjectURL(f)
  error.value = null
}

async function onPatientBlur(): Promise<void> {
  resolveState.value = null
  const pid = patientId.value.trim()
  if (!pid) return
  try {
    const r = await resolvePatient(pid, studyDate.value || undefined)
    resolveState.value = r.state
    if (r.candidates.length > 0) {
      resolveLabel.value = r.candidates[0].subjectLabel
      resolveStudy.value = r.candidates[0].studyName
    }
  } catch {
    resolveState.value = null // resolve is best-effort — never block the upload
  }
}

const canUpload = computed(() => !!file.value && !uploading.value)

async function onUpload(): Promise<void> {
  if (!file.value) return
  uploading.value = true
  error.value = null
  try {
    await commitImage(file.value, {
      patientId: patientId.value.trim() || undefined,
      laterality: laterality.value || undefined,
      studyDate: studyDate.value || undefined,
    })
    done.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    uploading.value = false
  }
}

function reset(): void {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  file.value = null
  previewUrl.value = null
  patientId.value = ''
  laterality.value = ''
  studyDate.value = ''
  resolveState.value = null
  done.value = false
  error.value = null
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-slate-50">
    <header class="h-14 border-b border-slate-200 bg-white flex items-center px-4 sm:px-6 shrink-0">
      <span class="muw-display font-semibold text-muw-blue tracking-tight text-[17px]">LibreClinica<em class="not-italic font-medium text-muw-coral-700 text-[0.66em] uppercase tracking-[0.08em] ml-1.5 align-middle">{{ t('imagePortal.brandSuffix') }}</em></span>
      <span class="ml-2 pl-3 border-l border-slate-200 text-[13px] font-medium text-slate-500">{{ t('imagePortal.portalLabel') }}</span>
      <span class="ml-auto hidden sm:inline-flex items-center gap-1.5 rounded-full bg-muw-teal-50 text-muw-teal-700 text-[12px] font-medium px-2.5 py-1">
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect width="14" height="9" x="5" y="11" rx="2" /><path d="M8 11V7a4 4 0 0 1 7.5-2" /></svg>
        {{ t('imagePortal.noLoginRequired') }}
      </span>
    </header>

    <main class="flex-1 flex items-start justify-center px-4 py-6">
      <div class="w-full max-w-lg">
        <div v-if="done" class="bg-white rounded-2xl ring-1 ring-slate-200 p-8 text-center" data-testid="upload-success">
          <div class="w-14 h-14 rounded-full bg-muw-teal-50 text-muw-teal-700 flex items-center justify-center mx-auto mb-4">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
          </div>
          <div class="text-[18px] font-semibold text-slate-800">{{ t('imagePortal.successTitle') }}</div>
          <div class="text-[13px] text-slate-500 mt-2">{{ t('imagePortal.successBody') }}</div>
          <button type="button" class="mt-6 px-4 py-2 rounded-lg bg-muw-blue text-white text-[14px] font-medium hover:bg-muw-blue-700" @click="reset">{{ t('imagePortal.uploadAnother') }}</button>
        </div>

        <form v-else class="bg-white rounded-2xl ring-1 ring-slate-200 p-5 sm:p-6" @submit.prevent="onUpload">
          <ImageDropzone v-if="!file" @file-selected="onFileSelected" />

          <div v-else class="relative rounded-2xl overflow-hidden ring-1 ring-slate-200 bg-slate-100">
            <img :src="previewUrl ?? ''" alt="" class="w-full max-h-72 object-contain" data-testid="image-preview" />
            <button type="button" class="absolute top-2 right-2 w-8 h-8 rounded-full bg-white/90 ring-1 ring-slate-200 text-slate-600 flex items-center justify-center hover:bg-white" :aria-label="t('imagePortal.uploadAnother')" @click="reset">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12" /></svg>
            </button>
          </div>

          <div class="mt-5 space-y-4">
            <div>
              <label class="block text-[13px] font-medium text-slate-600 mb-1" for="ip-patient">{{ t('imagePortal.patientIdLabel') }}</label>
              <input id="ip-patient" v-model="patientId" type="text" inputmode="text" autocomplete="off" :placeholder="t('imagePortal.patientIdPlaceholder')" class="w-full px-3 py-2.5 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100" @blur="onPatientBlur" />
              <p v-if="resolveState === 'suggested' || resolveState === 'novisit'" class="mt-1.5 text-[12px] text-muw-teal-700" data-testid="resolve-found">{{ t('imagePortal.patientFound', { label: resolveLabel, study: resolveStudy }) }}</p>
              <p v-else-if="resolveState === 'ambiguous'" class="mt-1.5 text-[12px] text-amber-700">{{ t('imagePortal.patientAmbiguous') }}</p>
              <p v-else-if="resolveState === 'nopatient'" class="mt-1.5 text-[12px] text-slate-500">{{ t('imagePortal.patientNotFound') }}</p>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-[13px] font-medium text-slate-600 mb-1" for="ip-lat">{{ t('imagePortal.lateralityLabel') }}</label>
                <select id="ip-lat" v-model="laterality" class="w-full px-3 py-2.5 border border-slate-300 rounded-lg bg-white focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100">
                  <option value="">—</option>
                  <option value="OD">OD</option>
                  <option value="OS">OS</option>
                  <option value="OU">OU</option>
                </select>
              </div>
              <div>
                <label class="block text-[13px] font-medium text-slate-600 mb-1" for="ip-date">{{ t('imagePortal.dateLabel') }}</label>
                <input id="ip-date" v-model="studyDate" type="date" class="w-full px-3 py-2.5 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100" />
              </div>
            </div>
          </div>

          <p v-if="error" class="mt-4 text-[13px] text-rose-700" data-testid="upload-error">{{ t('imagePortal.errorTitle') }}: {{ error }}</p>

          <button type="submit" class="mt-6 w-full px-4 py-3 rounded-xl bg-muw-blue text-white text-[15px] font-semibold hover:bg-muw-blue-700 disabled:bg-slate-300 disabled:cursor-not-allowed" :disabled="!canUpload" data-testid="upload-submit">
            {{ uploading ? t('imagePortal.uploading') : t('imagePortal.uploadButton') }}
          </button>
        </form>
      </div>
    </main>

    <footer class="border-t border-slate-200 bg-white px-4 sm:px-6 py-3 text-[12px] text-slate-400 shrink-0">
      {{ t('imagePortal.formatNote') }}
    </footer>
  </div>
</template>

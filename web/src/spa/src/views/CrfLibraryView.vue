<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import CrfAuthoringWizard from '@/components/CrfAuthoringWizard.vue'

import { useCrfLibraryStore } from '@/stores/crfLibrary'
import { useAuthStore } from '@/stores/auth'
import type { Crf } from '@/types/crfLibrary'

/**
 * Phase E A8.3 — CRF library view.
 *
 * Lists every CRF in the library with its versions inlined; lets the
 * operator create a new CRF shell, upload a new .xls/.xlsx version,
 * disable a CRF or one of its versions.
 *
 * The legacy spreadsheet parser is NOT yet wired — uploads land on
 * disk and the `crf_version` row is persisted, but the items list
 * stays empty until the follow-up B-series parser adapter ships.
 * The SPA surfaces this caveat in the upload dialog.
 *
 * Role-gated client-side to Administrator + Data Manager + CRC;
 * backend re-checks (sysadmin / director / coordinator triad).
 */
const { t } = useI18n()
const lib = useCrfLibraryStore()
const auth = useAuthStore()

const canManage = computed(() => {
  const role = auth.user?.role
  return role === 'Administrator' || role === 'Data Manager' || role === 'CRC'
})
const includeRemoved = ref(false)

onMounted(() => lib.loadCrfs(includeRemoved.value))

function refresh() { lib.loadCrfs(includeRemoved.value) }

/* ----------------------------- Create ----------------------------- */
const createOpen = ref(false)
const createForm = ref({ name: '', description: '' })
const createErrors = ref<Record<string, string>>({})
const createFormError = ref<string | null>(null)
const isCreating = ref(false)

function openCreate() {
  createForm.value = { name: '', description: '' }
  createErrors.value = {}
  createFormError.value = null
  createOpen.value = true
}

async function submitCreate() {
  if (createForm.value.name.trim() === '') return
  createErrors.value = {}
  createFormError.value = null
  isCreating.value = true
  try {
    const result = await lib.createCrf({
      name: createForm.value.name.trim(),
      description: createForm.value.description.trim() || undefined,
    })
    if (result.ok) {
      createOpen.value = false
    } else {
      createErrors.value = result.fieldErrors
      createFormError.value = result.message ?? null
    }
  } finally {
    isCreating.value = false
  }
}

/* --------------------------- Upload version ----------------------- */
interface UploadState {
  crfOid: string
  crfName: string
  file: File | null
  versionName: string
  versionDescription: string
  revisionNotes: string
}
const uploading = ref<UploadState | null>(null)
const uploadErrors = ref<Record<string, string>>({})
const uploadParseErrors = ref<string[]>([])
const uploadFormError = ref<string | null>(null)
const isUploading = ref(false)

function openUpload(crf: Crf) {
  uploading.value = {
    crfOid: crf.oid,
    crfName: crf.name,
    file: null,
    versionName: '',
    versionDescription: '',
    revisionNotes: '',
  }
  uploadErrors.value = {}
  uploadParseErrors.value = []
  uploadFormError.value = null
}

function onFileChange(ev: Event) {
  if (!uploading.value) return
  const target = ev.target as HTMLInputElement
  uploading.value.file = target.files && target.files.length > 0 ? target.files[0] : null
}

async function submitUpload() {
  if (!uploading.value || !uploading.value.file || uploading.value.versionName.trim() === '') return
  uploadErrors.value = {}
  uploadParseErrors.value = []
  uploadFormError.value = null
  isUploading.value = true
  try {
    const result = await lib.uploadVersion(uploading.value.crfOid, {
      file: uploading.value.file,
      versionName: uploading.value.versionName.trim(),
      versionDescription: uploading.value.versionDescription.trim() || undefined,
      revisionNotes: uploading.value.revisionNotes.trim() || undefined,
    })
    if (result.ok) {
      uploading.value = null
    } else {
      uploadErrors.value = result.fieldErrors
      uploadParseErrors.value = result.parseErrors
      uploadFormError.value = result.message ?? null
    }
  } finally {
    isUploading.value = false
  }
}

/* --------------------------- Author wizard ------------------------ */
// Phase E.6 Milestone A — JSON-body wizard launched per-CRF from the
// list. On close, reload the library so the freshly-authored version
// appears under its parent CRF.
interface AuthoringState { crfOid: string; crfName: string }
const authoring = ref<AuthoringState | null>(null)
function openAuthoring(crf: Crf) {
  authoring.value = { crfOid: crf.oid, crfName: crf.name }
}
function closeAuthoring() {
  authoring.value = null
  refresh()
}

/* ----------------------------- Disable ---------------------------- */
async function onDisableCrf(crf: Crf) {
  if (!confirm(t('crfLibrary.disableConfirm', { name: crf.name }))) return
  await lib.disableCrf(crf.oid)
}

async function onDisableVersion(crf: Crf, versionOid: string, versionName: string) {
  if (!confirm(t('crfLibrary.disableVersionConfirm', { name: crf.name, version: versionName }))) return
  await lib.disableVersion(crf.oid, versionOid)
}

/* ----------------------- Version lifecycle ------------------------ */
// Phase E.6 crf-library — per-version Lock / Unlock / Restore /
// Hard-remove + Download. All confirm() driven for now; the harmonizer
// will replace the hard-remove flow with a structured modal that
// renders the VersionUsageReport on 409.

const isSysadmin = computed(() => auth.user?.role === 'Administrator')

async function onLockVersion(crf: Crf, versionOid: string, versionName: string) {
  if (!confirm(t('crfLibrary.lockConfirm', { name: crf.name, version: versionName }))) return
  await lib.lockVersion(crf.oid, versionOid)
}

async function onUnlockVersion(crf: Crf, versionOid: string, versionName: string) {
  if (!confirm(t('crfLibrary.unlockConfirm', { name: crf.name, version: versionName }))) return
  await lib.unlockVersion(crf.oid, versionOid)
}

async function onRestoreVersion(crf: Crf, versionOid: string, versionName: string) {
  if (!confirm(t('crfLibrary.restoreConfirm', { name: crf.name, version: versionName }))) return
  await lib.restoreVersion(crf.oid, versionOid)
}

const hardRemoveBlocker = ref<{
  crfName: string
  report: import('@/types/crfLibrary').VersionUsageReport
} | null>(null)

async function onHardRemoveVersion(crf: Crf, versionOid: string, versionName: string) {
  if (!confirm(t('crfLibrary.hardRemoveConfirm', { name: crf.name, version: versionName }))) return
  const result = await lib.hardRemoveVersion(crf.oid, versionOid)
  if (result.ok) {
    // success — list patched in-place.
    return
  }
  if ('blocker' in result) {
    hardRemoveBlocker.value = { crfName: crf.name, report: result.blocker }
    return
  }
  alert(result.message)
}

async function onDownloadXls(crf: Crf, versionOid: string) {
  const result = await lib.downloadVersionXls(crf.oid, versionOid)
  if (!result.ok) {
    alert(t('crfLibrary.downloadXlsFailed', { message: result.message }))
    return
  }
  // Trigger browser download via a transient anchor.
  const url = URL.createObjectURL(result.blob)
  const a = document.createElement('a')
  a.href = url
  a.download = result.filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const visibleRows = computed(() =>
  includeRemoved.value ? lib.crfs : lib.crfs.filter((c) => c.status !== 'removed'),
)
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.buildStudy') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-5xl px-8 py-6">
      <div class="mb-4 flex items-end justify-between gap-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('crfLibrary.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('crfLibrary.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('crfLibrary.intro') }}</p>
        </div>
        <div class="flex items-center gap-3">
          <label class="text-xs text-slate-600 inline-flex items-center gap-1.5">
            <input type="checkbox" v-model="includeRemoved" @change="refresh" />
            {{ t('crfLibrary.includeRemoved') }}
          </label>
          <button
            v-if="canManage"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
            @click="openCreate"
          >
            {{ t('crfLibrary.createAction') }}
          </button>
        </div>
      </div>

      <div class="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-xs text-emerald-900 mb-4">
        {{ t('crfLibrary.parserActiveNote') }}
      </div>

      <p v-if="lib.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="lib.error" class="text-rose-700">{{ lib.error }}</p>
      <p v-else-if="visibleRows.length === 0" class="text-slate-500 italic">{{ t('crfLibrary.empty') }}</p>

      <ul v-else class="space-y-3">
        <li
          v-for="crf in visibleRows"
          :key="crf.oid"
          class="bg-white border border-slate-200 rounded-muw p-4"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="flex-1 min-w-0">
              <div class="flex items-baseline gap-2">
                <h3 class="font-medium text-slate-900">{{ crf.name }}</h3>
                <span class="text-[10px] text-slate-400 font-mono">{{ crf.oid }}</span>
                <StatusPill
                  v-if="crf.status === 'removed'"
                  variant="neutral"
                >{{ t('crfLibrary.statusRemoved') }}</StatusPill>
              </div>
              <p v-if="crf.description" class="text-xs text-slate-500 mt-1">{{ crf.description }}</p>
            </div>
            <div v-if="canManage && crf.status !== 'removed'" class="flex items-center gap-2 text-xs">
              <button
                class="text-muw-blue hover:underline"
                @click="openUpload(crf)"
              >{{ t('crfLibrary.uploadVersion') }}</button>
              <span class="text-slate-300">·</span>
              <button
                class="text-muw-blue hover:underline"
                @click="openAuthoring(crf)"
              >{{ t('crfLibrary.authorManually') }}</button>
              <span class="text-slate-300">·</span>
              <button class="text-rose-600 hover:underline" @click="onDisableCrf(crf)">
                {{ t('crfLibrary.disable') }}
              </button>
            </div>
          </div>

          <div v-if="crf.versions.length > 0" class="mt-3 border-t border-slate-100 pt-2">
            <div class="text-[10px] uppercase tracking-wide text-slate-500 mb-1">{{ t('crfLibrary.versions') }}</div>
            <ul class="space-y-1.5">
              <li
                v-for="v in crf.versions"
                :key="v.oid"
                class="flex items-center gap-3 text-xs"
              >
                <span class="font-mono text-slate-700">{{ v.name }}</span>
                <span class="text-slate-400">{{ v.oid }}</span>
                <StatusPill v-if="v.status === 'removed'" variant="neutral">{{ t('crfLibrary.statusRemoved') }}</StatusPill>
                <StatusPill v-else-if="v.status === 'locked'" variant="neutral">{{ t('crfLibrary.lock') }}</StatusPill>
                <span v-if="v.description" class="text-slate-500 truncate">{{ v.description }}</span>
                <span class="ml-auto" />
                <button
                  class="text-muw-blue hover:underline"
                  @click="onDownloadXls(crf, v.oid)"
                >{{ t('crfLibrary.downloadXls') }}</button>
                <template v-if="canManage">
                  <button
                    v-if="v.status === 'available'"
                    class="text-muw-blue hover:underline"
                    @click="onLockVersion(crf, v.oid, v.name)"
                  >{{ t('crfLibrary.lock') }}</button>
                  <button
                    v-if="v.status === 'locked'"
                    class="text-muw-blue hover:underline"
                    @click="onUnlockVersion(crf, v.oid, v.name)"
                  >{{ t('crfLibrary.unlock') }}</button>
                  <button
                    v-if="v.status === 'removed' || v.status === 'auto-removed'"
                    class="text-muw-blue hover:underline"
                    @click="onRestoreVersion(crf, v.oid, v.name)"
                  >{{ t('crfLibrary.restore') }}</button>
                  <button
                    v-if="v.status !== 'removed'"
                    class="text-rose-600 hover:underline"
                    @click="onDisableVersion(crf, v.oid, v.name)"
                  >{{ t('crfLibrary.disable') }}</button>
                  <button
                    v-if="isSysadmin"
                    class="text-rose-800 hover:underline"
                    @click="onHardRemoveVersion(crf, v.oid, v.name)"
                  >{{ t('crfLibrary.hardRemove') }}</button>
                </template>
              </li>
            </ul>
          </div>
        </li>
      </ul>

      <!-- Create form -->
      <div v-if="createOpen" class="mt-6 rounded-md border border-slate-200 bg-white p-4">
        <h2 class="text-sm font-semibold mb-3">{{ t('crfLibrary.createHeading') }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="crf-create-name" required>{{ t('crfLibrary.crfName') }}</FieldLabel>
            <TextInput id="crf-create-name" v-model="createForm.name" />
            <ErrorText v-if="createErrors.name">{{ createErrors.name }}</ErrorText>
          </div>
          <div class="col-span-2">
            <FieldLabel for="crf-create-desc">{{ t('crfLibrary.crfDescription') }}</FieldLabel>
            <TextInput id="crf-create-desc" v-model="createForm.description" />
          </div>
        </div>
        <ErrorText v-if="createFormError">{{ createFormError }}</ErrorText>
        <div class="mt-3 flex items-center gap-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="createOpen = false"
          >{{ t('common.cancel') }}</button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="createForm.name.trim() === '' || isCreating"
            @click="submitCreate"
          >{{ isCreating ? t('common.saving') : t('crfLibrary.submitCreate') }}</button>
        </div>
      </div>

      <!-- Upload form -->
      <div v-if="uploading" class="mt-6 rounded-md border border-amber-200 bg-amber-50 p-4">
        <h2 class="text-sm font-semibold mb-1">
          {{ t('crfLibrary.uploadHeading', { name: uploading.crfName }) }}
        </h2>
        <p class="text-xs text-slate-500 mb-3">{{ t('crfLibrary.uploadIntro') }}</p>
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="crf-upload-file" required>{{ t('crfLibrary.fileLabel') }}</FieldLabel>
            <input
              id="crf-upload-file"
              type="file"
              accept=".xls,.xlsx"
              class="block w-full text-xs text-slate-700"
              @change="onFileChange"
            />
            <ErrorText v-if="uploadErrors.file" class="whitespace-pre-line">{{ uploadErrors.file }}</ErrorText>
            <div v-if="uploadParseErrors.length > 0" class="mt-2 rounded-md border border-rose-200 bg-rose-50 p-2 text-xs text-rose-800">
              <div class="font-medium mb-1">{{ t('crfLibrary.parseErrorsHeading', { count: uploadParseErrors.length }) }}</div>
              <ul class="list-disc pl-4 space-y-0.5">
                <li v-for="(msg, i) in uploadParseErrors" :key="i">{{ msg }}</li>
              </ul>
            </div>
          </div>
          <div>
            <FieldLabel for="crf-upload-vname" required>{{ t('crfLibrary.versionName') }}</FieldLabel>
            <TextInput id="crf-upload-vname" v-model="uploading.versionName" />
            <ErrorText v-if="uploadErrors.versionName">{{ uploadErrors.versionName }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="crf-upload-revision">{{ t('crfLibrary.revisionNotes') }}</FieldLabel>
            <TextInput id="crf-upload-revision" v-model="uploading.revisionNotes" />
          </div>
          <div class="col-span-2">
            <FieldLabel for="crf-upload-desc">{{ t('crfLibrary.versionDescription') }}</FieldLabel>
            <TextInput id="crf-upload-desc" v-model="uploading.versionDescription" />
          </div>
        </div>
        <ErrorText v-if="uploadFormError">{{ uploadFormError }}</ErrorText>
        <div class="mt-3 flex items-center gap-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="uploading = null"
          >{{ t('common.cancel') }}</button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="!uploading.file || uploading.versionName.trim() === '' || isUploading"
            @click="submitUpload"
          >{{ isUploading ? t('common.saving') : t('crfLibrary.submitUpload') }}</button>
        </div>
      </div>
    </main>

    <CrfAuthoringWizard
      v-if="authoring"
      :open="true"
      :crf-oid="authoring.crfOid"
      :crf-name="authoring.crfName"
      @close="closeAuthoring"
    />

    <!-- Hard-remove blocker modal — renders the 409 VersionUsageReport
         when a hard-remove can't proceed. Migrate-dialog wiring lives
         here in the follow-up sub-chunk; for now we surface the report
         + a remediation hint pointing the operator at the legacy
         /pages/* paths the LegacyServletRegistry keeps reachable for
         parallel-run reconciliation. -->
    <div
      v-if="hardRemoveBlocker"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      @click.self="hardRemoveBlocker = null"
    >
      <div class="bg-white rounded-muw max-w-xl w-full p-5 shadow-xl">
        <h2 class="text-sm font-semibold mb-2 text-rose-800">
          {{ t('crfLibrary.hardRemoveBlocked', { version: hardRemoveBlocker.report.versionName }) }}
        </h2>
        <div v-if="hardRemoveBlocker.report.blockingEventDefinitions.length > 0" class="mb-3 text-xs text-slate-700">
          <p class="mb-1">{{ t('crfLibrary.hardRemoveBlocker.eventDefs', { count: hardRemoveBlocker.report.blockingEventDefinitions.length }) }}</p>
          <ul class="list-disc pl-5 space-y-0.5">
            <li v-for="r in hardRemoveBlocker.report.blockingEventDefinitions" :key="r.sedOid">
              <span class="font-mono">{{ r.sedOid }}</span> · {{ r.sedName }}
              <span v-if="r.studyOid" class="text-slate-400">({{ r.studyOid }})</span>
            </li>
          </ul>
        </div>
        <div v-if="hardRemoveBlocker.report.eventCrfCount > 0" class="mb-3 text-xs text-slate-700">
          <p>{{ t('crfLibrary.hardRemoveBlocker.eventCrfs', {
            count: hardRemoveBlocker.report.eventCrfCount,
            sample: hardRemoveBlocker.report.sampleSubjectLabels.join(', ') || '—',
          }) }}</p>
        </div>
        <p class="text-xs text-slate-500 italic mb-4">
          {{ t('crfLibrary.hardRemoveBlocker.remediation') }}
        </p>
        <div class="flex items-center justify-end gap-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="hardRemoveBlocker = null"
          >{{ t('common.cancel') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

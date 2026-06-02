<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import StatusPill from '@/components/StatusPill.vue'

import {
  useRulesStore,
  type RulesImportPreview,
  type RulesImportCommit,
} from '@/stores/rules'

/**
 * Phase E RX.2 — XML rules import dialog (3 steps).
 *
 * 1. <b>Upload</b> — file picker + "Download annotated template"
 *    helper + Upload button. POSTs to {@code /api/v1/rules/import}
 *    via the store.
 * 2. <b>Preview</b> — counts (valid / duplicate / invalid for rules
 *    + rule sets), issues list with severity colours, "Ignore
 *    duplicates" checkbox, Commit / Cancel.
 * 3. <b>Result</b> — counts of what landed in the DB, Close button.
 *    On close the parent reloads its list via {@code rules.load()}.
 *
 * The legacy import path stays available (the "Designer" XML and
 * the JSP upload form continue to work); this dialog is a SPA-side
 * lift of that flow with preview-then-confirm semantics.
 */
interface Props { open: boolean }
const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [v: boolean]
  close: []
  /** Emitted after a successful commit + close; parent should reload the rules list. */
  committed: []
}>()

const { t } = useI18n()
const rules = useRulesStore()

type Step = 'upload' | 'preview' | 'result'
const step = ref<Step>('upload')

const selectedFile = ref<File | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const isUploading = ref(false)
const uploadError = ref<string | null>(null)
const uploadFieldErrors = ref<string[]>([])

const preview = ref<RulesImportPreview | null>(null)
const ignoreDuplicates = ref(false)
const isCommitting = ref(false)
const commitError = ref<string | null>(null)

const result = ref<RulesImportCommit | null>(null)

// Reset every time the dialog re-opens so a previous run's state
// doesn't bleed in (the operator may have cancelled half-way).
watch(
  () => props.open,
  (next) => {
    if (next) resetAll()
  },
)

function resetAll() {
  step.value = 'upload'
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
  isUploading.value = false
  uploadError.value = null
  uploadFieldErrors.value = []
  preview.value = null
  ignoreDuplicates.value = false
  isCommitting.value = false
  commitError.value = null
  result.value = null
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  selectedFile.value = input.files && input.files[0] ? input.files[0] : null
  uploadError.value = null
  uploadFieldErrors.value = []
}

const canUpload = computed(() => selectedFile.value != null && !isUploading.value)

async function downloadTemplate() {
  // Plain fetch — the dev proxy forwards `/LibreClinica/...` and
  // prod serves the SPA from the same origin. The endpoint already
  // sets a Content-Disposition so the browser triggers a save when
  // the response is opened in a new tab, but we go via blob URL +
  // anchor click so the legacy "Download annotated template" UX
  // stays a one-click action even when the response isn't 200.
  try {
    const res = await fetch('/LibreClinica/pages/api/v1/rules/template', {
      credentials: 'include',
    })
    if (!res.ok) {
      uploadError.value = t('rules.import.templateError', { status: res.status })
      return
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'rules_template_with_notes.xml'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e) {
    uploadError.value = e instanceof Error ? e.message : t('rules.import.templateError', { status: '?' })
  }
}

async function onUpload() {
  if (selectedFile.value == null || isUploading.value) return
  isUploading.value = true
  uploadError.value = null
  uploadFieldErrors.value = []
  try {
    const res = await rules.uploadRulesXml(selectedFile.value)
    if (!res.ok) {
      uploadError.value = res.message
      uploadFieldErrors.value = res.errors
      return
    }
    preview.value = res.preview
    step.value = 'preview'
  } finally {
    isUploading.value = false
  }
}

async function onCommit() {
  if (preview.value == null || isCommitting.value) return
  isCommitting.value = true
  commitError.value = null
  try {
    const res = await rules.commitImport(preview.value.previewToken, ignoreDuplicates.value)
    if (!res.ok) {
      commitError.value = res.message
      return
    }
    result.value = res.result
    step.value = 'result'
  } finally {
    isCommitting.value = false
  }
}

function onCancel() {
  emit('update:open', false)
  emit('close')
}

function onClose() {
  emit('update:open', false)
  emit('close')
  // Tell the parent to refresh — only if we actually committed.
  if (result.value != null) emit('committed')
}

const previewSummary = computed(() => {
  const p = preview.value
  if (p == null) return ''
  return t('rules.import.preview.summary', {
    validRules: p.validRuleCount,
    duplicateRules: p.duplicateRuleCount,
    invalidRules: p.invalidRuleCount,
    validRuleSets: p.validRuleSetCount,
    duplicateRuleSets: p.duplicateRuleSetCount,
    invalidRuleSets: p.invalidRuleSetCount,
  })
})

const headingKey = computed(() => {
  switch (step.value) {
    case 'upload': return 'rules.import.heading.upload'
    case 'preview': return 'rules.import.heading.preview'
    case 'result': return 'rules.import.heading.result'
    default: return 'rules.import.heading.upload'
  }
})
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="rules-import-heading"
    panel-class="max-w-3xl"
    @update:open="(v) => emit('update:open', v)"
    @close="onCancel"
  >
    <template #header>
      <h2 id="rules-import-heading" class="text-base font-semibold tracking-tight">
        {{ t(headingKey) }}
      </h2>
    </template>

    <!-- ---------------- Step 1: Upload ---------------- -->
    <div v-if="step === 'upload'" class="space-y-4">
      <p class="text-xs text-slate-600 leading-relaxed">
        {{ t('rules.import.parserActiveNote') }}
      </p>

      <div>
        <button
          type="button"
          class="text-xs px-2 py-1 border border-slate-200 rounded-md text-muw-blue hover:bg-muw-blue-50"
          @click="downloadTemplate"
        >
          {{ t('rules.import.downloadTemplate') }}
        </button>
      </div>

      <div>
        <label class="block text-xs text-slate-600 mb-1" for="rules-import-file">
          {{ t('rules.import.fileLabel') }}
        </label>
        <input
          id="rules-import-file"
          ref="fileInputRef"
          type="file"
          accept=".xml,application/xml,text/xml"
          class="block w-full text-xs"
          @change="onFileChange"
        />
      </div>

      <p v-if="uploadError" class="text-xs text-rose-700">{{ uploadError }}</p>
      <ul v-if="uploadFieldErrors.length > 0" class="text-[11px] text-rose-700 list-disc ml-4 space-y-1">
        <li v-for="(err, i) in uploadFieldErrors" :key="i">{{ err }}</li>
      </ul>
    </div>

    <!-- ---------------- Step 2: Preview ---------------- -->
    <div v-else-if="step === 'preview' && preview != null" class="space-y-4">
      <p class="text-xs text-slate-700">{{ previewSummary }}</p>

      <div class="grid grid-cols-3 gap-2 text-xs">
        <div class="rounded-md border border-emerald-200 bg-emerald-50 p-2">
          <div class="text-[10px] uppercase tracking-wider text-emerald-700">{{ t('rules.import.preview.label.validRules') }}</div>
          <div class="text-base font-semibold text-emerald-900">{{ preview.validRuleCount }}</div>
        </div>
        <div class="rounded-md border border-amber-200 bg-amber-50 p-2">
          <div class="text-[10px] uppercase tracking-wider text-amber-700">{{ t('rules.import.preview.label.duplicateRules') }}</div>
          <div class="text-base font-semibold text-amber-900">{{ preview.duplicateRuleCount }}</div>
        </div>
        <div class="rounded-md border border-rose-200 bg-rose-50 p-2">
          <div class="text-[10px] uppercase tracking-wider text-rose-700">{{ t('rules.import.preview.label.invalidRules') }}</div>
          <div class="text-base font-semibold text-rose-900">{{ preview.invalidRuleCount }}</div>
        </div>
        <div class="rounded-md border border-emerald-200 bg-emerald-50 p-2">
          <div class="text-[10px] uppercase tracking-wider text-emerald-700">{{ t('rules.import.preview.label.validRuleSets') }}</div>
          <div class="text-base font-semibold text-emerald-900">{{ preview.validRuleSetCount }}</div>
        </div>
        <div class="rounded-md border border-amber-200 bg-amber-50 p-2">
          <div class="text-[10px] uppercase tracking-wider text-amber-700">{{ t('rules.import.preview.label.duplicateRuleSets') }}</div>
          <div class="text-base font-semibold text-amber-900">{{ preview.duplicateRuleSetCount }}</div>
        </div>
        <div class="rounded-md border border-rose-200 bg-rose-50 p-2">
          <div class="text-[10px] uppercase tracking-wider text-rose-700">{{ t('rules.import.preview.label.invalidRuleSets') }}</div>
          <div class="text-base font-semibold text-rose-900">{{ preview.invalidRuleSetCount }}</div>
        </div>
      </div>

      <div v-if="preview.issues.length > 0">
        <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold mb-1">
          {{ t('rules.import.preview.issuesHeading', { count: preview.issues.length }) }}
        </div>
        <ul class="max-h-56 overflow-y-auto space-y-1 text-xs">
          <li
            v-for="(issue, i) in preview.issues"
            :key="i"
            :class="[
              'rounded-md border p-2 flex items-start gap-2',
              issue.severity === 'ERROR'
                ? 'border-rose-200 bg-rose-50'
                : 'border-amber-200 bg-amber-50',
            ]"
          >
            <StatusPill
              :variant="issue.severity === 'ERROR' ? 'warning' : 'neutral'"
              class="shrink-0"
            >
              {{ t(`rules.import.preview.severity.${issue.severity}`) }}
            </StatusPill>
            <div class="flex-1 min-w-0">
              <div class="text-[10px] text-slate-500">
                {{ issue.scope }} · <code class="font-mono">{{ issue.identifier || '—' }}</code>
              </div>
              <div class="mt-0.5 text-xs whitespace-pre-wrap break-words">{{ issue.message }}</div>
            </div>
          </li>
        </ul>
      </div>

      <label class="flex items-start gap-2 text-xs text-slate-700">
        <input v-model="ignoreDuplicates" type="checkbox" class="mt-0.5" />
        <span>{{ t('rules.import.ignoreDuplicates') }}</span>
      </label>

      <p v-if="commitError" class="text-xs text-rose-700">{{ commitError }}</p>
    </div>

    <!-- ---------------- Step 3: Result ---------------- -->
    <div v-else-if="step === 'result' && result != null" class="space-y-3">
      <p class="text-xs text-slate-700">
        {{ t('rules.import.result.summary') }}
      </p>
      <ul class="text-xs space-y-1 list-disc ml-4">
        <li>{{ t('rules.import.result.rulesCreated', { count: result.rulesCreated }) }}</li>
        <li>{{ t('rules.import.result.rulesReplaced', { count: result.rulesReplaced }) }}</li>
        <li>{{ t('rules.import.result.ruleSetsCreated', { count: result.ruleSetsCreated }) }}</li>
        <li>{{ t('rules.import.result.ruleSetsReplaced', { count: result.ruleSetsReplaced }) }}</li>
        <li>{{ t('rules.import.result.actionsCreated', { count: result.actionsCreated }) }}</li>
      </ul>
      <p class="text-[10px] text-slate-500">
        {{ t('rules.import.result.committedAt', { ts: result.committedAt }) }}
      </p>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <!-- Step 1 -->
        <template v-if="step === 'upload'">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md hover:bg-slate-50"
            @click="onCancel"
          >
            {{ t('rules.import.cancelButton') }}
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90 disabled:opacity-40"
            :disabled="!canUpload"
            @click="onUpload"
          >
            {{ isUploading ? t('common.loading') : t('rules.import.uploadButton') }}
          </button>
        </template>

        <!-- Step 2 -->
        <template v-else-if="step === 'preview'">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md hover:bg-slate-50"
            @click="onCancel"
          >
            {{ t('rules.import.cancelButton') }}
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90 disabled:opacity-40"
            :disabled="isCommitting"
            @click="onCommit"
          >
            {{ isCommitting ? t('common.loading') : t('rules.import.commitButton') }}
          </button>
        </template>

        <!-- Step 3 -->
        <template v-else-if="step === 'result'">
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90"
            @click="onClose"
          >
            {{ t('rules.import.closeButton') }}
          </button>
        </template>
      </div>
    </template>
  </Modal>
</template>

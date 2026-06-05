<script setup lang="ts">
/**
 * Phase E.6 — Data Export MVP.
 *
 * Surfaces the legacy ExportDataset stack in the SPA:
 *   - Side rail with Build Study / Manage Users / Data Export (active).
 *   - Top toolbar with "Quick ODM export" button.
 *   - Table of saved datasets for the active study.
 *   - Per-row "Export now" (opens a format-picker modal) + "View files"
 *     (expands a sub-row with download links).
 *
 * <p>The MVP does NOT replace the legacy /Extract Data UI — it only
 * surfaces datasets the operator created via that wizard. An empty
 * table links out to the legacy /Extract Data path with a note.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import { useAuthStore } from '@/stores/auth'
import { useDatasetsStore } from '@/stores/datasets'
import type { ExportFormat } from '@/types/export'

const { t } = useI18n()
const auth = useAuthStore()
const datasets = useDatasetsStore()

const studyOid = computed(() => auth.user?.activeStudy?.oid ?? '')

onMounted(() => {
  if (studyOid.value) datasets.load(studyOid.value)
})

watch(studyOid, (next, prev) => {
  if (next && next !== prev) datasets.load(next)
})

/* --------------------- Per-row expand / file list --------------------- */

const expanded = ref<Set<number>>(new Set())

function toggleExpanded(datasetId: number) {
  const next = new Set(expanded.value)
  if (next.has(datasetId)) {
    next.delete(datasetId)
  } else {
    next.add(datasetId)
    if (!datasets.filesByDataset.has(datasetId) && studyOid.value) {
      void datasets.loadFiles(studyOid.value, datasetId)
    }
  }
  expanded.value = next
}

/* --------------------------- Export modal --------------------------- */

interface ExportModalState {
  datasetId: number
  datasetName: string
  format: ExportFormat
  error: string | null
}
const exportModal = ref<ExportModalState | null>(null)

function openExportModal(datasetId: number, datasetName: string) {
  exportModal.value = {
    datasetId,
    datasetName,
    format: 'odm',
    error: null,
  }
}

const EXPORT_FORMATS: ExportFormat[] = ['odm', 'csv', 'tsv', 'excel', 'sas', 'spss']

async function submitExport() {
  if (!exportModal.value || !studyOid.value) return
  exportModal.value.error = null
  const result = await datasets.triggerExport(
    studyOid.value,
    exportModal.value.datasetId,
    exportModal.value.format,
  )
  if (result) {
    // Close modal on success; the per-row files cache invalidates +
    // re-fetches automatically, so the new file lands in the expanded
    // sub-table without operator action.
    // Auto-trigger the download in a new tab.
    window.open(result.downloadUrl, '_blank', 'noopener,noreferrer')
    exportModal.value = null
  } else if (datasets.error) {
    exportModal.value.error = datasets.error
  }
}

/* ---------------------------- Quick ODM ---------------------------- */

async function runQuickOdm() {
  if (!studyOid.value) return
  const result = await datasets.quickOdm(studyOid.value)
  if (result) {
    window.open(result.downloadUrl, '_blank', 'noopener,noreferrer')
  }
}

/* ----------------------------- Helpers ----------------------------- */

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.valueOf())) return '—'
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: '2-digit' })
}

function formatBytes(n: number): string {
  if (!n || n < 0) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

/** Hard link to the legacy /Extract Data wizard. */
const legacyCreateLink = '/LibreClinica/CreateDataset'
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
      <RouterLink to="/export" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M12 3v12M6 9l6 6 6-6M5 21h14" />
        </svg>
        {{ t('nav.dataExport') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-5xl px-8 py-8">
      <div class="mb-6 flex items-start justify-between gap-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('dataExport.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('dataExport.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('dataExport.intro') }}</p>
        </div>
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="datasets.isQuickOdm || !studyOid"
            @click="runQuickOdm"
          >
            {{ datasets.isQuickOdm ? t('dataExport.quickOdmRunning') : t('dataExport.quickOdmButton') }}
          </button>
        </div>
      </div>

      <p v-if="datasets.error" class="mb-4 text-rose-700 text-sm" role="alert">{{ datasets.error }}</p>
      <p v-if="datasets.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>

      <!-- Empty state. -->
      <section
        v-else-if="datasets.rows.length === 0"
        class="bg-white border border-slate-200 rounded-muw p-8 text-center"
      >
        <p class="text-slate-700">{{ t('dataExport.emptyTitle') }}</p>
        <p class="text-xs text-slate-500 mt-2">{{ t('dataExport.emptyDescription') }}</p>
        <a
          :href="legacyCreateLink"
          class="inline-flex items-center gap-1.5 mt-4 px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-muw-blue"
          target="_self"
        >
          {{ t('dataExport.openLegacyWizard') }}
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <path d="M7 17L17 7M7 7h10v10" />
          </svg>
        </a>
      </section>

      <!-- Table. -->
      <section v-else class="bg-white border border-slate-200 rounded-muw overflow-hidden">
        <table class="w-full text-sm" :aria-label="t('dataExport.title')">
          <thead class="bg-slate-50 text-[11px] uppercase tracking-wider text-slate-500">
            <tr>
              <th class="px-4 py-2 text-left">{{ t('dataExport.column.name') }}</th>
              <th class="px-4 py-2 text-left">{{ t('dataExport.column.owner') }}</th>
              <th class="px-4 py-2 text-left">{{ t('dataExport.column.created') }}</th>
              <th class="px-4 py-2 text-left">{{ t('dataExport.column.lastRun') }}</th>
              <th class="px-4 py-2 text-right">{{ t('dataExport.column.files') }}</th>
              <th class="px-4 py-2 text-right">{{ t('dataExport.column.actions') }}</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <template v-for="row in datasets.rows" :key="row.id">
              <tr>
                <td class="px-4 py-3 align-top">
                  <div class="font-medium text-slate-900">{{ row.name }}</div>
                  <div v-if="row.description" class="text-xs text-slate-500 mt-0.5 line-clamp-2">{{ row.description }}</div>
                </td>
                <td class="px-4 py-3 align-top text-slate-700">{{ row.ownerName ?? '—' }}</td>
                <td class="px-4 py-3 align-top text-slate-700">{{ formatDate(row.dateCreated) }}</td>
                <td class="px-4 py-3 align-top text-slate-700">{{ formatDate(row.lastRunAt) }}</td>
                <td class="px-4 py-3 align-top text-right text-slate-700">{{ row.fileCount }}</td>
                <td class="px-4 py-3 align-top text-right">
                  <div class="inline-flex items-center gap-2">
                    <button
                      type="button"
                      class="px-2.5 py-1 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
                      @click="toggleExpanded(row.id)"
                    >
                      {{ expanded.has(row.id) ? t('dataExport.hideFiles') : t('dataExport.viewFiles') }}
                    </button>
                    <button
                      type="button"
                      class="px-2.5 py-1 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
                      :disabled="datasets.isExporting.has(row.id)"
                      @click="openExportModal(row.id, row.name)"
                    >
                      {{ datasets.isExporting.has(row.id) ? t('dataExport.exporting') : t('dataExport.exportNow') }}
                    </button>
                  </div>
                </td>
              </tr>
              <!-- Per-row expand sub-row. -->
              <tr v-if="expanded.has(row.id)">
                <td colspan="6" class="px-4 py-3 bg-slate-50">
                  <p v-if="datasets.isLoadingFiles.has(row.id)" class="text-slate-500 italic text-xs">
                    {{ t('common.loading') }}
                  </p>
                  <p
                    v-else-if="!datasets.filesByDataset.get(row.id) || datasets.filesByDataset.get(row.id)!.length === 0"
                    class="text-slate-500 italic text-xs"
                  >
                    {{ t('dataExport.noFilesYet') }}
                  </p>
                  <table v-else class="w-full text-xs">
                    <thead class="text-[10px] uppercase tracking-wider text-slate-500">
                      <tr>
                        <th class="px-2 py-1 text-left">{{ t('dataExport.file.name') }}</th>
                        <th class="px-2 py-1 text-left">{{ t('dataExport.file.format') }}</th>
                        <th class="px-2 py-1 text-right">{{ t('dataExport.file.size') }}</th>
                        <th class="px-2 py-1 text-left">{{ t('dataExport.file.generated') }}</th>
                        <th class="px-2 py-1 text-right">{{ t('dataExport.file.action') }}</th>
                      </tr>
                    </thead>
                    <tbody class="divide-y divide-slate-200">
                      <tr v-for="f in datasets.filesByDataset.get(row.id)!" :key="f.id">
                        <td class="px-2 py-1.5 text-slate-700 break-all">{{ f.name }}</td>
                        <td class="px-2 py-1.5 text-slate-700">{{ f.formatName }}</td>
                        <td class="px-2 py-1.5 text-slate-700 text-right">{{ formatBytes(f.sizeBytes) }}</td>
                        <td class="px-2 py-1.5 text-slate-700">{{ formatDate(f.generatedAt) }}</td>
                        <td class="px-2 py-1.5 text-right">
                          <a
                            :href="f.downloadUrl"
                            class="text-muw-blue hover:underline"
                            target="_blank"
                            rel="noopener noreferrer"
                          >
                            {{ t('dataExport.file.download') }}
                          </a>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </section>
    </main>

    <!-- Export modal. -->
    <div
      v-if="exportModal"
      class="fixed inset-0 z-30 bg-slate-900/40 flex items-center justify-center px-4"
      role="dialog"
      aria-modal="true"
      :aria-label="t('dataExport.modal.title')"
    >
      <div class="bg-white rounded-muw shadow-xl max-w-md w-full p-6">
        <h2 class="text-base font-semibold text-slate-900 mb-1">{{ t('dataExport.modal.title') }}</h2>
        <p class="text-xs text-slate-600 mb-4">
          {{ t('dataExport.modal.subTitle', { dataset: exportModal.datasetName }) }}
        </p>
        <fieldset class="space-y-2 mb-5">
          <legend class="text-xs uppercase tracking-wider text-slate-500 mb-1">{{ t('dataExport.modal.formatLabel') }}</legend>
          <label
            v-for="f in EXPORT_FORMATS"
            :key="f"
            class="flex items-center gap-2 cursor-pointer"
          >
            <input
              type="radio"
              :value="f"
              v-model="exportModal.format"
              :name="'exportFormat-' + exportModal.datasetId"
              class="accent-muw-blue"
            />
            <span class="text-sm text-slate-700">{{ t('dataExport.format.' + f) }}</span>
          </label>
        </fieldset>
        <p v-if="exportModal.error" class="text-rose-700 text-xs mb-3">{{ exportModal.error }}</p>
        <div class="flex items-center justify-end gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="exportModal = null"
            :disabled="datasets.isExporting.has(exportModal.datasetId)"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 disabled:cursor-not-allowed"
            @click="submitExport"
            :disabled="datasets.isExporting.has(exportModal.datasetId)"
          >
            {{ datasets.isExporting.has(exportModal.datasetId) ? t('dataExport.exporting') : t('dataExport.modal.submit') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

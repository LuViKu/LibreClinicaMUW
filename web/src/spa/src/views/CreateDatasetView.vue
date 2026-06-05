<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

import CreateDatasetWizard from '@/components/CreateDatasetWizard.vue'

/**
 * Phase E.6 — Data Export Phase 2 — wizard host page.
 *
 * One view, two routes:
 *   - {@code /datasets/new} — create mode (no editId).
 *   - {@code /datasets/:datasetId/edit} — edit mode; the wizard
 *     hydrates from the existing dataset.
 *
 * Layout: header + the {@code CreateDatasetWizard} body. The wizard
 * owns its own stepper, controls, and save button so this view stays
 * thin.
 */
const { t } = useI18n()
const route = useRoute()

const editIdRaw = computed(() => route.params.datasetId)
const editId = computed(() => {
  const v = editIdRaw.value
  if (Array.isArray(v) ? v[0] : v) {
    const num = Number(Array.isArray(v) ? v[0] : v)
    return Number.isFinite(num) ? num : undefined
  }
  return undefined
})

const headerKey = computed(() => (editId.value ? 'createDataset.editTitle' : 'createDataset.title'))
const blurbKey = computed(() => (editId.value ? 'createDataset.editBlurb' : 'createDataset.blurb'))
</script>

<template>
  <main class="px-6 py-6 max-w-5xl mx-auto space-y-6">
    <header>
      <p class="text-xs uppercase tracking-wide text-slate-500">{{ t('createDataset.eyebrow') }}</p>
      <h1 class="text-xl font-semibold text-slate-900">{{ t(headerKey) }}</h1>
      <p class="text-sm text-slate-600 max-w-2xl">{{ t(blurbKey) }}</p>
    </header>

    <CreateDatasetWizard :edit-id="editId" />
  </main>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Wizard, { type WizardStep } from '@/components/Wizard.vue'
import FilterBuilder from '@/components/FilterBuilder.vue'
import { useDatasetsStore } from '@/stores/datasets'
import type {
  DatasetFilterDto,
  FilterItemDto,
} from '@/types/export'

/**
 * Phase E.6 Data Export — Create-dataset wizard (Phase 3 slice).
 *
 * Phase 3 owns the "Filters" step; the surrounding wizard skeleton
 * (Metadata → Items → Filters → Confirm) is published here so the
 * FilterBuilder has a real host the operator can route to. Phase 2's
 * wizard PR will replace the Metadata + Items placeholder steps with
 * production-grade controls (the dataset-name form, the items tree
 * picker); Phase 3's contract is that the Filters step accepts the
 * picked items via {@code props.selectedItems} and authors the filter
 * list against {@code datasets.draft.filters} via FilterBuilder.
 *
 * The wizard is driven by {@link Wizard} (Phase E.3 primitive); each
 * step navigates by parent-owned Next/Back buttons rather than
 * arbitrary jumps. The Filters step calls the {@code :test-filter}
 * preview probe on every model mutation, debounced inside the
 * datasets store.
 */

interface Props {
  /**
   * Items the operator picked in the (currently placeholder) Items
   * step. In production, Phase 2's tree picker writes these; Phase 3
   * accepts them as a prop so the wizard surfaces a real preview even
   * before Phase 2 lands.
   */
  selectedItems?: FilterItemDto[]
  /**
   * Existing dataset id when editing — passed through to the
   * {@code :test-filter} probe. {@code '0'} for a new dataset (the
   * backend treats it as the active-study scope).
   */
  datasetId?: string
}

const props = withDefaults(defineProps<Props>(), {
  selectedItems: () => [] as FilterItemDto[],
  datasetId: '0',
})

const { t } = useI18n()
const datasets = useDatasetsStore()

const step = ref(0)
const steps = computed<WizardStep[]>(() => [
  { id: 'metadata', title: t('exportWizard.steps.metadata') },
  { id: 'items', title: t('exportWizard.steps.items'), clickable: false },
  { id: 'filters', title: t('exportWizard.steps.filters') },
  { id: 'confirm', title: t('exportWizard.steps.confirm'), clickable: false },
])

const filters = computed<DatasetFilterDto[]>({
  get: () => datasets.draft.filters,
  set: (next) => { datasets.draft.filters = next },
})

const canGoNext = computed(() => step.value < steps.value.length - 1)
const canGoBack = computed(() => step.value > 0)

function next(): void {
  if (canGoNext.value) step.value += 1
}

function back(): void {
  if (canGoBack.value) step.value -= 1
}

/**
 * FilterBuilder fires {@code preview-requested} on every model
 * mutation; we run the count probe via the store (which debounces +
 * cancels in-flight). The store's {@code preview} ref binds back into
 * FilterBuilder via {@code :preview} so the inline pane updates.
 */
function onPreviewRequested(next: DatasetFilterDto[]): void {
  if (next.length === 0) {
    datasets.preview = null
    return
  }
  void datasets.testFilter(props.datasetId, next)
}

// Sync the store's draft.selectedItemOids when the parent feeds in a
// different selectedItems list (Phase 2 will own this; Phase 3 mirrors
// for the test-filter validation pass).
watch(
  () => props.selectedItems,
  (items) => {
    datasets.draft.selectedItemOids = items.map((i) => i.oid)
  },
  { immediate: true },
)
</script>

<template>
  <div class="space-y-6" data-testid="create-dataset-wizard">
    <Wizard v-model:step="step" :steps="steps" />

    <section v-if="steps[step].id === 'metadata'" class="space-y-3">
      <h3 class="text-sm font-semibold text-slate-700">{{ t('exportWizard.metadata.heading') }}</h3>
      <p class="text-xs text-slate-500">{{ t('exportWizard.metadata.placeholder') }}</p>
    </section>

    <section v-else-if="steps[step].id === 'items'" class="space-y-3">
      <h3 class="text-sm font-semibold text-slate-700">{{ t('exportWizard.items.heading') }}</h3>
      <p class="text-xs text-slate-500">{{ t('exportWizard.items.placeholder') }}</p>
    </section>

    <section v-else-if="steps[step].id === 'filters'" class="space-y-3">
      <h3 class="text-sm font-semibold text-slate-700">{{ t('exportWizard.filters.heading') }}</h3>
      <p class="text-xs text-slate-500">{{ t('exportWizard.filters.lead') }}</p>
      <FilterBuilder
        v-model="filters"
        :selected-items="selectedItems"
        :preview="datasets.preview"
        :preview-loading="datasets.isLoadingPreview"
        :preview-error="datasets.previewError"
        @preview-requested="onPreviewRequested"
      />
    </section>

    <section v-else-if="steps[step].id === 'confirm'" class="space-y-3">
      <h3 class="text-sm font-semibold text-slate-700">{{ t('exportWizard.confirm.heading') }}</h3>
      <p class="text-xs text-slate-500">{{ t('exportWizard.confirm.placeholder') }}</p>
    </section>

    <div class="flex items-center justify-between border-t border-slate-200 pt-4">
      <button
        type="button"
        class="px-3 py-1.5 text-sm rounded-md border border-slate-300 text-slate-700 hover:bg-slate-50 muw-focus disabled:opacity-50 disabled:cursor-not-allowed"
        data-testid="wizard-back"
        :disabled="!canGoBack"
        @click="back"
      >
        {{ t('exportWizard.back') }}
      </button>
      <button
        type="button"
        class="px-3 py-1.5 text-sm rounded-md bg-muw-blue text-white hover:bg-muw-blue-600 muw-focus disabled:opacity-50 disabled:cursor-not-allowed"
        data-testid="wizard-next"
        :disabled="!canGoNext"
        @click="next"
      >
        {{ t('exportWizard.next') }}
      </button>
    </div>
  </div>
</template>

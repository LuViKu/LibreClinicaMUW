<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useDatasetsStore } from '@/stores/datasets'
import {
  FLAG_SECTIONS,
  type DatasetFilterDto,
  type FilterItemDto,
  type InclusionFlags,
} from '@/types/export'
import Wizard, { type WizardStep } from '@/components/Wizard.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import HelperText from '@/components/HelperText.vue'
import EventCrfItemTreePicker from '@/components/EventCrfItemTreePicker.vue'
import InclusionFlagsForm from '@/components/InclusionFlagsForm.vue'
import FilterBuilder from '@/components/FilterBuilder.vue'

/**
 * Phase E.6 — Data Export — create-dataset wizard shell.
 *
 * Four-step flow that replaces the legacy five-page /CreateDataset JSP:
 *   1. Scope            — name + description + event/CRF/item tree
 *   2. Inclusion flags  — 18 booleans grouped into 5 sections
 *   3. Filters          — Phase 3 FilterBuilder predicate authoring
 *   4. Review           — summary + Save button.
 *
 * Both create and edit modes share the same wizard — the store decides
 * which HTTP verb to fire on save.
 *
 * Phase 3's {@code FilterBuilder} is wired into the Filters step. It
 * reads its item universe from the wizard-selected items (mapped onto
 * {@link FilterItemDto}) so the operator can only filter on items they
 * already picked in step 1.
 */

interface Props {
  /** Optional dataset id; when set, the wizard hydrates in edit mode. */
  editId?: number
}

const props = defineProps<Props>()

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const datasets = useDatasetsStore()

const localErrors = ref<Record<string, string>>({})
const remoteErrors = ref<Record<string, string>>({})
const saving = ref(false)
const saveBanner = ref<string | null>(null)
const fieldErrors = computed(() => ({ ...localErrors.value, ...remoteErrors.value }))

/* ============================================================== */
/* Mount — seed the draft + load the event tree.                  */
/* ============================================================== */

onMounted(async () => {
  const studyOid = auth.user?.activeStudy?.oid
  if (!studyOid) {
    router.replace({ name: 'pick-study' })
    return
  }

  // Reload the tree on every wizard entry. The cost is one denormalised
  // event/CRF/version/item fetch per mount; the benefit is that a
  // freshly-created visit or CRF version (added between wizard
  // sessions via the Build Study flow) is visible immediately. Phase
  // E.6 export-tool bug — operators couldn't see newly created visits
  // until they hard-reloaded the page.
  await datasets.loadEventTree(studyOid)

  if (props.editId) {
    // Hydrate from the existing dataset.
    const existing = await datasets.loadOne(props.editId)
    if (existing) {
      datasets.startEditDraft(existing)
    } else {
      router.replace({ name: 'data-export' })
    }
  } else if (!datasets.draft) {
    datasets.startNewDraft()
  }
})

/* ============================================================== */
/* Step model                                                     */
/* ============================================================== */

const stepIsReachable = (idx: number): boolean => {
  if (idx <= 0) return true
  return scopeValid.value
}

const steps = computed<WizardStep[]>(() => [
  { id: 'scope', title: t('createDataset.steps.scope'), clickable: true },
  { id: 'flags', title: t('createDataset.steps.flags'), clickable: stepIsReachable(1) },
  { id: 'filters', title: t('createDataset.steps.filters'), clickable: stepIsReachable(2) },
  { id: 'review', title: t('createDataset.steps.review'), clickable: stepIsReachable(3) },
])

/* ============================================================== */
/* Step 1 — Scope validation                                      */
/* ============================================================== */

const scopeValid = computed(() => {
  const d = datasets.draft
  if (!d) return false
  if (!d.name.trim()) return false
  if (d.eventDefinitionOids.length === 0) return false
  if (d.crfVersionIds.length === 0) return false
  if (d.itemIds.length === 0) return false
  return true
})

const draft = computed(() => datasets.draft)

const step = computed({
  get: () => datasets.draft?.step ?? 0,
  set: (v: number) => datasets.setStep(v),
})

function setName(v: string) {
  datasets.patchDraft({ name: v })
  if (v.trim()) localErrors.value = stripField(localErrors.value, 'name')
}
function setDescription(v: string) {
  datasets.patchDraft({ description: v })
}

function setEventOids(v: string[]) {
  datasets.patchDraft({ eventDefinitionOids: v })
  if (v.length > 0) localErrors.value = stripField(localErrors.value, 'eventDefinitionOids')
}
function setVersionIds(v: number[]) {
  datasets.patchDraft({ crfVersionIds: v })
  if (v.length > 0) localErrors.value = stripField(localErrors.value, 'crfVersionIds')
}
function setItemIds(v: number[]) {
  datasets.patchDraft({ itemIds: v })
  if (v.length > 0) localErrors.value = stripField(localErrors.value, 'itemIds')
}

function setFlags(v: InclusionFlags) {
  datasets.patchDraft({ includeFlags: v })
}

function stripField(src: Record<string, string>, field: string): Record<string, string> {
  if (!(field in src)) return src
  const out = { ...src }
  delete out[field]
  return out
}

/* ============================================================== */
/* Step 3 — Filters (Phase 3 FilterBuilder)                       */
/* ============================================================== */

const filterItems = computed<FilterItemDto[]>(() => {
  const d = datasets.draft
  if (!d) return []
  // Walk the loaded event tree once and project the wizard-picked
  // item ids onto {oid,label,dataType} triples so FilterBuilder can
  // render the operator selector against type-appropriate ops.
  const picked = new Set<number>(d.itemIds)
  const out: FilterItemDto[] = []
  for (const ev of datasets.eventTree) {
    for (const crf of ev.crfs) {
      for (const ver of crf.versions) {
        for (const item of ver.items) {
          if (picked.has(item.itemId)) {
            out.push({ oid: item.oid, label: item.name, dataType: item.dataType })
          }
        }
      }
    }
  }
  return out
})

const filters = computed<DatasetFilterDto[]>({
  get: () => datasets.draft?.filters ?? [],
  set: (next) => { datasets.patchDraft({ filters: next }) },
})

const datasetIdForPreview = computed(() => String(datasets.draft?.editingDatasetId ?? '0'))

function onPreviewRequested(next: DatasetFilterDto[]): void {
  if (next.length === 0) {
    datasets.preview = null
    return
  }
  void datasets.testFilter(datasetIdForPreview.value, next)
}

/* ============================================================== */
/* Navigation                                                     */
/* ============================================================== */

function goNext() {
  const idx = step.value
  if (idx === 0) {
    const errors: Record<string, string> = {}
    if (!draft.value?.name.trim()) errors.name = t('createDataset.errors.nameRequired')
    if ((draft.value?.eventDefinitionOids.length ?? 0) === 0)
      errors.eventDefinitionOids = t('createDataset.errors.eventRequired')
    if ((draft.value?.crfVersionIds.length ?? 0) === 0)
      errors.crfVersionIds = t('createDataset.errors.versionRequired')
    if ((draft.value?.itemIds.length ?? 0) === 0)
      errors.itemIds = t('createDataset.errors.itemRequired')
    localErrors.value = errors
    if (Object.keys(errors).length > 0) return
  }
  step.value = Math.min(idx + 1, 3)
}

function goBack() {
  step.value = Math.max(0, step.value - 1)
}

function cancel() {
  datasets.clearDraft()
  router.push({ name: 'data-export' })
}

/* ============================================================== */
/* Save                                                            */
/* ============================================================== */

async function save() {
  const studyOid = auth.user?.activeStudy?.oid
  if (!studyOid || !draft.value) return
  saving.value = true
  saveBanner.value = null
  remoteErrors.value = {}
  try {
    const res = await datasets.saveDraft(studyOid)
    if (res.ok) {
      datasets.clearDraft()
      router.push({ name: 'data-export' })
    } else {
      remoteErrors.value = res.fieldErrors
      saveBanner.value = res.message
      // Jump back to step 1 when scope errors come from the backend.
      const scopeFields = new Set(['name', 'description', 'eventDefinitionOids', 'crfVersionIds', 'itemIds'])
      if (Object.keys(res.fieldErrors).some((f) => scopeFields.has(f))) {
        step.value = 0
      }
    }
  } finally {
    saving.value = false
  }
}

/* ============================================================== */
/* Cleanup — drop the draft when the route unmounts.              */
/* ============================================================== */

watch(
  () => router.currentRoute.value.name,
  (next) => {
    if (next !== 'dataset-new' && next !== 'dataset-edit') {
      // Operator left the wizard via direct nav — keep draft live in
      // case they come back, but reset error banners.
      saveBanner.value = null
      localErrors.value = {}
      remoteErrors.value = {}
    }
  },
)

/* ============================================================== */
/* Review-step summary                                            */
/* ============================================================== */

const flagsSummary = computed(() => {
  const d = draft.value
  if (!d) return []
  return FLAG_SECTIONS.map((s) => ({
    id: s.id,
    title: t(`createDataset.flagSection.${s.id}.title`),
    selected: s.keys.filter((k) => d.includeFlags[k]).map((k) => t(`createDataset.flag.${k}.label`)),
  }))
})

const reviewLines = computed(() => {
  const d = draft.value
  if (!d) return null
  return {
    name: d.name.trim(),
    description: d.description.trim(),
    events: d.eventDefinitionOids.length,
    versions: d.crfVersionIds.length,
    items: d.itemIds.length,
  }
})
</script>

<template>
  <div v-if="draft" class="space-y-6" data-testid="create-dataset-wizard">
    <Wizard v-model:step="step" :steps="steps">
      <template #default="{ currentStep }">
        <!-- Step 0 — Scope -->
        <section v-if="currentStep === 0" class="space-y-6">
          <header>
            <h2 class="text-lg font-semibold text-slate-900">
              {{ t('createDataset.scope.title') }}
            </h2>
            <HelperText>{{ t('createDataset.scope.blurb') }}</HelperText>
          </header>

          <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <FieldLabel for="dataset-name" required>
                {{ t('createDataset.fields.name') }}
              </FieldLabel>
              <TextInput
                id="dataset-name"
                :model-value="draft.name"
                :error="Boolean(fieldErrors.name)"
                :placeholder="t('createDataset.fields.namePlaceholder')"
                autocomplete="off"
                @update:model-value="setName"
              />
              <ErrorText v-if="fieldErrors.name">{{ fieldErrors.name }}</ErrorText>
              <HelperText v-else>{{ t('createDataset.fields.nameHelp') }}</HelperText>
            </div>
            <div>
              <FieldLabel for="dataset-description">
                {{ t('createDataset.fields.description') }}
              </FieldLabel>
              <TextInput
                id="dataset-description"
                :model-value="draft.description"
                :placeholder="t('createDataset.fields.descriptionPlaceholder')"
                @update:model-value="setDescription"
              />
              <HelperText>{{ t('createDataset.fields.descriptionHelp') }}</HelperText>
            </div>
          </div>

          <div>
            <FieldLabel for="event-tree-search" required>
              {{ t('createDataset.scope.treeTitle') }}
            </FieldLabel>
            <EventCrfItemTreePicker
              :tree="datasets.eventTree"
              :event-oids="draft.eventDefinitionOids"
              :version-ids="draft.crfVersionIds"
              :item-ids="draft.itemIds"
              :loading="datasets.isEventTreeLoading"
              @update:event-oids="setEventOids"
              @update:version-ids="setVersionIds"
              @update:item-ids="setItemIds"
            />
            <ErrorText v-if="fieldErrors.eventDefinitionOids">{{ fieldErrors.eventDefinitionOids }}</ErrorText>
            <ErrorText v-else-if="fieldErrors.crfVersionIds">{{ fieldErrors.crfVersionIds }}</ErrorText>
            <ErrorText v-else-if="fieldErrors.itemIds">{{ fieldErrors.itemIds }}</ErrorText>
          </div>
        </section>

        <!-- Step 1 — Inclusion flags -->
        <section v-else-if="currentStep === 1" class="space-y-4">
          <header>
            <h2 class="text-lg font-semibold text-slate-900">
              {{ t('createDataset.flagsTitle') }}
            </h2>
          </header>
          <InclusionFlagsForm
            :model-value="draft.includeFlags"
            @update:model-value="setFlags"
          />
        </section>

        <!-- Step 2 — Filters (Phase 3 FilterBuilder) -->
        <section v-else-if="currentStep === 2" class="space-y-4">
          <header>
            <h2 class="text-lg font-semibold text-slate-900">
              {{ t('createDataset.filtersTitle') }}
            </h2>
            <HelperText>{{ t('createDataset.filtersBlurb') }}</HelperText>
          </header>
          <FilterBuilder
            v-model="filters"
            :selected-items="filterItems"
            :preview="datasets.preview"
            :preview-loading="datasets.isLoadingPreview"
            :preview-error="datasets.previewError"
            @preview-requested="onPreviewRequested"
          />
        </section>

        <!-- Step 3 — Review -->
        <section v-else class="space-y-4">
          <header>
            <h2 class="text-lg font-semibold text-slate-900">
              {{ t('createDataset.reviewTitle') }}
            </h2>
            <HelperText>{{ t('createDataset.reviewBlurb') }}</HelperText>
          </header>

          <dl v-if="reviewLines" class="grid grid-cols-1 sm:grid-cols-3 gap-3 text-sm bg-slate-50 rounded-md p-4">
            <div>
              <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('createDataset.fields.name') }}</dt>
              <dd class="font-medium text-slate-800">{{ reviewLines.name || '—' }}</dd>
            </div>
            <div>
              <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('createDataset.fields.description') }}</dt>
              <dd class="text-slate-700">{{ reviewLines.description || '—' }}</dd>
            </div>
            <div>
              <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('createDataset.review.scope') }}</dt>
              <dd class="text-slate-700">
                {{ t('createDataset.review.scopeSummary', {
                  events: reviewLines.events,
                  versions: reviewLines.versions,
                  items: reviewLines.items,
                }) }}
              </dd>
            </div>
          </dl>

          <div class="border border-slate-200 rounded-md">
            <header class="px-3 py-2 bg-slate-50 text-sm font-semibold text-slate-800 border-b border-slate-200">
              {{ t('createDataset.review.flagsTitle') }}
            </header>
            <ul class="divide-y divide-slate-100">
              <li
                v-for="section in flagsSummary"
                :key="section.id"
                class="px-3 py-2 text-sm flex flex-wrap gap-2 items-start"
              >
                <span class="font-medium text-slate-700 min-w-[140px]">{{ section.title }}</span>
                <span v-if="section.selected.length === 0" class="text-slate-400 italic">{{ t('createDataset.review.noFlagsInSection') }}</span>
                <ul v-else class="flex flex-wrap gap-1">
                  <li
                    v-for="label in section.selected"
                    :key="label"
                    class="text-xs px-2 py-0.5 rounded-full bg-muw-blue-100 text-muw-blue"
                  >{{ label }}</li>
                </ul>
              </li>
            </ul>
          </div>

          <div
            v-if="saveBanner"
            class="rounded-md border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700"
            role="alert"
          >
            {{ saveBanner }}
          </div>
        </section>
      </template>
    </Wizard>

    <footer class="flex flex-wrap items-center justify-between gap-3 pt-4 border-t border-slate-200">
      <button
        type="button"
        class="px-4 py-2 text-sm rounded-md text-slate-600 hover:bg-slate-100"
        data-testid="wizard-cancel"
        @click="cancel"
      >
        {{ t('common.cancel') }}
      </button>

      <div class="flex items-center gap-3">
        <button
          type="button"
          class="px-4 py-2 text-sm rounded-md border border-slate-300 text-slate-700 hover:bg-slate-50"
          data-testid="wizard-back"
          :disabled="step === 0"
          @click="goBack"
        >
          {{ t('common.back') }}
        </button>
        <button
          v-if="step < 3"
          type="button"
          class="px-4 py-2 text-sm rounded-md bg-muw-blue text-white hover:bg-muw-blue-700"
          data-testid="wizard-next"
          @click="goNext"
        >
          {{ t('common.next') }}
        </button>
        <button
          v-else
          type="button"
          class="px-4 py-2 text-sm rounded-md bg-muw-blue text-white hover:bg-muw-blue-700 disabled:opacity-50"
          data-testid="wizard-save"
          :disabled="saving || !scopeValid"
          @click="save"
        >
          {{ saving ? t('createDataset.saving') : datasets.isEditing ? t('createDataset.saveEdit') : t('createDataset.save') }}
        </button>
      </div>
    </footer>
  </div>

  <div v-else class="px-3 py-6 text-center text-sm text-slate-500" role="status">
    {{ t('createDataset.preparing') }}
  </div>
</template>

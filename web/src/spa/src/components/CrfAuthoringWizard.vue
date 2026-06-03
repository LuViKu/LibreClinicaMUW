<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import StatusPill from '@/components/StatusPill.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useCrfAuthoringStore, type AuthoringDataType } from '@/stores/crfAuthoring'

/**
 * Phase E.6 Milestone A — manual eCRF authoring wizard.
 *
 * <p>Side-rail wizard with three sections: Metadata → Sections → Review.
 * Locked to the Milestone A scope: one section, two-three items,
 * three data types (ST / INTEGER / BL), one TEXT response set. No
 * show-when, no calculations, no item groups beyond the implicit
 * ungrouped default. Final-submit persistence — closing discards.
 *
 * <p>Backend wire: {@code POST /pages/api/v1/crfs/{crfOid}/versions}
 * with {@code Content-Type: application/json}. The backend synthesises
 * an XLS workbook from the JSON and feeds it to the existing
 * spreadsheet parser pipeline — zero parity drift with XLS upload.
 */

interface Props {
  open: boolean
  crfOid: string
  crfName: string
}
const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [v: boolean]
  close: []
}>()

const { t } = useI18n()
const store = useCrfAuthoringStore()

type Step = 'metadata' | 'sections' | 'review'
const currentStep = ref<Step>('metadata')

const formError = ref<string | null>(null)
const submitParseErrors = ref<string[]>([])
const submitFieldErrors = ref<Record<string, string>>({})

watch(
  () => props.open,
  (next) => {
    if (next) {
      store.reset()
      currentStep.value = 'metadata'
      formError.value = null
      submitParseErrors.value = []
      submitFieldErrors.value = {}
    }
  },
)

const dataTypeOptions: Array<{ value: AuthoringDataType; labelKey: string }> = [
  { value: 'ST', labelKey: 'crfLibrary.author.dataType.ST' },
  { value: 'INTEGER', labelKey: 'crfLibrary.author.dataType.INTEGER' },
  { value: 'BL', labelKey: 'crfLibrary.author.dataType.BL' },
]

const sectionList = computed(() => store.draft.sections)

const canSubmit = computed(() => {
  const d = store.draft
  if (d.versionName.trim() === '') return false
  if (d.sections.length === 0) return false
  for (const section of d.sections) {
    if (section.label.trim() === '' || section.title.trim() === '') return false
    if (section.items.length === 0) return false
    for (const item of section.items) {
      if (item.name.trim() === '') return false
      if (!/^\w+$/.test(item.name.trim())) return false
      if (item.descriptionLabel.trim() === '') return false
    }
  }
  return true
})

function goToStep(step: Step): void {
  currentStep.value = step
}

function onAddItem(sectionIndex: number): void {
  store.addItem(sectionIndex)
}

function onRemoveItem(sectionIndex: number, itemIndex: number): void {
  store.removeItem(sectionIndex, itemIndex)
}

async function onSubmit(): Promise<void> {
  if (!canSubmit.value || store.isSubmitting) return
  formError.value = null
  submitParseErrors.value = []
  submitFieldErrors.value = {}
  const result = await store.submit(props.crfOid)
  if (result.ok) {
    emit('update:open', false)
    emit('close')
  } else {
    submitFieldErrors.value = result.fieldErrors
    submitParseErrors.value = result.parseErrors
    formError.value = result.message ?? null
  }
}

function onCancel(): void {
  emit('update:open', false)
  emit('close')
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="crf-authoring-heading"
    panel-class="max-w-4xl"
    @update:open="(v) => emit('update:open', v)"
    @close="onCancel"
  >
    <template #header>
      <div>
        <h2 id="crf-authoring-heading" class="text-base font-semibold tracking-tight">
          {{ t('crfLibrary.author.heading', { name: props.crfName }) }}
        </h2>
        <p class="text-[11px] text-slate-500 mt-0.5">
          {{ t('crfLibrary.author.subheading') }}
        </p>
      </div>
    </template>

    <div class="flex gap-4 min-h-[24rem]">
      <!-- Side rail -->
      <nav class="w-44 shrink-0 border-r border-slate-200 pr-3 -ml-1">
        <ul class="space-y-1 text-xs">
          <li>
            <button
              type="button"
              class="block w-full text-left px-2.5 py-1.5 rounded-md hover:bg-slate-50"
              :class="currentStep === 'metadata' ? 'bg-muw-blue/10 text-muw-blue font-medium' : 'text-slate-700'"
              @click="goToStep('metadata')"
            >
              {{ t('crfLibrary.author.rail.metadata') }}
            </button>
          </li>
          <li>
            <button
              type="button"
              class="block w-full text-left px-2.5 py-1.5 rounded-md hover:bg-slate-50"
              :class="currentStep === 'sections' ? 'bg-muw-blue/10 text-muw-blue font-medium' : 'text-slate-700'"
              @click="goToStep('sections')"
            >
              {{ t('crfLibrary.author.rail.sections') }}
            </button>
          </li>
          <li>
            <button
              type="button"
              class="block w-full text-left px-2.5 py-1.5 rounded-md hover:bg-slate-50"
              :class="currentStep === 'review' ? 'bg-muw-blue/10 text-muw-blue font-medium' : 'text-slate-700'"
              @click="goToStep('review')"
            >
              {{ t('crfLibrary.author.rail.review') }}
            </button>
          </li>
        </ul>
        <div class="mt-4 rounded-md border border-amber-200 bg-amber-50 p-2 text-[11px] text-amber-900 leading-snug">
          {{ t('crfLibrary.author.scopeNote') }}
        </div>
      </nav>

      <!-- Main panel -->
      <section class="flex-1 min-w-0 space-y-4">
        <!-- Metadata step -->
        <div v-if="currentStep === 'metadata'" class="space-y-4">
          <h3 class="text-sm font-semibold">{{ t('crfLibrary.author.metadata.heading') }}</h3>
          <div>
            <FieldLabel for="crf-author-vname" required>
              {{ t('crfLibrary.versionName') }}
            </FieldLabel>
            <TextInput
              id="crf-author-vname"
              :model-value="store.draft.versionName"
              :error="submitFieldErrors.versionName != null"
              @update:model-value="(v: string) => store.setVersionName(v)"
            />
            <ErrorText v-if="submitFieldErrors.versionName">{{ submitFieldErrors.versionName }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="crf-author-vdesc">{{ t('crfLibrary.versionDescription') }}</FieldLabel>
            <TextInput
              id="crf-author-vdesc"
              :model-value="store.draft.versionDescription"
              @update:model-value="(v: string) => store.setVersionDescription(v)"
            />
          </div>
          <div>
            <FieldLabel for="crf-author-vrev">{{ t('crfLibrary.revisionNotes') }}</FieldLabel>
            <TextInput
              id="crf-author-vrev"
              :model-value="store.draft.revisionNotes"
              @update:model-value="(v: string) => store.setMetadata({ revisionNotes: v })"
            />
          </div>
        </div>

        <!-- Sections step -->
        <div v-if="currentStep === 'sections'" class="space-y-5">
          <h3 class="text-sm font-semibold">{{ t('crfLibrary.author.sections.heading') }}</h3>
          <div
            v-for="(section, sIdx) in sectionList"
            :key="`s-${sIdx}`"
            class="rounded-md border border-slate-200 bg-white p-3"
          >
            <div class="grid grid-cols-2 gap-3">
              <div>
                <FieldLabel :for="`crf-author-slabel-${sIdx}`" required>
                  {{ t('crfLibrary.author.sectionLabel') }}
                </FieldLabel>
                <TextInput
                  :id="`crf-author-slabel-${sIdx}`"
                  v-model="section.label"
                />
              </div>
              <div>
                <FieldLabel :for="`crf-author-stitle-${sIdx}`" required>
                  {{ t('crfLibrary.author.sectionTitle') }}
                </FieldLabel>
                <TextInput
                  :id="`crf-author-stitle-${sIdx}`"
                  v-model="section.title"
                />
              </div>
              <div class="col-span-2">
                <FieldLabel :for="`crf-author-sinstr-${sIdx}`">
                  {{ t('crfLibrary.author.sectionInstructions') }}
                </FieldLabel>
                <TextInput
                  :id="`crf-author-sinstr-${sIdx}`"
                  v-model="section.instructions"
                />
              </div>
            </div>

            <div class="mt-4">
              <div class="flex items-center justify-between mb-2">
                <h4 class="text-xs font-semibold text-slate-700">
                  {{ t('crfLibrary.author.itemsHeading') }}
                </h4>
                <button
                  type="button"
                  class="text-xs text-muw-blue hover:underline"
                  @click="onAddItem(sIdx)"
                >{{ t('crfLibrary.author.addItem') }}</button>
              </div>
              <p v-if="section.items.length === 0" class="text-xs italic text-slate-500">
                {{ t('crfLibrary.author.itemsEmpty') }}
              </p>
              <ul class="space-y-3">
                <li
                  v-for="(item, iIdx) in section.items"
                  :key="`s-${sIdx}-i-${iIdx}`"
                  class="rounded-md border border-slate-200 bg-slate-50/60 p-3"
                >
                  <div class="grid grid-cols-2 gap-3">
                    <div>
                      <FieldLabel :for="`crf-author-iname-${sIdx}-${iIdx}`" required>
                        {{ t('crfLibrary.author.itemName') }}
                      </FieldLabel>
                      <TextInput
                        :id="`crf-author-iname-${sIdx}-${iIdx}`"
                        v-model="item.name"
                        placeholder="AGE"
                      />
                    </div>
                    <div>
                      <FieldLabel :for="`crf-author-idesc-${sIdx}-${iIdx}`" required>
                        {{ t('crfLibrary.author.descriptionLabel') }}
                      </FieldLabel>
                      <TextInput
                        :id="`crf-author-idesc-${sIdx}-${iIdx}`"
                        v-model="item.descriptionLabel"
                      />
                    </div>
                    <div class="col-span-2">
                      <FieldLabel :for="`crf-author-iltext-${sIdx}-${iIdx}`">
                        {{ t('crfLibrary.author.leftItemText') }}
                      </FieldLabel>
                      <TextInput
                        :id="`crf-author-iltext-${sIdx}-${iIdx}`"
                        v-model="item.leftItemText"
                      />
                    </div>
                    <div>
                      <FieldLabel :for="`crf-author-idtype-${sIdx}-${iIdx}`" required>
                        {{ t('crfLibrary.author.dataTypeLabel') }}
                      </FieldLabel>
                      <SelectInput
                        :id="`crf-author-idtype-${sIdx}-${iIdx}`"
                        v-model="item.dataType"
                      >
                        <option v-for="opt in dataTypeOptions" :key="opt.value" :value="opt.value">
                          {{ t(opt.labelKey) }}
                        </option>
                      </SelectInput>
                    </div>
                    <div class="flex items-end pb-1">
                      <label class="inline-flex items-center gap-2 text-xs text-slate-700">
                        <input type="checkbox" v-model="item.required" />
                        {{ t('crfLibrary.author.required') }}
                      </label>
                    </div>
                  </div>
                  <div class="mt-2 text-right">
                    <button
                      type="button"
                      class="text-[11px] text-rose-600 hover:underline"
                      @click="onRemoveItem(sIdx, iIdx)"
                    >{{ t('common.remove') }}</button>
                  </div>
                </li>
              </ul>
            </div>
          </div>
        </div>

        <!-- Review step -->
        <div v-if="currentStep === 'review'" class="space-y-3 text-xs">
          <h3 class="text-sm font-semibold">{{ t('crfLibrary.author.review.heading') }}</h3>
          <p class="text-slate-600 leading-relaxed">
            {{ t('crfLibrary.author.review.intro') }}
          </p>
          <dl class="rounded-md border border-slate-200 bg-white p-3 space-y-1">
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.crfName') }}</dt>
              <dd class="flex-1 font-mono">{{ props.crfName }}</dd>
            </div>
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.versionName') }}</dt>
              <dd class="flex-1 font-mono">{{ store.draft.versionName || '—' }}</dd>
            </div>
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.author.review.sectionCount') }}</dt>
              <dd class="flex-1">{{ store.draft.sections.length }}</dd>
            </div>
            <div class="flex">
              <dt class="w-40 text-slate-500">{{ t('crfLibrary.author.review.itemCount') }}</dt>
              <dd class="flex-1">{{ store.draft.sections.reduce((acc, s) => acc + s.items.length, 0) }}</dd>
            </div>
          </dl>
          <div v-if="!canSubmit" class="rounded-md border border-amber-200 bg-amber-50 p-2 text-[11px] text-amber-900">
            {{ t('crfLibrary.author.review.incomplete') }}
          </div>
          <div v-if="submitParseErrors.length > 0" class="rounded-md border border-rose-200 bg-rose-50 p-2 text-[11px] text-rose-800">
            <div class="font-medium mb-1">{{ t('crfLibrary.author.review.parseErrors', { count: submitParseErrors.length }) }}</div>
            <ul class="list-disc pl-4 space-y-0.5">
              <li v-for="(msg, i) in submitParseErrors" :key="i">{{ msg }}</li>
            </ul>
          </div>
          <ErrorText v-if="formError">{{ formError }}</ErrorText>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="flex items-center gap-2">
        <StatusPill v-if="store.isSubmitting" variant="neutral">
          {{ t('common.saving') }}
        </StatusPill>
      </div>
      <div class="flex items-center gap-2">
        <button
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="onCancel"
        >{{ t('common.cancel') }}</button>
        <button
          v-if="currentStep !== 'review'"
          class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700"
          @click="goToStep(currentStep === 'metadata' ? 'sections' : 'review')"
        >{{ t('crfLibrary.author.next') }}</button>
        <button
          v-if="currentStep === 'review'"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="!canSubmit || store.isSubmitting"
          @click="onSubmit"
        >{{ store.isSubmitting ? t('common.saving') : t('crfLibrary.author.submit') }}</button>
      </div>
    </template>
  </Modal>
</template>

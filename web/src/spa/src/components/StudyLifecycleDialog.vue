<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useAdminStudiesStore } from '@/stores/adminStudies'
import type { AdminStudy, StudyRemovalPreview } from '@/types/study'

/**
 * Confirmation for removing or restoring a study.
 *
 * Removing a study takes everything under it along: sites, role
 * bindings, subjects, events, CRFs and their data. So before the
 * operator confirms, the dialog names what will go, counted by the
 * server (`GET /studies/{oid}/removal-preview`), the way the legacy
 * RemoveStudy confirmation page listed it. A reason is required for both
 * directions; the server records it in the audit trail.
 */
interface Props {
  open: boolean
  study: AdminStudy | null
  action: 'remove' | 'restore'
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:open': [v: boolean]; done: [] }>()

const { t } = useI18n()
const store = useAdminStudiesStore()

const preview = ref<StudyRemovalPreview | null>(null)
const previewError = ref<string | null>(null)
const loadingPreview = ref(false)
const reason = ref('')
const reasonError = ref<string | null>(null)
const submitError = ref<string | null>(null)
const submitting = ref(false)

const isRemove = computed(() => props.action === 'remove')

/** The removal's reach, one line per kind that has any rows. */
const impactLines = computed<{ key: string; n: number }[]>(() => {
  const p = preview.value
  if (!p) return []
  return [
    { key: 'sites', n: p.siteNames.length },
    { key: 'roleBindings', n: p.roleBindings },
    { key: 'subjects', n: p.subjects },
    { key: 'groupClasses', n: p.groupClasses },
    { key: 'eventDefinitions', n: p.eventDefinitions },
    { key: 'events', n: p.events },
    { key: 'eventCrfs', n: p.eventCrfs },
    { key: 'itemData', n: p.itemData },
    { key: 'datasets', n: p.datasets },
  ].filter((line) => line.n > 0)
})

watch(
  () => [props.open, props.study, props.action] as const,
  async ([isOpen, study, action]) => {
    if (!isOpen || !study) return
    preview.value = null
    previewError.value = null
    reason.value = ''
    reasonError.value = null
    submitError.value = null
    if (action !== 'remove') return
    loadingPreview.value = true
    try {
      preview.value = await store.previewRemoval(study.oid)
    } catch (e) {
      previewError.value = e instanceof Error ? e.message : t('studyLifecycle.previewFailed')
    } finally {
      loadingPreview.value = false
    }
  },
  { immediate: true },
)

/** A removal is confirmed only once the operator has seen what it takes. */
const canConfirm = computed(() =>
  !submitting.value && (!isRemove.value || preview.value !== null),
)

async function confirm() {
  if (!props.study || !canConfirm.value) return
  const text = reason.value.trim()
  if (text === '') {
    reasonError.value = t('studyLifecycle.reasonRequired')
    return
  }
  reasonError.value = null
  submitError.value = null
  submitting.value = true
  try {
    const result = isRemove.value
      ? await store.remove(props.study.oid, text)
      : await store.restore(props.study.oid, text)
    if (result.ok) {
      emit('done')
      close()
    } else {
      submitError.value = result.message
    }
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:open', false)
}
</script>

<template>
  <Modal :open="props.open" labelled-by="study-lifecycle-title" panel-class="max-w-lg" @update:open="(v) => emit('update:open', v)">
    <template #header>
      <h2 id="study-lifecycle-title" class="text-lg font-semibold tracking-tight">
        {{ isRemove ? t('studyLifecycle.removeTitle') : t('studyLifecycle.restoreTitle') }}
      </h2>
    </template>

    <div v-if="props.study" class="space-y-4 text-sm text-slate-700">
      <p>
        <span class="font-medium text-slate-900">{{ props.study.name }}</span>
        <span class="font-mono text-xs text-slate-500"> · {{ props.study.oid }}</span>
      </p>

      <template v-if="isRemove">
        <p>{{ t('studyLifecycle.removeIntro') }}</p>
        <p v-if="loadingPreview" class="text-xs italic text-slate-500">{{ t('common.loading') }}</p>
        <p v-else-if="previewError" class="text-xs text-rose-600" role="alert">{{ previewError }}</p>
        <div v-else-if="preview" data-testid="study-removal-impact">
          <p v-if="impactLines.length === 0" class="text-xs text-slate-500">{{ t('studyLifecycle.nothingUnder') }}</p>
          <ul v-else class="list-disc pl-5 space-y-0.5">
            <li v-for="line in impactLines" :key="line.key" :data-testid="`impact-${line.key}`">
              {{ t(`studyLifecycle.impact.${line.key}`, { n: line.n }) }}
              <span v-if="line.key === 'sites'" class="text-slate-500">({{ preview.siteNames.join(', ') }})</span>
            </li>
          </ul>
        </div>
        <p class="text-xs text-slate-500">{{ t('studyLifecycle.removeReversible') }}</p>
      </template>
      <p v-else>{{ t('studyLifecycle.restoreIntro') }}</p>

      <div>
        <FieldLabel for="study-lifecycle-reason" required>{{ t('studyLifecycle.reason') }}</FieldLabel>
        <textarea
          id="study-lifecycle-reason"
          v-model="reason"
          rows="3"
          maxlength="1000"
          class="w-full text-sm px-2 py-1.5 border border-slate-200 rounded-md"
          :placeholder="t('studyLifecycle.reasonPlaceholder')"
        />
        <ErrorText v-if="reasonError">{{ reasonError }}</ErrorText>
      </div>
      <ErrorText v-if="submitError">{{ submitError }}</ErrorText>
    </div>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          :disabled="submitting"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          type="button"
          class="px-4 py-1.5 text-xs text-white rounded-md font-medium disabled:opacity-50"
          :class="isRemove ? 'bg-rose-600 hover:bg-rose-700' : 'bg-muw-blue hover:bg-muw-blue-700'"
          :disabled="!canConfirm"
          data-testid="study-lifecycle-confirm"
          @click="confirm"
        >
          {{ submitting ? t('common.saving') : isRemove ? t('studyLifecycle.removeConfirm') : t('studyLifecycle.restoreConfirm') }}
        </button>
      </div>
    </template>
  </Modal>
</template>

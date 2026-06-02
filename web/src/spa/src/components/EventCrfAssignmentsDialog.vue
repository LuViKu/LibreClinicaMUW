<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import StatusPill from '@/components/StatusPill.vue'
import SelectInput from '@/components/SelectInput.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useCrfLibraryStore } from '@/stores/crfLibrary'
import type { EventCrfAssignment, EventCrfAssignmentInput, SdvRequirement } from '@/types/crfLibrary'
import type { EventDefinition } from '@/types/eventDefinition'

/**
 * Phase E A8.3 follow-up — manage the CRF assignments for one
 * event definition.
 *
 * Two surfaces in one modal:
 * - Top: list of currently-attached CRFs with badges (required /
 *   double-entry / SDV mode) + per-row Edit + Remove.
 * - Bottom: "Attach CRF" form with a CRF picker and a default-version
 *   picker. The 14 backend fields are split: the compact attach form
 *   surfaces the four most-used flags (required, doubleEntry, SDV,
 *   electronicSignature); the full edit panel (toggled via "Show
 *   advanced") exposes the participant-form / Enketo cluster.
 */
interface Props {
  open: boolean
  studyOid: string | null
  eventDef: EventDefinition | null
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:open': [v: boolean]; close: [] }>()

const { t } = useI18n()
const lib = useCrfLibraryStore()

const assignments = ref<EventCrfAssignment[]>([])
const isLoading = ref(false)
const loadError = ref<string | null>(null)

async function refresh() {
  if (!props.studyOid || !props.eventDef) return
  isLoading.value = true
  loadError.value = null
  try {
    assignments.value = await lib.listAssignments(props.studyOid, props.eventDef.oid)
  } catch (e) {
    assignments.value = []
    loadError.value = e instanceof Error ? e.message : 'Unknown error'
  } finally {
    isLoading.value = false
  }
}

watch(
  () => [props.open, props.eventDef] as const,
  ([isOpen]) => {
    if (isOpen) {
      // Load CRFs from the library so the picker has options; only
      // fetch if the cache is empty (already loaded means it's
      // already up to date).
      if (lib.crfs.length === 0) lib.loadCrfs(false)
      refresh()
      resetAttachForm()
      editing.value = null
    }
  },
)

/* --------------------------- Attach form --------------------------- */

interface AttachForm {
  crfOid: string
  defaultVersionOid: string
  required: boolean
  doubleEntry: boolean
  electronicSignature: boolean
  sourceDataVerification: SdvRequirement
}
const attach = ref<AttachForm>(initialAttachForm())
const attachErrors = ref<Record<string, string>>({})
const attachFormError = ref<string | null>(null)
const isAttaching = ref(false)

function initialAttachForm(): AttachForm {
  return {
    crfOid: '',
    defaultVersionOid: '',
    required: true,
    doubleEntry: false,
    electronicSignature: false,
    sourceDataVerification: 'NOTREQUIRED',
  }
}

function resetAttachForm() {
  attach.value = initialAttachForm()
  attachErrors.value = {}
  attachFormError.value = null
}

/** Eligible CRFs = library CRFs that aren't already attached (or whose
    existing assignment is removed). */
const eligibleCrfs = computed(() => {
  const attached = new Set(assignments.value.filter((a) => a.status !== 'removed').map((a) => a.crfOid))
  return lib.crfs.filter((c) => c.status !== 'removed' && !attached.has(c.oid))
})

/** Versions for the picked CRF, active only. */
const versionsForPick = computed(() => {
  const crf = lib.crfs.find((c) => c.oid === attach.value.crfOid)
  if (!crf) return []
  return crf.versions.filter((v) => v.status !== 'removed')
})

watch(() => attach.value.crfOid, (oid) => {
  // Reset version when CRF changes, and pre-select the latest version
  // if there's only one (common case for newly-created CRFs).
  const crf = lib.crfs.find((c) => c.oid === oid)
  const vs = crf ? crf.versions.filter((v) => v.status !== 'removed') : []
  attach.value.defaultVersionOid = vs.length === 1 ? vs[0].oid : ''
})

async function submitAttach() {
  if (!props.studyOid || !props.eventDef) return
  if (attach.value.crfOid === '' || attach.value.defaultVersionOid === '') return
  attachErrors.value = {}
  attachFormError.value = null
  isAttaching.value = true
  try {
    const body: EventCrfAssignmentInput = {
      crfOid: attach.value.crfOid,
      defaultVersionOid: attach.value.defaultVersionOid,
      required: attach.value.required,
      doubleEntry: attach.value.doubleEntry,
      electronicSignature: attach.value.electronicSignature,
      sourceDataVerification: attach.value.sourceDataVerification,
    }
    const result = await lib.attachCrf(props.studyOid, props.eventDef.oid, body)
    if (result.ok) {
      resetAttachForm()
      await refresh()
    } else {
      attachErrors.value = result.fieldErrors
      attachFormError.value = result.message ?? null
    }
  } finally {
    isAttaching.value = false
  }
}

/* --------------------------- Edit panel --------------------------- */

interface EditState {
  crfOid: string
  crfName: string
  defaultVersionOid: string
  required: boolean
  doubleEntry: boolean
  decisionCondition: boolean
  electronicSignature: boolean
  hideCrf: boolean
  sourceDataVerification: SdvRequirement
  participantForm: boolean
  allowAnonymousSubmission: boolean
  submissionUrl: string
}
const editing = ref<EditState | null>(null)
const editErrors = ref<Record<string, string>>({})
const editFormError = ref<string | null>(null)
const isSavingEdit = ref(false)
const showAdvanced = ref(false)

function openEdit(a: EventCrfAssignment) {
  editing.value = {
    crfOid: a.crfOid,
    crfName: a.crfName,
    defaultVersionOid: a.defaultVersionOid,
    required: a.required,
    doubleEntry: a.doubleEntry,
    decisionCondition: a.decisionCondition,
    electronicSignature: a.electronicSignature,
    hideCrf: a.hideCrf,
    sourceDataVerification: a.sourceDataVerification,
    participantForm: a.participantForm,
    allowAnonymousSubmission: a.allowAnonymousSubmission,
    submissionUrl: a.submissionUrl,
  }
  editErrors.value = {}
  editFormError.value = null
  showAdvanced.value = false
}

const versionsForEdit = computed(() => {
  if (!editing.value) return []
  const crf = lib.crfs.find((c) => c.oid === editing.value!.crfOid)
  if (!crf) return []
  return crf.versions.filter((v) => v.status !== 'removed')
})

async function submitEdit() {
  if (!props.studyOid || !props.eventDef || !editing.value) return
  editErrors.value = {}
  editFormError.value = null
  isSavingEdit.value = true
  try {
    const body: EventCrfAssignmentInput = {
      defaultVersionOid: editing.value.defaultVersionOid,
      required: editing.value.required,
      doubleEntry: editing.value.doubleEntry,
      decisionCondition: editing.value.decisionCondition,
      electronicSignature: editing.value.electronicSignature,
      hideCrf: editing.value.hideCrf,
      sourceDataVerification: editing.value.sourceDataVerification,
      participantForm: editing.value.participantForm,
      allowAnonymousSubmission: editing.value.allowAnonymousSubmission,
      submissionUrl: editing.value.submissionUrl,
    }
    const result = await lib.updateAssignment(props.studyOid, props.eventDef.oid, editing.value.crfOid, body)
    if (result.ok) {
      editing.value = null
      await refresh()
    } else {
      editErrors.value = result.fieldErrors
      editFormError.value = result.message ?? null
    }
  } finally {
    isSavingEdit.value = false
  }
}

/* ----------------------------- Remove ---------------------------- */

async function onRemove(a: EventCrfAssignment) {
  if (!props.studyOid || !props.eventDef) return
  if (!confirm(t('assignCrfs.removeConfirm', { crf: a.crfName, event: props.eventDef.name }))) return
  const ok = await lib.removeAssignment(props.studyOid, props.eventDef.oid, a.crfOid)
  if (ok) await refresh()
}

/* ----------------------------- Helpers --------------------------- */

const sdvOptions: { v: SdvRequirement; l: () => string }[] = [
  { v: 'NOTREQUIRED',    l: () => t('assignCrfs.sdv.NOTREQUIRED') },
  { v: 'PARTIALREQUIRED', l: () => t('assignCrfs.sdv.PARTIALREQUIRED') },
  { v: 'AllREQUIRED',    l: () => t('assignCrfs.sdv.AllREQUIRED') },
  { v: 'NOTAPPLICABLE',  l: () => t('assignCrfs.sdv.NOTAPPLICABLE') },
]

function sdvLabel(v: SdvRequirement): string {
  return t(`assignCrfs.sdv.${v}`)
}

function close() {
  emit('update:open', false)
  emit('close')
}

const activeAssignments = computed(() =>
  assignments.value.filter((a) => a.status !== 'removed'),
)
</script>

<template>
  <Modal :open="props.open" labelled-by="assign-crfs-title" panel-class="max-w-4xl" @update:open="(v) => emit('update:open', v)" @close="close">
    <template #header>
      <div>
        <h2 id="assign-crfs-title" class="text-lg font-semibold tracking-tight">
          {{ t('assignCrfs.title') }}
        </h2>
        <p v-if="props.eventDef" class="text-xs text-slate-500 mt-0.5">
          {{ props.eventDef.name }} · <span class="font-mono">{{ props.eventDef.oid }}</span>
        </p>
      </div>
    </template>

    <div v-if="props.eventDef" class="space-y-6">
      <!-- Current assignments -->
      <section>
        <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-500 mb-2">
          {{ t('assignCrfs.attachedHeading') }}
        </h3>
        <p v-if="isLoading" class="text-xs italic text-slate-500">{{ t('common.loading') }}</p>
        <p v-else-if="loadError" class="text-xs text-rose-600">{{ loadError }}</p>
        <p v-else-if="activeAssignments.length === 0" class="text-xs italic text-slate-500">
          {{ t('assignCrfs.empty') }}
        </p>
        <ul v-else class="space-y-1.5">
          <li
            v-for="a in activeAssignments"
            :key="a.crfOid"
            class="rounded-md border border-slate-200 bg-white p-3"
          >
            <div class="flex items-start gap-3">
              <div class="flex-1 min-w-0">
                <div class="flex items-baseline gap-2 flex-wrap">
                  <span class="font-medium text-slate-800">{{ a.crfName }}</span>
                  <span class="font-mono text-[10px] text-slate-400">{{ a.crfOid }}</span>
                  <span class="text-xs text-slate-500">{{ t('assignCrfs.version') }}: {{ a.defaultVersionName }}</span>
                </div>
                <div class="mt-1 flex items-center gap-1.5 flex-wrap">
                  <StatusPill v-if="a.required" variant="warning">{{ t('assignCrfs.required') }}</StatusPill>
                  <StatusPill v-if="a.doubleEntry" variant="info">{{ t('assignCrfs.doubleEntry') }}</StatusPill>
                  <StatusPill v-if="a.electronicSignature" variant="info">{{ t('assignCrfs.electronicSignature') }}</StatusPill>
                  <StatusPill v-if="a.hideCrf" variant="neutral">{{ t('assignCrfs.hideCrf') }}</StatusPill>
                  <StatusPill v-if="a.participantForm" variant="info">{{ t('assignCrfs.participantForm') }}</StatusPill>
                  <StatusPill variant="neutral">SDV: {{ sdvLabel(a.sourceDataVerification) }}</StatusPill>
                </div>
              </div>
              <div class="flex items-center gap-2 text-xs shrink-0">
                <button class="text-muw-blue hover:underline" @click="openEdit(a)">
                  {{ t('assignCrfs.edit') }}
                </button>
                <span class="text-slate-300">·</span>
                <button class="text-rose-600 hover:underline" @click="onRemove(a)">
                  {{ t('assignCrfs.remove') }}
                </button>
              </div>
            </div>
          </li>
        </ul>
      </section>

      <!-- Inline edit panel -->
      <section
        v-if="editing"
        class="rounded-md border border-amber-200 bg-amber-50 p-3"
      >
        <div class="flex items-baseline justify-between mb-3">
          <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-700">
            {{ t('assignCrfs.editHeading', { crf: editing.crfName }) }}
          </h3>
          <button
            class="text-[10px] uppercase tracking-wider text-muw-blue hover:underline"
            @click="showAdvanced = !showAdvanced"
          >{{ showAdvanced ? t('assignCrfs.hideAdvanced') : t('assignCrfs.showAdvanced') }}</button>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="ec-edit-version" required>{{ t('assignCrfs.defaultVersion') }}</FieldLabel>
            <SelectInput id="ec-edit-version" v-model="editing.defaultVersionOid">
              <option v-for="v in versionsForEdit" :key="v.oid" :value="v.oid">{{ v.name }}</option>
            </SelectInput>
            <ErrorText v-if="editErrors.defaultVersionOid">{{ editErrors.defaultVersionOid }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="ec-edit-sdv">{{ t('assignCrfs.sdvLabel') }}</FieldLabel>
            <SelectInput id="ec-edit-sdv" v-model="editing.sourceDataVerification">
              <option v-for="o in sdvOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
            </SelectInput>
          </div>
          <div class="space-y-1.5">
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="editing.required" /> {{ t('assignCrfs.required') }}
            </label>
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="editing.doubleEntry" /> {{ t('assignCrfs.doubleEntry') }}
            </label>
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="editing.electronicSignature" /> {{ t('assignCrfs.electronicSignature') }}
            </label>
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="editing.hideCrf" /> {{ t('assignCrfs.hideCrf') }}
            </label>
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="editing.decisionCondition" /> {{ t('assignCrfs.decisionCondition') }}
            </label>
          </div>

          <!-- Advanced section: Enketo / participant-form fields -->
          <div v-if="showAdvanced" class="col-span-2 mt-2 pt-2 border-t border-amber-200 space-y-2">
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="editing.participantForm" /> {{ t('assignCrfs.participantForm') }}
            </label>
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="editing.allowAnonymousSubmission" /> {{ t('assignCrfs.allowAnonymousSubmission') }}
            </label>
            <div>
              <FieldLabel for="ec-edit-submission-url">{{ t('assignCrfs.submissionUrl') }}</FieldLabel>
              <TextInput id="ec-edit-submission-url" v-model="editing.submissionUrl" />
            </div>
          </div>
        </div>

        <ErrorText v-if="editFormError">{{ editFormError }}</ErrorText>

        <div class="mt-3 flex items-center gap-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="editing = null"
          >{{ t('common.cancel') }}</button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="isSavingEdit"
            @click="submitEdit"
          >{{ isSavingEdit ? t('common.saving') : t('assignCrfs.submitEdit') }}</button>
        </div>
      </section>

      <!-- Attach form -->
      <section v-if="eligibleCrfs.length > 0">
        <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-500 mb-2">
          {{ t('assignCrfs.attachHeading') }}
        </h3>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <FieldLabel for="ec-attach-crf" required>{{ t('assignCrfs.crf') }}</FieldLabel>
            <SelectInput id="ec-attach-crf" v-model="attach.crfOid">
              <option value="">{{ t('assignCrfs.crfPlaceholder') }}</option>
              <option v-for="c in eligibleCrfs" :key="c.oid" :value="c.oid">{{ c.name }}</option>
            </SelectInput>
            <ErrorText v-if="attachErrors.crfOid">{{ attachErrors.crfOid }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="ec-attach-version" required>{{ t('assignCrfs.defaultVersion') }}</FieldLabel>
            <SelectInput id="ec-attach-version" v-model="attach.defaultVersionOid" :disabled="versionsForPick.length === 0">
              <option value="">{{ t('assignCrfs.versionPlaceholder') }}</option>
              <option v-for="v in versionsForPick" :key="v.oid" :value="v.oid">{{ v.name }}</option>
            </SelectInput>
            <ErrorText v-if="attachErrors.defaultVersionOid">{{ attachErrors.defaultVersionOid }}</ErrorText>
            <p v-if="attach.crfOid !== '' && versionsForPick.length === 0" class="text-xs text-rose-600 mt-1">
              {{ t('assignCrfs.noVersionsNote') }}
            </p>
          </div>
          <div>
            <FieldLabel for="ec-attach-sdv">{{ t('assignCrfs.sdvLabel') }}</FieldLabel>
            <SelectInput id="ec-attach-sdv" v-model="attach.sourceDataVerification">
              <option v-for="o in sdvOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
            </SelectInput>
          </div>
          <div class="space-y-1.5 pt-5">
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="attach.required" /> {{ t('assignCrfs.required') }}
            </label>
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="attach.doubleEntry" /> {{ t('assignCrfs.doubleEntry') }}
            </label>
            <label class="flex items-center gap-1.5 text-xs">
              <input type="checkbox" v-model="attach.electronicSignature" /> {{ t('assignCrfs.electronicSignature') }}
            </label>
          </div>
        </div>
        <ErrorText v-if="attachFormError">{{ attachFormError }}</ErrorText>
        <div class="mt-3">
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="attach.crfOid === '' || attach.defaultVersionOid === '' || isAttaching"
            @click="submitAttach"
          >{{ isAttaching ? t('common.saving') : t('assignCrfs.submitAttach') }}</button>
        </div>
      </section>

      <p v-else class="text-xs italic text-slate-500">
        {{ t('assignCrfs.noEligibleCrfsNote') }}
      </p>
    </div>

    <template #footer>
      <div class="text-xs text-slate-500">{{ t('assignCrfs.auditNote') }}</div>
      <button
        class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
        @click="close"
      >{{ t('common.cancel') }}</button>
    </template>
  </Modal>
</template>

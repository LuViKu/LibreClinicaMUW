<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useStudyParametersStore } from '@/stores/studyParameters'
import { ApiError } from '@/api/client'
import { ALLOWED, type UpdateStudyParametersInput } from '@/types/studyParameters'

/**
 * Phase E.6 study-params — Study parameters edit view.
 *
 * Renders the 18 study_parameter_value handles as a section-grouped
 * form (Subject ID / Discrepancy / Interviewer / Modules) and submits
 * the partial patch via {@link useStudyParametersStore.update}.
 *
 * Auth: gated by router meta {@code role: 'Administrator'} mirroring
 * StudyIdentityEditView. The backend re-checks authoritatively via
 * StudyAdminAuthorization.roleMayEditStudy and returns 403 if the
 * caller cannot edit this study (re-thrown by the store, caught here
 * to redirect to the study picker with a flash message).
 *
 * Downstream consumers (AddSubjectView field visibility, CrfEntryView
 * header defaults) live in their own clusters per playbook §3.2
 * sequencing — this view ships the read/write surface only.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useStudyParametersStore()

const oid = computed(() => String(route.params.oid))

// Local form mirror — every value tracked as a string. We seed from
// the store on mount and submit only fields that differ from the
// store's current snapshot (partial patch).
interface FormShape {
  subjectIdGeneration: string
  subjectIdPrefixSuffix: string
  subjectPersonIdRequired: string
  personIdShownOnCRF: string
  collectDob: string
  genderRequired: string
  eventLocationRequired: string
  discrepancyManagement: string
  interviewerNameRequired: string
  interviewerNameDefault: string
  interviewerNameEditable: string
  interviewDateRequired: string
  interviewDateDefault: string
  interviewDateEditable: string
  secondaryLabelViewable: string
  adminForcedReasonForChange: string
  participantPortal: string
  randomization: string
}
const HANDLES: (keyof FormShape)[] = [
  'subjectIdGeneration',
  'subjectIdPrefixSuffix',
  'subjectPersonIdRequired',
  'personIdShownOnCRF',
  'collectDob',
  'genderRequired',
  'eventLocationRequired',
  'discrepancyManagement',
  'interviewerNameRequired',
  'interviewerNameDefault',
  'interviewerNameEditable',
  'interviewDateRequired',
  'interviewDateDefault',
  'interviewDateEditable',
  'secondaryLabelViewable',
  'adminForcedReasonForChange',
  'participantPortal',
  'randomization',
]
const form = ref<FormShape>(blankForm())
const savedAt = ref<number | null>(null)

function blankForm(): FormShape {
  return {
    subjectIdGeneration: 'manual',
    subjectIdPrefixSuffix: 'true',
    subjectPersonIdRequired: 'required',
    personIdShownOnCRF: 'false',
    collectDob: '1',
    genderRequired: 'true',
    eventLocationRequired: 'not_used',
    discrepancyManagement: 'true',
    interviewerNameRequired: 'not_used',
    interviewerNameDefault: 'blank',
    interviewerNameEditable: 'true',
    interviewDateRequired: 'not_used',
    interviewDateDefault: 'blank',
    interviewDateEditable: 'true',
    secondaryLabelViewable: 'false',
    adminForcedReasonForChange: 'true',
    participantPortal: 'disabled',
    randomization: 'disabled',
  }
}

onMounted(async () => {
  try {
    await store.load(oid.value)
    if (store.current) {
      for (const h of HANDLES) {
        ;(form.value[h] as string) = store.current[h]
      }
    }
  } catch (e) {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      router.replace({ name: 'study-picker' })
    }
  }
})

onUnmounted(() => store.reset())

async function submit() {
  savedAt.value = null
  if (!store.current) return
  const patch: UpdateStudyParametersInput = {}
  for (const h of HANDLES) {
    const v = form.value[h]
    if (v !== store.current[h]) patch[h] = v
  }
  if (Object.keys(patch).length === 0) {
    savedAt.value = Date.now()
    return
  }
  try {
    const result = await store.update(oid.value, patch)
    if (result.ok) {
      savedAt.value = Date.now()
      // Mirror store back into form so subsequent edits diff from the
      // freshly persisted state, not the pre-patch local copy.
      if (store.current) {
        for (const h of HANDLES) {
          ;(form.value[h] as string) = (store.current as FormShape)[h]
        }
      }
    }
  } catch (e) {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      router.replace({ name: 'study-picker' })
    }
  }
}

function cancel() {
  router.push({ name: 'build-study', params: { oid: oid.value } })
}

// Helper for v-for label rendering — keeps template terse.
const enumLabel = (group: string, value: string) =>
  t(`studyParameters.enums.${group}.${value}`, value)
</script>

<template>
  <div class="grid grid-cols-[260px_1fr] min-h-screen bg-white">
    <SideRail />

    <main class="px-6 py-5">
      <div class="flex items-baseline justify-between mb-5">
        <h1 class="text-base font-medium text-slate-800">
          {{ t('studyParameters.title') }}
        </h1>
        <span class="text-xs text-slate-500">{{ oid }}</span>
      </div>

      <p v-if="store.isLoading" class="text-xs italic text-slate-500 mb-3">
        {{ t('common.loading') }}
      </p>
      <p v-if="store.error" class="text-xs text-rose-600 mb-3">{{ store.error }}</p>

      <div
        v-if="savedAt !== null"
        class="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-900 mb-4"
        role="status"
      >
        {{ t('studyParameters.saved') }}
      </div>

      <p class="text-sm text-slate-700 mb-5">{{ t('studyParameters.intro') }}</p>

      <!-- Subject ID / identity -->
      <section class="space-y-4 mb-6">
        <h2 class="text-sm font-medium text-slate-700">{{ t('studyParameters.sections.subjectId') }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <FieldLabel for="sp-subjectIdGeneration">{{ t('studyParameters.fields.subjectIdGeneration') }}</FieldLabel>
            <SelectInput id="sp-subjectIdGeneration" v-model="form.subjectIdGeneration"
                         :error="!!store.fieldErrors.subjectIdGeneration">
              <option v-for="o in ALLOWED.subjectIdGeneration" :key="o" :value="o">
                {{ enumLabel('subjectIdGeneration', o) }}
              </option>
            </SelectInput>
            <ErrorText v-if="store.fieldErrors.subjectIdGeneration">
              {{ store.fieldErrors.subjectIdGeneration }}
            </ErrorText>
          </div>
          <div>
            <FieldLabel for="sp-subjectIdPrefixSuffix">{{ t('studyParameters.fields.subjectIdPrefixSuffix') }}</FieldLabel>
            <SelectInput id="sp-subjectIdPrefixSuffix" v-model="form.subjectIdPrefixSuffix">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-subjectPersonIdRequired">{{ t('studyParameters.fields.subjectPersonIdRequired') }}</FieldLabel>
            <SelectInput id="sp-subjectPersonIdRequired" v-model="form.subjectPersonIdRequired">
              <option v-for="o in ALLOWED.requiredOptionalNotUsed" :key="o" :value="o">{{ enumLabel('required', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-personIdShownOnCRF">{{ t('studyParameters.fields.personIdShownOnCRF') }}</FieldLabel>
            <SelectInput id="sp-personIdShownOnCRF" v-model="form.personIdShownOnCRF">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-collectDob">{{ t('studyParameters.fields.collectDob') }}</FieldLabel>
            <SelectInput id="sp-collectDob" v-model="form.collectDob"
                         :error="!!store.fieldErrors.collectDob">
              <option v-for="o in ALLOWED.collectDob" :key="o" :value="o">{{ enumLabel('collectDob', o) }}</option>
            </SelectInput>
            <ErrorText v-if="store.fieldErrors.collectDob">{{ store.fieldErrors.collectDob }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="sp-genderRequired">{{ t('studyParameters.fields.genderRequired') }}</FieldLabel>
            <SelectInput id="sp-genderRequired" v-model="form.genderRequired">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-eventLocationRequired">{{ t('studyParameters.fields.eventLocationRequired') }}</FieldLabel>
            <SelectInput id="sp-eventLocationRequired" v-model="form.eventLocationRequired">
              <option v-for="o in ALLOWED.requiredOptionalNotUsed" :key="o" :value="o">{{ enumLabel('required', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-secondaryLabelViewable">{{ t('studyParameters.fields.secondaryLabelViewable') }}</FieldLabel>
            <SelectInput id="sp-secondaryLabelViewable" v-model="form.secondaryLabelViewable">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
        </div>
      </section>

      <!-- Discrepancy + RFC -->
      <section class="space-y-4 mb-6">
        <h2 class="text-sm font-medium text-slate-700">{{ t('studyParameters.sections.discrepancy') }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <FieldLabel for="sp-discrepancyManagement">{{ t('studyParameters.fields.discrepancyManagement') }}</FieldLabel>
            <SelectInput id="sp-discrepancyManagement" v-model="form.discrepancyManagement">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-adminForcedReasonForChange">{{ t('studyParameters.fields.adminForcedReasonForChange') }}</FieldLabel>
            <SelectInput id="sp-adminForcedReasonForChange" v-model="form.adminForcedReasonForChange">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
        </div>
      </section>

      <!-- Interviewer + Interview date -->
      <section class="space-y-4 mb-6">
        <h2 class="text-sm font-medium text-slate-700">{{ t('studyParameters.sections.interviewer') }}</h2>
        <div class="grid grid-cols-3 gap-3">
          <div>
            <FieldLabel for="sp-interviewerNameRequired">{{ t('studyParameters.fields.interviewerNameRequired') }}</FieldLabel>
            <SelectInput id="sp-interviewerNameRequired" v-model="form.interviewerNameRequired">
              <option v-for="o in ALLOWED.requiredOptionalNotUsed" :key="o" :value="o">{{ enumLabel('required', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-interviewerNameDefault">{{ t('studyParameters.fields.interviewerNameDefault') }}</FieldLabel>
            <SelectInput id="sp-interviewerNameDefault" v-model="form.interviewerNameDefault">
              <option v-for="o in ALLOWED.blankPrepopulated" :key="o" :value="o">{{ enumLabel('blankPrepopulated', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-interviewerNameEditable">{{ t('studyParameters.fields.interviewerNameEditable') }}</FieldLabel>
            <SelectInput id="sp-interviewerNameEditable" v-model="form.interviewerNameEditable">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-interviewDateRequired">{{ t('studyParameters.fields.interviewDateRequired') }}</FieldLabel>
            <SelectInput id="sp-interviewDateRequired" v-model="form.interviewDateRequired">
              <option v-for="o in ALLOWED.requiredOptionalNotUsed" :key="o" :value="o">{{ enumLabel('required', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-interviewDateDefault">{{ t('studyParameters.fields.interviewDateDefault') }}</FieldLabel>
            <SelectInput id="sp-interviewDateDefault" v-model="form.interviewDateDefault">
              <option v-for="o in ALLOWED.blankPrepopulated" :key="o" :value="o">{{ enumLabel('blankPrepopulated', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-interviewDateEditable">{{ t('studyParameters.fields.interviewDateEditable') }}</FieldLabel>
            <SelectInput id="sp-interviewDateEditable" v-model="form.interviewDateEditable">
              <option v-for="o in ALLOWED.bool" :key="o" :value="o">{{ enumLabel('bool', o) }}</option>
            </SelectInput>
          </div>
        </div>
      </section>

      <!-- Modules -->
      <section class="space-y-4 mb-6">
        <h2 class="text-sm font-medium text-slate-700">{{ t('studyParameters.sections.modules') }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <FieldLabel for="sp-participantPortal">{{ t('studyParameters.fields.participantPortal') }}</FieldLabel>
            <SelectInput id="sp-participantPortal" v-model="form.participantPortal">
              <option v-for="o in ALLOWED.enabledDisabled" :key="o" :value="o">{{ enumLabel('enabledDisabled', o) }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="sp-randomization">{{ t('studyParameters.fields.randomization') }}</FieldLabel>
            <SelectInput id="sp-randomization" v-model="form.randomization">
              <option v-for="o in ALLOWED.enabledDisabled" :key="o" :value="o">{{ enumLabel('enabledDisabled', o) }}</option>
            </SelectInput>
          </div>
        </div>
      </section>

      <div class="flex items-center gap-2 pt-2">
        <button
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="cancel"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="store.isSaving || store.isLoading"
          @click="submit"
        >
          {{ store.isSaving ? t('common.saving') : t('studyParameters.save') }}
        </button>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useStudyStore } from '@/stores/study'

/**
 * Phase E A8.1 — Create Study view (sysadmin only).
 *
 * Single-page form collapsing the legacy 6-page CreateStudyServlet
 * wizard. Required fields drive the submit button; the backend
 * re-validates and returns per-field errors on collision (name /
 * uniqueProtocolId already taken).
 *
 * On 201 the new study's OID is the SPA's confirmation — we navigate
 * directly to its build dashboard so the next task tiles (event
 * definitions, CRFs, sites) become the natural follow-up.
 */
const { t } = useI18n()
const router = useRouter()
const studies = useStudyStore()

interface Form {
  name: string
  uniqueProtocolId: string
  briefSummary: string
  principalInvestigator: string
  sponsor: string
  officialTitle: string
  secondaryProtocolId: string
  protocolType: string
  phase: string
}

function blankForm(): Form {
  return {
    name: '',
    uniqueProtocolId: '',
    briefSummary: '',
    principalInvestigator: '',
    sponsor: '',
    officialTitle: '',
    secondaryProtocolId: '',
    protocolType: 'Interventional',
    phase: '',
  }
}

const form = ref<Form>(blankForm())
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)

const canSubmit = computed(() => {
  return (
    form.value.name.trim().length > 0 &&
    form.value.uniqueProtocolId.trim().length > 0 &&
    form.value.briefSummary.trim().length > 0 &&
    form.value.principalInvestigator.trim().length > 0 &&
    form.value.sponsor.trim().length > 0
  )
})

async function submit() {
  if (!canSubmit.value) return
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    const result = await studies.create({
      name: form.value.name.trim(),
      uniqueProtocolId: form.value.uniqueProtocolId.trim(),
      briefSummary: form.value.briefSummary.trim(),
      principalInvestigator: form.value.principalInvestigator.trim(),
      sponsor: form.value.sponsor.trim(),
      officialTitle: form.value.officialTitle.trim() || undefined,
      secondaryProtocolId: form.value.secondaryProtocolId.trim() || undefined,
      protocolType: form.value.protocolType.trim() || undefined,
      phase: form.value.phase.trim() || undefined,
    })
    if (result.ok) {
      // Land directly on the new study's build dashboard.
      router.push('/build-study')
    } else {
      fieldErrors.value = result.fieldErrors
      formError.value = result.message ?? null
    }
  } finally {
    isSubmitting.value = false
  }
}

function cancel() {
  router.push('/build-study')
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.buildStudy') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-3xl px-8 py-6">
      <div class="mb-4">
        <div class="text-xs text-slate-500 mb-1">{{ t('studyForm.create.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('studyForm.create.title') }}</h1>
      </div>

      <p class="text-sm text-slate-700 mb-5">{{ t('studyForm.create.intro') }}</p>

      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="study-name" required>{{ t('studyForm.name') }}</FieldLabel>
            <TextInput id="study-name" v-model="form.name" />
            <ErrorText v-if="fieldErrors.name">{{ fieldErrors.name }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="study-uid" required>{{ t('studyForm.uniqueProtocolId') }}</FieldLabel>
            <TextInput id="study-uid" v-model="form.uniqueProtocolId" />
            <p class="text-xs text-slate-500 mt-1">{{ t('studyForm.uniqueProtocolIdHint') }}</p>
            <ErrorText v-if="fieldErrors.uniqueProtocolId">{{ fieldErrors.uniqueProtocolId }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="study-secondary-uid">{{ t('studyForm.secondaryProtocolId') }}</FieldLabel>
            <TextInput id="study-secondary-uid" v-model="form.secondaryProtocolId" />
          </div>
          <div class="col-span-2">
            <FieldLabel for="study-summary" required>{{ t('studyForm.briefSummary') }}</FieldLabel>
            <TextInput id="study-summary" v-model="form.briefSummary" />
            <ErrorText v-if="fieldErrors.briefSummary">{{ fieldErrors.briefSummary }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="study-pi" required>{{ t('studyForm.principalInvestigator') }}</FieldLabel>
            <TextInput id="study-pi" v-model="form.principalInvestigator" />
            <ErrorText v-if="fieldErrors.principalInvestigator">{{ fieldErrors.principalInvestigator }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="study-sponsor" required>{{ t('studyForm.sponsor') }}</FieldLabel>
            <TextInput id="study-sponsor" v-model="form.sponsor" />
            <ErrorText v-if="fieldErrors.sponsor">{{ fieldErrors.sponsor }}</ErrorText>
          </div>
          <div class="col-span-2">
            <FieldLabel for="study-official-title">{{ t('studyForm.officialTitle') }}</FieldLabel>
            <TextInput id="study-official-title" v-model="form.officialTitle" />
          </div>
          <div>
            <FieldLabel for="study-protocol-type">{{ t('studyForm.protocolType') }}</FieldLabel>
            <SelectInput id="study-protocol-type" v-model="form.protocolType">
              <option value="Interventional">{{ t('studyForm.protocolType_Interventional') }}</option>
              <option value="Observational">{{ t('studyForm.protocolType_Observational') }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="study-phase">{{ t('studyForm.phase') }}</FieldLabel>
            <TextInput id="study-phase" v-model="form.phase" />
          </div>
        </div>

        <ErrorText v-if="formError">{{ formError }}</ErrorText>

        <div class="flex items-center gap-2 pt-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="cancel"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="!canSubmit || isSubmitting"
            @click="submit"
          >
            {{ isSubmitting ? t('common.saving') : t('studyForm.create.submit') }}
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

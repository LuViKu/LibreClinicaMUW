<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { apiGet } from '@/api/client'
import { useStudyStore } from '@/stores/study'
import type { StudyIdentity } from '@/types/study'

/**
 * Phase E A8.1 — Study identity edit view.
 *
 * Sysadmin / Director / Coordinator (bound to target) — backend
 * authoritatively re-checks. Loads the current identity from a thin
 * adapter call (reusing the dashboard endpoint isn't ideal — it
 * carries task counts but not the protocol fields — so for this slice
 * we ship a focused GET against the new endpoint, but the legacy
 * BuildStudyApiController doesn't expose identity yet, so this view
 * uses the StudyOption from the picker as a starting point for the
 * commonly-edited fields. The PUT response carries the canonical
 * StudyIdentityDto we hydrate the form with on first edit.).
 *
 * Note: the M12 read-side doesn't yet expose every identity field
 * (collaborators / protocolDescription / contactEmail), so the form
 * starts blank for those fields. An A8.1 follow-up adds
 * GET /api/v1/studies/{oid} so the form can pre-fill — for now this
 * slice ships the edit path with explicit "leave blank to keep current
 * value" semantics.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const studies = useStudyStore()

const oid = computed(() => String(route.params.oid))

interface Form {
  name: string
  briefSummary: string
  principalInvestigator: string
  sponsor: string
  officialTitle: string
  secondaryProtocolId: string
  protocolType: string
  phase: string
}

const form = ref<Form>({
  name: '',
  briefSummary: '',
  principalInvestigator: '',
  sponsor: '',
  officialTitle: '',
  secondaryProtocolId: '',
  protocolType: '',
  phase: '',
})
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isLoading = ref(false)
const isSubmitting = ref(false)
const loadError = ref<string | null>(null)

onMounted(async () => {
  isLoading.value = true
  try {
    // The dashboard already exposes name + identifier — those are
    // enough to seed the form's identity fields. The remaining
    // protocol fields stay blank; the operator can fill anything
    // they want to change and leave the rest untouched (the backend
    // treats undefined as "leave unchanged").
    const dash = await apiGet<{ studyName: string }>(
      `/pages/api/v1/studies/${encodeURIComponent(oid.value)}/build-status`,
    )
    form.value.name = dash.studyName ?? ''
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : 'Unknown error'
  } finally {
    isLoading.value = false
  }
})

async function submit() {
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    // Only forward non-blank fields — blanks mean "leave unchanged"
    // (mirrors the backend's null-vs-string semantics).
    const patch: Record<string, string> = {}
    for (const key of Object.keys(form.value) as (keyof Form)[]) {
      const v = (form.value[key] as string).trim()
      if (v !== '') patch[key] = v
    }
    const result = await studies.update(oid.value, patch as Partial<StudyIdentity>)
    if (result.ok) {
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
        <div class="text-xs text-slate-500 mb-1">{{ t('studyForm.edit.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('studyForm.edit.title') }}</h1>
        <p class="text-xs text-slate-500 mt-1">{{ oid }}</p>
      </div>

      <p v-if="loadError" class="text-xs text-rose-600 mb-3">{{ loadError }}</p>
      <p v-if="isLoading" class="text-xs italic text-slate-500 mb-3">{{ t('common.loading') }}</p>

      <p class="text-sm text-slate-700 mb-5">{{ t('studyForm.edit.intro') }}</p>

      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="study-name">{{ t('studyForm.name') }}</FieldLabel>
            <TextInput id="study-name" v-model="form.name" />
            <ErrorText v-if="fieldErrors.name">{{ fieldErrors.name }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="study-secondary-uid">{{ t('studyForm.secondaryProtocolId') }}</FieldLabel>
            <TextInput id="study-secondary-uid" v-model="form.secondaryProtocolId" />
          </div>
          <div>
            <FieldLabel for="study-phase">{{ t('studyForm.phase') }}</FieldLabel>
            <TextInput id="study-phase" v-model="form.phase" />
          </div>
          <div class="col-span-2">
            <FieldLabel for="study-summary">{{ t('studyForm.briefSummary') }}</FieldLabel>
            <TextInput id="study-summary" v-model="form.briefSummary" />
            <ErrorText v-if="fieldErrors.briefSummary">{{ fieldErrors.briefSummary }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="study-pi">{{ t('studyForm.principalInvestigator') }}</FieldLabel>
            <TextInput id="study-pi" v-model="form.principalInvestigator" />
            <ErrorText v-if="fieldErrors.principalInvestigator">{{ fieldErrors.principalInvestigator }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="study-sponsor">{{ t('studyForm.sponsor') }}</FieldLabel>
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
              <option value="">{{ t('studyForm.edit.leaveUnchanged') }}</option>
              <option value="Interventional">{{ t('studyForm.protocolType_Interventional') }}</option>
              <option value="Observational">{{ t('studyForm.protocolType_Observational') }}</option>
            </SelectInput>
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
            :disabled="isSubmitting"
            @click="submit"
          >
            {{ isSubmitting ? t('common.saving') : t('studyForm.edit.submit') }}
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

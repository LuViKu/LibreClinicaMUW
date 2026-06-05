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
import { useAuthStore } from '@/stores/auth'
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
const auth = useAuthStore()

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
const savedAt = ref<number | null>(null)

onMounted(async () => {
  isLoading.value = true
  try {
    // Phase E.6 (2026-06-03): load the full StudyIdentityDto so every
    // field can be pre-populated. Previously this view only loaded
    // the name from the build-status dashboard and left every other
    // field blank — combined with the PUT endpoint treating blanks
    // as "leave unchanged", that meant operators who hit Save without
    // re-typing every field had ALL of those fields silently dropped.
    const identity = await apiGet<StudyIdentity>(
      `/pages/api/v1/studies/${encodeURIComponent(oid.value)}`,
    )
    form.value = {
      name: identity.name ?? '',
      briefSummary: identity.briefSummary ?? '',
      principalInvestigator: identity.principalInvestigator ?? '',
      sponsor: identity.sponsor ?? '',
      officialTitle: identity.officialTitle ?? '',
      secondaryProtocolId: identity.secondaryProtocolId ?? '',
      protocolType: identity.protocolType ?? '',
      phase: identity.phase ?? '',
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : 'Unknown error'
  } finally {
    isLoading.value = false
  }
})

async function submit() {
  fieldErrors.value = {}
  formError.value = null
  savedAt.value = null
  isSubmitting.value = true
  try {
    // Phase E.6: send EVERY field, not just non-blank ones. The form
    // is now fully pre-populated from the GET so blanking a field
    // means "clear it", not "leave unchanged". The backend trims and
    // accepts empty strings on the optional fields.
    const patch: Record<string, string> = {}
    for (const key of Object.keys(form.value) as (keyof Form)[]) {
      patch[key] = (form.value[key] as string).trim()
    }
    const result = await studies.update(oid.value, patch as Partial<StudyIdentity>)
    if (result.ok) {
      savedAt.value = Date.now()
      // Phase E.6: header breadcrumb derives from auth.user.activeStudy.
      // After a successful save we rehydrate /me so the top bar reflects
      // the new study name (and any other identity bits) without forcing
      // a hard refresh.
      if (auth.user?.activeStudy?.oid === oid.value) {
        void auth.bootstrap()
      }
      // Drop the "saved" banner after 4 seconds; user stays on the view.
      setTimeout(() => {
        if (savedAt.value !== null && Date.now() - savedAt.value >= 3500) {
          savedAt.value = null
        }
      }, 4000)
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

      <!-- Phase E.6: inline success banner — stays for ~4s after a clean save. -->
      <div
        v-if="savedAt !== null"
        class="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-900 mb-4 flex items-center gap-2"
        role="status"
      >
        <svg class="h-3.5 w-3.5 text-emerald-700" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.7-9.3a1 1 0 00-1.4-1.4L9 10.6 7.7 9.3a1 1 0 10-1.4 1.4l2 2a1 1 0 001.4 0l4-4z" clip-rule="evenodd" />
        </svg>
        <span>{{ t('studyForm.edit.saved') }}</span>
      </div>

      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="study-name" required>{{ t('studyForm.name') }}</FieldLabel>
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

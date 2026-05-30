<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'
import StatusPill from '@/components/StatusPill.vue'

import {
  useSubjectsStore,
  validateAddSubject,
  AddSubjectValidationError,
  type AddSubjectErrorField,
  type AddSubjectInput,
} from '@/stores/subjects'
import type { Gender } from '@/types/subject'

const { t } = useI18n()
const router = useRouter()
const subjects = useSubjectsStore()

const todayIso = computed(() => new Date().toISOString().slice(0, 10))

const form = reactive<AddSubjectInput>({
  id: '',
  secondaryId: '',
  siteOid: 'TDS0004',
  siteLabel: 'München',
  gender: '' as Gender, // empty until user picks
  yearOfBirth: null,
  groupLabel: null,
  enrolledOn: todayIso.value,
})

const submitAttempted = ref(false)
const serverError = ref<string | null>(null)

const liveErrors = computed(() => validateAddSubject(form, subjects.rows, { today: todayIso.value }))

function errorFor(field: AddSubjectErrorField): string | null {
  if (!submitAttempted.value) return null
  return liveErrors.value.find((e) => e.field === field)?.message ?? null
}

function setGender(value: Gender) {
  form.gender = value
}

async function submit(redirect: 'matrix' | 'addNext' | 'schedule') {
  submitAttempted.value = true
  serverError.value = null
  if (liveErrors.value.length > 0) return

  try {
    const subject = await subjects.add({ ...form })
    if (redirect === 'matrix') {
      router.push({ name: 'subject-matrix' })
    } else if (redirect === 'addNext') {
      // Reset for next subject; preserve site context.
      form.id = ''
      form.secondaryId = ''
      form.gender = '' as Gender
      form.yearOfBirth = null
      form.groupLabel = null
      form.enrolledOn = todayIso.value
      submitAttempted.value = false
    } else if (redirect === 'schedule') {
      router.push({ path: `/subjects/${encodeURIComponent(subject.id)}` })
    }
  } catch (err) {
    if (err instanceof AddSubjectValidationError) {
      // Fall through — liveErrors already surfaces the issue inline.
      return
    }
    serverError.value = err instanceof Error ? err.message : 'Unknown server error'
  }
}

const genderOptions: { code: Gender; label: () => string }[] = [
  { code: 'F', label: () => t('addSubject.gender.female') },
  { code: 'M', label: () => t('addSubject.gender.male') },
  { code: 'O', label: () => t('addSubject.gender.other') },
  { code: 'U', label: () => t('addSubject.gender.unknown') },
]

const yearMin = 1900
const yearMax = computed(() => Number(todayIso.value.slice(0, 4)))
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink
        to="/"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
          <polyline points="9 22 9 12 15 12 15 22" />
        </svg>
        {{ t('nav.home') }}
      </RouterLink>

      <RouterLink
        to="/subjects"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <rect width="18" height="18" x="3" y="3" rx="2" />
          <path d="M3 9h18M9 21V9" />
        </svg>
        {{ t('nav.subjectMatrix') }}
      </RouterLink>

      <RouterLink
        to="/subjects/new"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium"
        aria-current="page"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <circle cx="12" cy="8" r="5" />
          <path d="M20 21a8 8 0 1 0-16 0" />
          <path d="M19 16v6M22 19h-6" />
        </svg>
        {{ t('nav.addSubject') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-3xl px-8 py-8">
      <div class="mb-6">
        <div class="text-xs text-slate-500 mb-1">{{ form.siteLabel }} · {{ t('addSubject.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('addSubject.title') }}</h1>
        <p class="text-slate-500 text-xs mt-1 leading-relaxed">{{ t('addSubject.intro') }}</p>
      </div>

      <form
        class="bg-white border border-slate-200 rounded-muw p-6 space-y-6"
        novalidate
        @submit.prevent="submit('matrix')"
      >
        <!-- Identification section -->
        <section>
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
            {{ t('addSubject.section.identification') }}
          </h2>

          <div class="grid grid-cols-2 gap-x-6 gap-y-4">
            <div>
              <FieldLabel for="subject-id" required>{{ t('addSubject.field.subjectId') }}</FieldLabel>
              <TextInput
                id="subject-id"
                v-model="form.id"
                :placeholder="t('addSubject.placeholder.subjectId')"
                :error="errorFor('id') != null"
                autocomplete="off"
              />
              <HelperText>{{ t('addSubject.helper.subjectId') }}</HelperText>
              <ErrorText v-if="errorFor('id')">{{ errorFor('id') }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="secondary-id">{{ t('addSubject.field.secondaryId') }}</FieldLabel>
              <TextInput
                id="secondary-id"
                v-model="form.secondaryId"
                :placeholder="t('addSubject.placeholder.secondaryId')"
                :error="errorFor('secondaryId') != null"
                autocomplete="off"
              />
              <p class="mt-1 text-[11px] text-rose-600 flex items-start gap-1">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
                  <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                  <line x1="12" x2="12" y1="9" y2="13" />
                  <line x1="12" x2="12.01" y1="17" y2="17" />
                </svg>
                {{ t('addSubject.phiWarning') }}
              </p>
              <ErrorText v-if="errorFor('secondaryId')">{{ errorFor('secondaryId') }}</ErrorText>
            </div>
          </div>
        </section>

        <hr class="border-slate-200" />

        <!-- Enrolment section -->
        <section>
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
            {{ t('addSubject.section.enrolment') }}
          </h2>

          <div class="grid grid-cols-2 gap-x-6 gap-y-4">
            <div>
              <FieldLabel for="enrolled-on" required>{{ t('addSubject.field.enrolledOn') }}</FieldLabel>
              <TextInput
                id="enrolled-on"
                v-model="form.enrolledOn"
                type="text"
                placeholder="YYYY-MM-DD"
                :error="errorFor('enrolledOn') != null"
                inputmode="numeric"
              />
              <ErrorText v-if="errorFor('enrolledOn')">{{ errorFor('enrolledOn') }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="gender-group" required>{{ t('addSubject.field.gender') }}</FieldLabel>
              <div
                id="gender-group"
                class="grid grid-cols-4 gap-2"
                role="radiogroup"
                :aria-label="t('addSubject.field.gender')"
                :aria-invalid="errorFor('gender') != null"
              >
                <label
                  v-for="opt in genderOptions"
                  :key="opt.code"
                  class="flex items-center justify-center gap-2 px-3 py-2 border rounded-md cursor-pointer text-xs font-medium transition-colors"
                  :class="form.gender === opt.code
                    ? 'border-muw-blue-200 bg-muw-blue-50 text-muw-blue'
                    : 'border-slate-300 hover:bg-slate-50 text-slate-700'"
                >
                  <input
                    type="radio"
                    name="gender"
                    class="sr-only"
                    :value="opt.code"
                    :checked="form.gender === opt.code"
                    @change="setGender(opt.code)"
                  />
                  <span>{{ opt.label() }}</span>
                </label>
              </div>
              <ErrorText v-if="errorFor('gender')">{{ errorFor('gender') }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="year-of-birth">{{ t('addSubject.field.yearOfBirth') }}</FieldLabel>
              <input
                id="year-of-birth"
                v-model.number="form.yearOfBirth"
                type="number"
                :min="yearMin"
                :max="yearMax"
                :placeholder="t('addSubject.placeholder.yearOfBirth')"
                class="w-full px-3 py-2 border rounded-md focus:outline-none transition-colors muw-focus"
                :class="errorFor('yearOfBirth')
                  ? 'border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100'
                  : 'border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100'"
              />
              <HelperText>{{ t('addSubject.helper.yearOfBirth') }}</HelperText>
              <ErrorText v-if="errorFor('yearOfBirth')">{{ errorFor('yearOfBirth') }}</ErrorText>
            </div>

            <div>
              <FieldLabel for="group-label">{{ t('addSubject.field.groupLabel') }}</FieldLabel>
              <SelectInput id="group-label" v-model="form.groupLabel">
                <option :value="null">{{ t('addSubject.group.notAssigned') }}</option>
                <option value="Arm A">Arm A</option>
                <option value="Arm B">Arm B</option>
              </SelectInput>
            </div>
          </div>
        </section>

        <hr class="border-slate-200" />

        <!-- Live preview pill -->
        <div class="flex items-center gap-2 text-xs">
          <span class="text-slate-500">{{ t('addSubject.preview') }}</span>
          <StatusPill variant="info">
            {{ form.id || '—' }} · {{ form.gender || '?' }} · {{ form.enrolledOn || '—' }}
          </StatusPill>
        </div>

        <!-- Server error region -->
        <div
          v-if="serverError"
          class="rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800"
          role="alert"
        >
          {{ serverError }}
        </div>

        <!-- Save action row -->
        <div class="flex items-center justify-between pt-1">
          <RouterLink to="/subjects" class="text-xs text-slate-500 hover:text-slate-700">
            {{ t('addSubject.cancelLink') }}
          </RouterLink>

          <div class="flex items-center gap-2">
            <button
              type="button"
              class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
              :disabled="subjects.isLoading"
              @click="submit('addNext')"
            >
              {{ t('addSubject.action.saveAndAddNext') }}
            </button>
            <button
              type="submit"
              class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
              :disabled="subjects.isLoading"
            >
              {{ t('addSubject.action.saveAndFinish') }}
            </button>
            <button
              type="button"
              class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5 font-medium"
              :disabled="subjects.isLoading"
              @click="submit('schedule')"
            >
              {{ t('addSubject.action.saveAndSchedule') }}
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </button>
          </div>
        </div>
      </form>

      <div class="mt-4 rounded-md bg-muw-blue-50 border border-muw-blue-100 px-4 py-3 text-xs text-muw-blue-900 flex items-start gap-2.5">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="mt-0.5 shrink-0" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <path d="M12 16v-4M12 8h.01" />
        </svg>
        <p class="leading-relaxed">{{ t('addSubject.modesHelp') }}</p>
      </div>
    </main>
  </div>
</template>

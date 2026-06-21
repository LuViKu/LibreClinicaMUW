<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiDelete, apiGet, apiPut, ApiError } from '@/api/client'
import { STUDY_MODULES } from '@/studyModules/registry'
import { useErrorsStore } from '@/stores/errors'

/**
 * 2026-06-21 user-feedback batch — per-study SPA-module enrollment panel.
 *
 * <p>Renders one toggle per known SPA module (sourced from the
 * convention-discovered {@code STUDY_MODULES} registry). Each toggle
 * fires {@code PUT /api/v1/studies/{oid}/modules/{moduleId}} on enroll
 * or {@code DELETE} on un-enroll. The panel reflects the server
 * truth after each round-trip, so a failed write rolls the UI back
 * automatically.
 *
 * <p>The catalog itself lives entirely in the SPA — the backend stores
 * opaque module-id strings. Adding a new module under
 * {@code studyModules/<id>/index.ts} surfaces it here automatically.
 *
 * <p>The host view ({@code StudyParametersEditView}) is already
 * Administrator-gated; the backend endpoint re-enforces via
 * {@code StudyAdminAuthorization.userMayEditStudy}.
 */

interface Props {
  /** Study OID (path-bound). */
  studyOid: string
}
const props = defineProps<Props>()

const { t } = useI18n()
const errors = useErrorsStore()

interface EnrollmentList {
  studyOid: string
  moduleIds: string[]
}

const enrolled = ref<string[]>([])
const loading = ref(false)
const pendingId = ref<string | null>(null)

/**
 * Normalised set of enrolled module ids for membership checks. Mirrors
 * the backend's normalisation (trim + upper) so a manifest's
 * {@code protocolType} compares cleanly.
 */
const enrolledSet = computed<Set<string>>(() => {
  return new Set(enrolled.value.map((m) => m.trim().toUpperCase()))
})

/**
 * Display list — one entry per known module, ordered by manifest
 * registration order so the UI mirrors the bundle order.
 */
const moduleRows = computed(() => {
  return STUDY_MODULES.map((m) => ({
    id: m.protocolType.trim().toUpperCase(),
    label: t(m.labelKey),
    isEnrolled: enrolledSet.value.has(m.protocolType.trim().toUpperCase()),
  }))
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const list = await apiGet<EnrollmentList>(
      `/pages/api/v1/studies/${encodeURIComponent(props.studyOid)}/modules`,
    )
    enrolled.value = list.moduleIds ?? []
  } catch (e) {
    if (e instanceof ApiError) {
      errors.push(e, 'studyModuleEnrollment.load')
    } else {
      errors.push(
        new Error(t('studyModuleEnrollment.loadError')),
        'studyModuleEnrollment.load',
      )
    }
  } finally {
    loading.value = false
  }
}

async function toggle(moduleId: string, enroll: boolean): Promise<void> {
  if (pendingId.value) return
  pendingId.value = moduleId
  try {
    const list = await (enroll
      ? apiPut<EnrollmentList>(
          `/pages/api/v1/studies/${encodeURIComponent(props.studyOid)}/modules/${encodeURIComponent(moduleId)}`,
          {},
        )
      : apiDelete<EnrollmentList>(
          `/pages/api/v1/studies/${encodeURIComponent(props.studyOid)}/modules/${encodeURIComponent(moduleId)}`,
        ))
    enrolled.value = list.moduleIds ?? []
  } catch (e) {
    if (e instanceof ApiError) {
      errors.push(e, 'studyModuleEnrollment.toggle')
    } else {
      errors.push(
        new Error(t('studyModuleEnrollment.toggleError')),
        'studyModuleEnrollment.toggle',
      )
    }
    // Re-sync against server truth so a failed write doesn't leave
    // the UI showing a stale value.
    await load()
  } finally {
    pendingId.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="space-y-3 mb-6" data-testid="study-module-enrollment-panel">
    <h2 class="text-sm font-medium text-slate-700">
      {{ t('studyModuleEnrollment.title') }}
    </h2>
    <p class="text-xs text-slate-500">
      {{ t('studyModuleEnrollment.hint') }}
    </p>

    <div
      v-if="moduleRows.length === 0"
      class="text-xs italic text-slate-400 border border-dashed border-slate-300 rounded-md px-3 py-4"
      data-testid="study-module-enrollment-empty"
    >
      {{ t('studyModuleEnrollment.noModules') }}
    </div>

    <ul v-else class="space-y-2">
      <li
        v-for="row in moduleRows"
        :key="row.id"
        class="flex items-center justify-between gap-3 border border-slate-200 rounded-md px-3 py-2 bg-white"
        :data-testid="`study-module-enrollment-row-${row.id}`"
      >
        <div>
          <div class="text-sm font-medium text-slate-800">{{ row.label }}</div>
          <div class="text-[11px] text-slate-500 mono">{{ row.id }}</div>
        </div>
        <button
          type="button"
          class="text-xs px-3 py-1 rounded border transition-colors disabled:opacity-50"
          :class="row.isEnrolled
            ? 'bg-muw-blue text-white border-muw-blue hover:bg-muw-blue-700'
            : 'bg-white text-slate-700 border-slate-300 hover:bg-slate-100'"
          :disabled="loading || pendingId === row.id"
          :data-testid="`study-module-enrollment-toggle-${row.id}`"
          @click="toggle(row.id, !row.isEnrolled)"
        >
          {{ row.isEnrolled
            ? t('studyModuleEnrollment.enrolled')
            : t('studyModuleEnrollment.notEnrolled') }}
        </button>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
/**
 * 2026-06-30 — Subject group-assignment editor.
 *
 * Generic dialog for editing a subject's {@code subject_group_map}
 * memberships post-recruitment. The SPA never had a UI for this
 * before — {@code AddSubjectView} deliberately stripped its
 * group-class picker; {@code SubjectDetailView} only showed
 * {@code groupLabel} read-only. This wraps the existing
 * {@code PUT /pages/api/v1/subjects/{oid}/groups} endpoint
 * (SubjectGroupAssignmentService).
 *
 * <h2>How it works</h2>
 *
 * <ul>
 *   <li>Loads the study's group_classes via the existing
 *     {@link useGroupClassesStore} on open.</li>
 *   <li>For each class, renders a radio group of (each defined
 *     group) + a special "— nicht zugewiesen —" option for OPTIONAL
 *     classes (REQUIRED classes hide that option to enforce the
 *     class's contract).</li>
 *   <li>Pre-selects the current assignment from
 *     {@link SubjectDetailDto.groupAssignments}.</li>
 *   <li>On save, PUTs the desired state for every class in one batch.
 *     The backend reconciles inserts / soft-deletes / group switches.</li>
 *   <li>Emits {@code saved} on success — parent triggers a refetch
 *     of the subject so the updated assignment surfaces.</li>
 * </ul>
 *
 * <p>If the study has zero group_classes, the dialog shows a
 * "no classes defined" notice + points the operator at the legacy
 * "Subject Group Classes" admin to set one up first.
 */

import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiPut, ApiError, ApiNetworkError } from '@/api/client'
import { useGroupClassesStore } from '@/stores/groupClasses'
import type { GroupAssignmentSnapshot } from '@/types/subject'

const props = defineProps<{
  open: boolean
  /** Site-scoped subject label, used in the PUT URL. */
  studySubjectOid: string
  /** Study OID needed to look up group_classes. */
  studyOid: string
  /** Current assignments from SubjectDetailDto.groupAssignments. */
  currentAssignments: GroupAssignmentSnapshot[]
}>()

const emit = defineEmits<{
  close: []
  saved: []
}>()

const { t } = useI18n()
const groupClassesStore = useGroupClassesStore()

const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
/** Map of groupClassId → chosen groupId (or null = unassigned). */
const picks = ref<Map<number, number | null>>(new Map())

async function loadClasses() {
  loading.value = true
  error.value = null
  try {
    await groupClassesStore.load(props.studyOid)
    const fresh = new Map<number, number | null>()
    for (const cls of groupClassesStore.rows) {
      // Seed from the current assignment, falling through to null
      // when the subject isn't in any group of this class yet.
      const cur = props.currentAssignments.find((a) => a.groupClassId === cls.id)
      fresh.set(cls.id, cur?.groupId ?? null)
    }
    picks.value = fresh
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load group classes'
  } finally {
    loading.value = false
  }
}

onMounted(loadClasses)

const activeClasses = computed(() =>
  groupClassesStore.rows.filter((c) => (c.status ?? 'available').toLowerCase() !== 'deleted'),
)

const canSave = computed(() => {
  if (saving.value || loading.value) return false
  // Every REQUIRED class must have a non-null pick.
  for (const cls of activeClasses.value) {
    if (cls.subjectAssignment === 'REQUIRED' && picks.value.get(cls.id) == null) {
      return false
    }
  }
  return true
})

function setPick(classId: number, groupId: number | null) {
  picks.value.set(classId, groupId)
  // Vue 3 reactivity: Map.set on a ref doesn't trigger; trip it
  // explicitly by re-assigning the ref.
  picks.value = new Map(picks.value)
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  error.value = null
  try {
    const assignments = Array.from(picks.value.entries()).map(([groupClassId, groupId]) => ({
      groupClassId,
      groupId,
    }))
    // Use the shared api/client.ts wrapper so the call routes through
    // the `/LibreClinica` context-path prefix that the Vite dev proxy
    // expects (raw fetch() hit /pages/... directly → 404).
    await apiPut(
      `/pages/api/v1/subjects/${encodeURIComponent(props.studySubjectOid)}/groups`,
      { assignments },
    )
    emit('saved')
    emit('close')
  } catch (e) {
    if (e instanceof ApiError) {
      const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
      const header = body?.message ?? `Save failed (HTTP ${e.status})`
      // Surface the per-field validation messages too — the bare
      // "Validation failed" envelope hides the real reason.
      const fieldMsgs = (body?.errors ?? []).map((fe) => `${fe.field}: ${fe.message}`)
      error.value = fieldMsgs.length > 0
        ? `${header} — ${fieldMsgs.join('; ')}`
        : header
    } else if (e instanceof ApiNetworkError) {
      error.value = 'Backend nicht erreichbar — Speichern fehlgeschlagen.'
    } else {
      error.value = e instanceof Error ? e.message : 'Save failed'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      data-testid="subject-group-edit-dialog"
      class="fixed inset-0 z-50 bg-slate-900/40 backdrop-blur-sm flex items-center justify-center p-4"
      @click.self="emit('close')"
    >
      <div class="bg-white rounded-xl shadow-xl ring-1 ring-slate-200 w-full max-w-lg max-h-[80vh] flex flex-col">
        <header class="flex items-center justify-between px-5 py-4 border-b border-slate-100 shrink-0">
          <h2 class="text-[14px] font-semibold text-slate-900">
            {{ t('subjectGroupEdit.title') }}
          </h2>
          <button
            type="button"
            data-testid="subject-group-edit-close"
            class="text-slate-400 hover:text-slate-700"
            @click="emit('close')"
          >
            <span aria-hidden="true">✕</span>
          </button>
        </header>

        <div class="px-5 py-4 overflow-y-auto">
          <p class="text-[12.5px] text-slate-500 mb-4">
            {{ t('subjectGroupEdit.description') }}
          </p>

          <div v-if="loading" class="text-[13px] text-slate-500 py-2">
            {{ t('subjectGroupEdit.loading') }}
          </div>

          <div
            v-else-if="activeClasses.length === 0"
            data-testid="subject-group-edit-no-classes"
            class="rounded-md bg-amber-50 text-amber-800 px-3 py-2 text-[12.5px]"
          >
            {{ t('subjectGroupEdit.noClasses') }}
          </div>

          <div v-else class="space-y-5">
            <fieldset
              v-for="cls in activeClasses"
              :key="cls.id"
              :data-testid="`subject-group-edit-class-${cls.id}`"
              class="border border-slate-200 rounded-md p-3"
            >
              <legend class="px-1.5 text-[12px] font-semibold text-slate-700">
                {{ cls.name }}
                <span
                  v-if="cls.subjectAssignment === 'REQUIRED'"
                  class="ml-1 text-rose-500"
                  :title="t('subjectGroupEdit.requiredHint')"
                >*</span>
              </legend>
              <div class="space-y-1.5 mt-1">
                <label
                  v-for="g in cls.groups"
                  :key="g.id"
                  class="flex items-start gap-2 text-[13px] text-slate-700 cursor-pointer"
                  :data-testid="`subject-group-edit-choice-${cls.id}-${g.id}`"
                >
                  <input
                    type="radio"
                    :checked="picks.get(cls.id) === g.id"
                    :name="`group-class-${cls.id}`"
                    class="mt-0.5"
                    @change="setPick(cls.id, g.id)"
                  />
                  <span>{{ g.name }}</span>
                </label>
                <label
                  v-if="cls.subjectAssignment !== 'REQUIRED'"
                  class="flex items-start gap-2 text-[13px] text-slate-500 italic cursor-pointer"
                  :data-testid="`subject-group-edit-choice-${cls.id}-NONE`"
                >
                  <input
                    type="radio"
                    :checked="picks.get(cls.id) == null"
                    :name="`group-class-${cls.id}`"
                    class="mt-0.5"
                    @change="setPick(cls.id, null)"
                  />
                  <span>{{ t('subjectGroupEdit.unassigned') }}</span>
                </label>
              </div>
            </fieldset>
          </div>

          <div
            v-if="error"
            data-testid="subject-group-edit-error"
            class="mt-3 rounded-md bg-rose-50 text-rose-700 px-3 py-2 text-[12px]"
          >{{ error }}</div>
        </div>

        <footer class="px-5 py-3 border-t border-slate-100 flex items-center gap-2 justify-end shrink-0">
          <button
            type="button"
            data-testid="subject-group-edit-cancel"
            class="px-3 py-1.5 rounded-md text-[13px] text-slate-600 hover:bg-slate-100"
            @click="emit('close')"
          >
            {{ t('subjectGroupEdit.cancel') }}
          </button>
          <button
            type="button"
            data-testid="subject-group-edit-save"
            :disabled="!canSave"
            class="px-3 py-1.5 rounded-md text-[13px] font-semibold text-white bg-muw-blue hover:bg-muw-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
            @click="save"
          >
            {{ saving
              ? t('subjectGroupEdit.saving')
              : t('subjectGroupEdit.save') }}
          </button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

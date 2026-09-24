<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import BuildStudyRail from '@/components/BuildStudyRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import EventCrfAssignmentsDialog from '@/components/EventCrfAssignmentsDialog.vue'

import { useEventDefinitionsStore } from '@/stores/eventDefinitions'
import { useAuthStore } from '@/stores/auth'
import { useImagingModalitiesStore } from '@/stores/imagingModalities'
import { useStudyModuleStore } from '@/stores/studyModules'
import { useConfirm } from '@/composables/useConfirm'
import type {
  EventDefinition,
  EventType,
  ImagingLaterality,
  ImagingPlanCatchUp,
  ImagingRequirement,
} from '@/types/eventDefinition'
import {
  RETINAL_TASK_OPTIONS,
  buildPlanRows,
  requiredTasksOf,
  toWriteEntries,
  toggleTask,
  type PlanRow,
} from '@/lib/imagingPlan'

/**
 * Phase E A8.2 — event-definition CRUD view.
 *
 * Lists the active study's event definitions and lets the operator
 * create new ones, edit existing ones, reorder via up/down arrows,
 * and disable. CRF assignments come from A8.3 — a per-row "Manage
 * CRFs" link will appear once that ships.
 *
 * Role-gated client-side to Administrator + Data Manager; backend
 * re-checks authoritatively against the sysadmin / director /
 * coordinator triad.
 */
const { t, locale } = useI18n()
const eventDefs = useEventDefinitionsStore()
const auth = useAuthStore()
const modalities = useImagingModalitiesStore()
const studyModules = useStudyModuleStore()
const confirm = useConfirm()

const studyOid = computed(() => auth.user?.activeStudy?.oid ?? null)

/*
 * DR-034 — a study with an imaging catalogue plans its visits per modality
 * (required or optional, which eye, which inference tasks); one without keeps
 * the older per-visit task chips, which the backend still honours.
 */
const hasCatalogue = computed(() => modalities.list.length > 0)
/** What an enrolled module insists on — shown pressed and not switchable. */
const requiredTasks = computed(() => requiredTasksOf(studyModules.activeModules))
function modalityLabel(row: { labelDe: string; labelEn: string }): string {
  return String(locale.value).toLowerCase().startsWith('de') ? row.labelDe : row.labelEn
}
const REQUIREMENT_OPTIONS: readonly ImagingRequirement[] = ['optional', 'required'] as const
const LATERALITY_OPTIONS: ReadonlyArray<ImagingLaterality | ''> = ['', 'OU', 'OD', 'OS'] as const
const canManage = computed(() => {
  const role = auth.user?.role
  return role === 'Administrator' || role === 'Data Manager'
})
// Phase E.6 — lock + unlock are sysadmin-only on the backend (matches
// the legacy UnlockEventDefinitionServlet mayProceed guard). Gating the
// buttons client-side avoids surfacing them to roles that would only
// see a 403 on click.
const canLifecycle = computed(() => auth.user?.role === 'Administrator')

onMounted(() => {
  if (studyOid.value) {
    eventDefs.load(studyOid.value)
    modalities.load(studyOid.value)
  }
})

watch(studyOid, (next) => {
  if (next) {
    eventDefs.load(next)
    modalities.load(next)
  }
})

interface CreateForm {
  name: string
  type: EventType
  description: string
  category: string
  repeating: boolean
}
const createOpen = ref(false)
const createForm = ref<CreateForm>({
  name: '', type: 'scheduled', description: '', category: '', repeating: false,
})
const createErrors = ref<Record<string, string>>({})
const createFormError = ref<string | null>(null)
const isCreating = ref(false)

function openCreate() {
  createForm.value = { name: '', type: 'scheduled', description: '', category: '', repeating: false }
  createErrors.value = {}
  createFormError.value = null
  createOpen.value = true
}

async function submitCreate() {
  if (!studyOid.value || createForm.value.name.trim() === '') return
  createErrors.value = {}
  createFormError.value = null
  isCreating.value = true
  try {
    const result = await eventDefs.create(studyOid.value, {
      name: createForm.value.name.trim(),
      type: createForm.value.type,
      description: createForm.value.description.trim() || undefined,
      category: createForm.value.category.trim() || undefined,
      repeating: createForm.value.repeating,
    })
    if (result.ok) {
      createOpen.value = false
    } else {
      createErrors.value = result.fieldErrors
      createFormError.value = result.message ?? null
    }
  } finally {
    isCreating.value = false
  }
}

interface EditState {
  oid: string
  name: string
  description: string
  category: string
  type: EventType
  repeating: boolean
  /** 2026-06-22 — the int PK; needed to address the retinal-tasks sub-resource. */
  sedId: number
  /** 2026-06-22 — selected retinal-inference task panel (studies without an imaging catalogue). */
  retinalTasks: string[]
  /** DR-034 — one row per catalogue modality; `included` rows are the plan. */
  imagingPlan: PlanRow[]
}
const editing = ref<EditState | null>(null)
const editErrors = ref<Record<string, string>>({})
const editFormError = ref<string | null>(null)
const isSavingEdit = ref(false)

async function openEdit(row: EventDefinition) {
  editing.value = {
    oid: row.oid,
    sedId: row.sedId,
    name: row.name,
    description: row.description,
    category: row.category,
    type: (row.type as EventType) || 'scheduled',
    repeating: row.repeating,
    retinalTasks: [],
    imagingPlan: [],
  }
  editErrors.value = {}
  editFormError.value = null
  if (!studyOid.value || !row.sedId) return
  // Both are lazy: the task list for a study without a catalogue, the plan
  // for one with. Loading both costs one small call and spares a race with
  // the catalogue still loading when the form opens.
  const [tasks, entries] = await Promise.all([
    eventDefs.loadRetinalTasks(studyOid.value, row.sedId),
    eventDefs.loadImagingPlan(studyOid.value, row.sedId),
  ])
  if (editing.value && editing.value.sedId === row.sedId) {
    editing.value.retinalTasks = tasks
    editing.value.imagingPlan = buildPlanRows(modalities.list, entries)
  }
}

function togglePlanTask(row: PlanRow, task: string): void {
  toggleTask(row, task, requiredTasks.value)
}

/*
 * DR-035 — the plan applies to scans filed from now on. The editor shows
 * what applying it to the already-filed scans would start, and does so only
 * on a click: a plan edit could fan out hundreds of GPU jobs, and the number
 * is the administrator's to see first. The preview reflects the SAVED plan,
 * so it is refreshed after a save.
 */
const catchUpPreview = ref<ImagingPlanCatchUp | null>(null)
const catchUpResult = ref<ImagingPlanCatchUp | null>(null)
const catchUpLoading = ref(false)
const catchUpRunning = ref(false)
const catchUpError = ref(false)

async function loadCatchUpPreview(sedId: number): Promise<void> {
  if (!studyOid.value || !hasCatalogue.value) return
  catchUpLoading.value = true
  catchUpError.value = false
  try {
    const p = await eventDefs.previewImagingPlanCatchUp(studyOid.value, sedId)
    if (editing.value && editing.value.sedId === sedId) catchUpPreview.value = p
  } finally {
    catchUpLoading.value = false
  }
}

async function runCatchUp(): Promise<void> {
  if (!editing.value || !studyOid.value) return
  const sedId = editing.value.sedId
  catchUpRunning.value = true
  catchUpError.value = false
  catchUpResult.value = null
  try {
    const r = await eventDefs.runImagingPlanCatchUp(studyOid.value, sedId)
    if (!r) {
      catchUpError.value = true
      return
    }
    catchUpResult.value = r
    await loadCatchUpPreview(sedId)
  } finally {
    catchUpRunning.value = false
  }
}

watch(editing, (next, prev) => {
  if (!next || next.sedId !== prev?.sedId) {
    catchUpPreview.value = null
    catchUpResult.value = null
    catchUpError.value = false
  }
  if (next && next.sedId !== prev?.sedId) void loadCatchUpPreview(next.sedId)
})

function toggleRetinalTask(task: string): void {
  if (!editing.value) return
  const current = editing.value.retinalTasks
  editing.value.retinalTasks = current.includes(task)
    ? current.filter((t) => t !== task)
    : [...current, task]
}

async function submitEdit() {
  if (!editing.value || !studyOid.value) return
  editErrors.value = {}
  editFormError.value = null
  isSavingEdit.value = true
  try {
    const result = await eventDefs.update(studyOid.value, editing.value.oid, {
      name: editing.value.name.trim(),
      description: editing.value.description.trim(),
      category: editing.value.category.trim(),
      type: editing.value.type,
      repeating: editing.value.repeating,
    })
    if (!result.ok) {
      editErrors.value = result.fieldErrors
      editFormError.value = result.message ?? null
      return
    }
    // Persist the imaging plan (or, without a catalogue, the task panel)
    // alongside the core fields. Failure here doesn't roll back the
    // event_def update — the core write already landed; surface the
    // secondary error inline instead.
    if (editing.value.sedId) {
      const ok = hasCatalogue.value
        ? await eventDefs.saveImagingPlan(
          studyOid.value, editing.value.sedId,
          toWriteEntries(editing.value.imagingPlan, requiredTasks.value),
        )
        : await eventDefs.saveRetinalTasks(
          studyOid.value, editing.value.sedId, editing.value.retinalTasks,
        )
      if (!ok) {
        editFormError.value = hasCatalogue.value
          ? t('eventDefinitions.imagingPlan.saveError')
          : t('eventDefinitions.retinalTasks.saveError')
        return
      }
      // DR-035 — the saved plan may now owe work to scans already filed.
      // Stay in the form with the refreshed number so the administrator can
      // apply it, rather than closing and hiding the question.
      if (hasCatalogue.value) {
        catchUpResult.value = null
        await loadCatchUpPreview(editing.value.sedId)
        if (catchUpPreview.value && catchUpPreview.value.scans > 0 && catchUpPreview.value.started > 0) {
          return
        }
      }
    }
    editing.value = null
  } finally {
    isSavingEdit.value = false
  }
}

async function onDisable(row: EventDefinition) {
  if (!studyOid.value) return
  if (!(await confirm({ message: t('eventDefinitions.disableConfirm', { name: row.name }), danger: true }))) return
  await eventDefs.disable(studyOid.value, row.oid)
}

// Phase E.6 — restore / lock / unlock. Each prompts before firing so
// the operator can't trigger a destructive cascade with a misclick.
const showRemoved = ref(false)
async function onRestore(row: EventDefinition) {
  if (!studyOid.value) return
  if (!(await confirm({ message: t('eventDefinitions.restoreConfirm', { name: row.name }), danger: false }))) return
  await eventDefs.restore(studyOid.value, row.oid)
}
async function onLock(row: EventDefinition) {
  if (!studyOid.value) return
  if (!(await confirm({ message: t('eventDefinitions.lockConfirm', { name: row.name }), danger: true }))) return
  await eventDefs.lock(studyOid.value, row.oid)
}
async function onUnlock(row: EventDefinition) {
  if (!studyOid.value) return
  if (!(await confirm({ message: t('eventDefinitions.unlockConfirm', { name: row.name }), danger: false }))) return
  await eventDefs.unlock(studyOid.value, row.oid)
}

const activeRows = computed(() => eventDefs.rows.filter((r) => r.status !== 'removed'))
const removedRows = computed(() => eventDefs.rows.filter((r) => r.status === 'removed'))

async function moveUp(idx: number) {
  if (!studyOid.value || idx <= 0) return
  const oids = activeRows.value.map((r) => r.oid)
  const swap = oids[idx - 1]
  oids[idx - 1] = oids[idx]
  oids[idx] = swap
  await eventDefs.reorder(studyOid.value, oids)
}

async function moveDown(idx: number) {
  if (!studyOid.value || idx >= activeRows.value.length - 1) return
  const oids = activeRows.value.map((r) => r.oid)
  const swap = oids[idx + 1]
  oids[idx + 1] = oids[idx]
  oids[idx] = swap
  await eventDefs.reorder(studyOid.value, oids)
}

const typeOptions: { v: EventType; l: () => string }[] = [
  { v: 'scheduled',   l: () => t('eventDefinitions.type.scheduled') },
  { v: 'unscheduled', l: () => t('eventDefinitions.type.unscheduled') },
  { v: 'common',      l: () => t('eventDefinitions.type.common') },
]

/* Phase E A8.3-asgnUI — CRF assignments dialog state. */
const assignDialogOpen = ref(false)
const assignTarget = ref<EventDefinition | null>(null)
function openAssignments(row: EventDefinition) {
  assignTarget.value = row
  assignDialogOpen.value = true
}
</script>

<template>
  <div class="flex">
    <BuildStudyRail />

    <div class="flex-1 max-w-4xl px-8 py-6">
      <div class="mb-4 flex items-end justify-between gap-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('eventDefinitions.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('eventDefinitions.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('eventDefinitions.intro') }}</p>
        </div>
        <button
          v-if="canManage"
          class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
          @click="openCreate"
        >
          {{ t('eventDefinitions.createAction') }}
        </button>
      </div>

      <p v-if="eventDefs.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="eventDefs.error" class="text-rose-700">{{ eventDefs.error }}</p>
      <p v-else-if="activeRows.length === 0" class="text-slate-500 italic">
        {{ t('eventDefinitions.empty') }}
      </p>

      <table v-else class="w-full text-sm border-collapse">
        <thead>
          <tr class="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
            <th class="px-3 py-2 w-10">#</th>
            <th class="px-3 py-2">{{ t('eventDefinitions.column.name') }}</th>
            <th class="px-3 py-2">{{ t('eventDefinitions.column.type') }}</th>
            <th class="px-3 py-2">{{ t('eventDefinitions.column.repeating') }}</th>
            <th class="px-3 py-2 text-right">{{ t('eventDefinitions.column.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(row, idx) in activeRows"
            :key="row.oid"
            class="border-b border-slate-100 hover:bg-slate-50"
          >
            <td class="px-3 py-2 font-mono text-xs text-slate-500">{{ row.ordinal }}</td>
            <td class="px-3 py-2">
              <div class="font-medium text-slate-800">{{ row.name }}</div>
              <div v-if="row.description" class="text-xs text-slate-500 mt-0.5">{{ row.description }}</div>
            </td>
            <td class="px-3 py-2">
              <StatusPill variant="neutral">{{ t(`eventDefinitions.type.${row.type}`) }}</StatusPill>
            </td>
            <td class="px-3 py-2 text-slate-600">
              {{ row.repeating ? t('common.next') : '—' }}
            </td>
            <td class="px-3 py-2 text-right text-xs">
              <div v-if="canManage" class="inline-flex items-center gap-2">
                <button
                  class="inline-flex items-center justify-center min-w-[24px] min-h-[24px] text-slate-500 hover:text-muw-blue disabled:opacity-30"
                  :disabled="idx === 0"
                  @click="moveUp(idx)"
                  :aria-label="t('eventDefinitions.moveUp')"
                >↑</button>
                <button
                  class="inline-flex items-center justify-center min-w-[24px] min-h-[24px] text-slate-500 hover:text-muw-blue disabled:opacity-30"
                  :disabled="idx === activeRows.length - 1"
                  @click="moveDown(idx)"
                  :aria-label="t('eventDefinitions.moveDown')"
                >↓</button>
                <span class="text-slate-500">·</span>
                <button class="text-muw-blue hover:underline" @click="openEdit(row)">
                  {{ t('common.next') === 'Next' ? 'Edit' : 'Bearbeiten' }}
                </button>
                <span class="text-slate-500">·</span>
                <button class="text-muw-blue hover:underline" @click="openAssignments(row)">
                  {{ t('eventDefinitions.manageCrfs') }}
                </button>
                <span class="text-slate-500">·</span>
                <button class="text-rose-600 hover:underline" @click="onDisable(row)">
                  {{ t('eventDefinitions.disable') }}
                </button>
                <template v-if="canLifecycle">
                  <span class="text-slate-500">·</span>
                  <button
                    v-if="row.status !== 'locked'"
                    class="text-amber-700 hover:underline"
                    @click="onLock(row)"
                  >{{ t('eventDefinitions.lock') }}</button>
                  <button
                    v-else
                    class="text-emerald-700 hover:underline"
                    @click="onUnlock(row)"
                  >{{ t('eventDefinitions.unlock') }}</button>
                </template>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- Phase E.6 — removed event definitions, surfaced behind a toggle so
           the main list stays focused on the operator's active workflow. -->
      <div v-if="canManage" class="mt-6">
        <button
          class="text-xs text-slate-500 hover:text-muw-blue underline"
          @click="showRemoved = !showRemoved"
        >
          {{ showRemoved ? t('eventDefinitions.hideRemoved') : t('eventDefinitions.showRemoved') }}
          ({{ removedRows.length }})
        </button>
        <table
          v-if="showRemoved && removedRows.length > 0"
          class="mt-3 w-full text-sm border-collapse opacity-70"
        >
          <thead>
            <tr class="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
              <th class="px-3 py-2">{{ t('eventDefinitions.column.name') }}</th>
              <th class="px-3 py-2">{{ t('eventDefinitions.column.type') }}</th>
              <th class="px-3 py-2 text-right">{{ t('eventDefinitions.column.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in removedRows" :key="row.oid" class="border-b border-slate-100">
              <td class="px-3 py-2 text-slate-700">
                <span class="line-through">{{ row.name }}</span>
              </td>
              <td class="px-3 py-2">
                <StatusPill variant="neutral">{{ t(`eventDefinitions.type.${row.type}`) }}</StatusPill>
              </td>
              <td class="px-3 py-2 text-right text-xs">
                <button class="text-emerald-700 hover:underline" @click="onRestore(row)">
                  {{ t('eventDefinitions.restore') }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Inline create form -->
      <div v-if="createOpen" class="mt-6 rounded-md border border-slate-200 bg-white p-4">
        <h2 class="text-sm font-semibold mb-3">{{ t('eventDefinitions.createHeading') }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="ed-name" required>{{ t('eventDefinitions.column.name') }}</FieldLabel>
            <TextInput id="ed-name" v-model="createForm.name" />
            <ErrorText v-if="createErrors.name">{{ createErrors.name }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="ed-type" required>{{ t('eventDefinitions.column.type') }}</FieldLabel>
            <SelectInput id="ed-type" v-model="createForm.type">
              <option v-for="opt in typeOptions" :key="opt.v" :value="opt.v">{{ opt.l() }}</option>
            </SelectInput>
            <ErrorText v-if="createErrors.type">{{ createErrors.type }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="ed-category">{{ t('eventDefinitions.category') }}</FieldLabel>
            <TextInput id="ed-category" v-model="createForm.category" />
          </div>
          <div class="col-span-2">
            <FieldLabel for="ed-desc">{{ t('eventDefinitions.description') }}</FieldLabel>
            <TextInput id="ed-desc" v-model="createForm.description" />
          </div>
          <div class="col-span-2 flex items-center gap-2">
            <input id="ed-repeating" v-model="createForm.repeating" type="checkbox" class="rounded" />
            <label for="ed-repeating" class="text-xs text-slate-700">{{ t('eventDefinitions.repeatingLabel') }}</label>
          </div>
        </div>
        <ErrorText v-if="createFormError">{{ createFormError }}</ErrorText>
        <div class="mt-3 flex items-center gap-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="createOpen = false"
          >{{ t('common.cancel') }}</button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="createForm.name.trim() === '' || isCreating"
            @click="submitCreate"
          >{{ isCreating ? t('common.saving') : t('eventDefinitions.submitCreate') }}</button>
        </div>
      </div>

      <!-- Inline edit form -->
      <div v-if="editing" class="mt-6 rounded-md border border-amber-200 bg-amber-50 p-4">
        <h2 class="text-sm font-semibold mb-3">
          {{ t('eventDefinitions.editHeading') }} <span class="font-mono text-xs text-slate-500">{{ editing.oid }}</span>
        </h2>
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="ed-edit-name" required>{{ t('eventDefinitions.column.name') }}</FieldLabel>
            <TextInput id="ed-edit-name" v-model="editing.name" />
            <ErrorText v-if="editErrors.name">{{ editErrors.name }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="ed-edit-type" required>{{ t('eventDefinitions.column.type') }}</FieldLabel>
            <SelectInput id="ed-edit-type" v-model="editing.type">
              <option v-for="opt in typeOptions" :key="opt.v" :value="opt.v">{{ opt.l() }}</option>
            </SelectInput>
            <ErrorText v-if="editErrors.type">{{ editErrors.type }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="ed-edit-category">{{ t('eventDefinitions.category') }}</FieldLabel>
            <TextInput id="ed-edit-category" v-model="editing.category" />
          </div>
          <div class="col-span-2">
            <FieldLabel for="ed-edit-desc">{{ t('eventDefinitions.description') }}</FieldLabel>
            <TextInput id="ed-edit-desc" v-model="editing.description" />
          </div>
          <div class="col-span-2 flex items-center gap-2">
            <input id="ed-edit-repeating" v-model="editing.repeating" type="checkbox" class="rounded" />
            <label for="ed-edit-repeating" class="text-xs text-slate-700">{{ t('eventDefinitions.repeatingLabel') }}</label>
          </div>

          <!-- DR-034 — the visit imaging plan: one row per catalogue
               modality. An included row says the visit expects that
               modality (required rows block signing while missing),
               for which eye(s), and — on an OCT-volume modality — which
               inference tasks a filed scan is fanned out to. -->
          <div v-if="hasCatalogue" class="col-span-2" data-testid="ed-edit-imaging-plan">
            <FieldLabel for="ed-edit-imaging-plan">{{ t('eventDefinitions.imagingPlan.label') }}</FieldLabel>
            <table id="ed-edit-imaging-plan" class="mt-1 w-full text-xs">
              <thead>
                <tr class="text-left text-slate-500">
                  <th scope="col" class="py-1 pr-2 font-medium">{{ t('eventDefinitions.imagingPlan.expected') }}</th>
                  <th scope="col" class="py-1 pr-2 font-medium">{{ t('eventDefinitions.imagingPlan.requirement') }}</th>
                  <th scope="col" class="py-1 pr-2 font-medium">{{ t('eventDefinitions.imagingPlan.laterality') }}</th>
                  <th scope="col" class="py-1 font-medium">{{ t('eventDefinitions.imagingPlan.tasks') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in editing.imagingPlan"
                  :key="`plan-${row.modalityId}`"
                  class="border-t border-amber-100 align-top"
                  :data-testid="`ed-edit-plan-${row.code}`"
                >
                  <td class="py-1.5 pr-2">
                    <label class="inline-flex items-center gap-2">
                      <input
                        v-model="row.included"
                        type="checkbox"
                        class="rounded"
                        :data-testid="`ed-edit-plan-${row.code}-included`"
                      />
                      <span class="text-slate-800">{{ modalityLabel(row) }}</span>
                      <span class="font-mono text-[10px] text-slate-400">{{ row.code }}</span>
                    </label>
                  </td>
                  <td class="py-1.5 pr-2">
                    <select
                      v-model="row.requirement"
                      class="px-2 py-1 rounded ring-1 ring-slate-200 bg-white disabled:opacity-50"
                      :disabled="!row.included"
                      :data-testid="`ed-edit-plan-${row.code}-requirement`"
                    >
                      <option v-for="r in REQUIREMENT_OPTIONS" :key="r" :value="r">
                        {{ t(`eventDefinitions.imagingPlan.requirementOption.${r}`) }}
                      </option>
                    </select>
                  </td>
                  <td class="py-1.5 pr-2">
                    <select
                      v-model="row.laterality"
                      class="px-2 py-1 rounded ring-1 ring-slate-200 bg-white disabled:opacity-50"
                      :disabled="!row.included"
                      :data-testid="`ed-edit-plan-${row.code}-laterality`"
                    >
                      <option v-for="l in LATERALITY_OPTIONS" :key="l || 'any'" :value="l">
                        {{ t(`eventDefinitions.imagingPlan.lateralityOption.${l || 'any'}`) }}
                      </option>
                    </select>
                  </td>
                  <td class="py-1.5">
                    <div v-if="row.acceptsE2e" class="flex flex-wrap gap-1.5">
                      <button
                        v-for="task in RETINAL_TASK_OPTIONS"
                        :key="`${row.modalityId}-${task}`"
                        type="button"
                        :data-testid="`ed-edit-plan-${row.code}-task-${task}`"
                        :aria-pressed="row.tasks.includes(task) || requiredTasks.includes(task)"
                        :disabled="!row.included || requiredTasks.includes(task)"
                        :title="requiredTasks.includes(task) ? t('eventDefinitions.imagingPlan.requiredByModule') : undefined"
                        class="inline-flex items-center gap-1 rounded-full text-[11px] font-semibold px-2.5 py-0.5 border transition disabled:cursor-not-allowed"
                        :class="row.tasks.includes(task) || requiredTasks.includes(task)
                          ? 'bg-muw-sky-50 border-muw-sky-300 text-muw-sky-700'
                          : 'bg-white border-slate-200 text-slate-500 hover:bg-slate-50 disabled:opacity-50'"
                        @click="togglePlanTask(row, task)"
                      >
                        <span class="font-mono text-[10px] uppercase">{{ task }}</span>
                        <span class="text-slate-400">·</span>
                        <span>{{ t(`retinal.task.${task}`) }}</span>
                      </button>
                    </div>
                    <span v-else class="text-slate-400">{{ t('eventDefinitions.imagingPlan.noInference') }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p class="mt-1.5 text-[11px] text-slate-500">
              {{ t('eventDefinitions.imagingPlan.hint') }}
            </p>
            <p v-if="requiredTasks.length" class="mt-1 text-[11px] text-slate-500">
              {{ t('eventDefinitions.imagingPlan.requiredByModuleHint', { tasks: requiredTasks.join(', ') }) }}
            </p>

            <!-- DR-035 — the plan applies to scans filed from now on; this
                 catches up what is already filed, after showing the number. -->
            <div class="mt-3 rounded border border-amber-200 bg-white/60 p-2.5 text-[11px]" data-testid="ed-edit-plan-catch-up">
              <p v-if="catchUpLoading" class="text-slate-500">{{ t('eventDefinitions.imagingPlan.catchUp.loading') }}</p>
              <template v-else-if="catchUpPreview">
                <p v-if="catchUpPreview.scans === 0" class="text-slate-500">
                  {{ t('eventDefinitions.imagingPlan.catchUp.none') }}
                </p>
                <p v-else class="text-slate-700" data-testid="ed-edit-plan-catch-up-summary">
                  {{ t('eventDefinitions.imagingPlan.catchUp.summary', { scans: catchUpPreview.scans, n: catchUpPreview.started }) }}
                </p>
              </template>
              <p class="mt-1 text-slate-500">{{ t('eventDefinitions.imagingPlan.catchUp.hint') }}</p>
              <div class="mt-2 flex items-center gap-2">
                <button
                  type="button"
                  class="px-3 py-1 text-[11px] border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700 disabled:opacity-50"
                  :disabled="catchUpRunning || !catchUpPreview || catchUpPreview.scans === 0"
                  data-testid="ed-edit-plan-catch-up-apply"
                  @click="runCatchUp"
                >{{ catchUpRunning ? t('eventDefinitions.imagingPlan.catchUp.applying') : t('eventDefinitions.imagingPlan.catchUp.apply') }}</button>
                <span v-if="catchUpResult" class="text-muw-teal-700" data-testid="ed-edit-plan-catch-up-done">
                  {{ t('eventDefinitions.imagingPlan.catchUp.done', {
                    attached: catchUpResult.attached,
                    started: catchUpResult.started,
                    failedPart: catchUpResult.failed > 0
                      ? t('eventDefinitions.imagingPlan.catchUp.failedPart', { failed: catchUpResult.failed })
                      : '',
                  }) }}
                </span>
                <span v-if="catchUpError" class="text-rose-700">{{ t('eventDefinitions.imagingPlan.catchUp.error') }}</span>
              </div>
            </div>
          </div>

          <!-- 2026-06-22 — retinal-inference task panel, for a study
               without an imaging catalogue. When an OCT scan is committed
               against this visit, one inference job is enqueued per
               selected task. An empty selection falls back to "fluid" on
               commit so today's behaviour is preserved for visits the
               admin hasn't configured. -->
          <div v-else class="col-span-2">
            <FieldLabel for="ed-edit-retinal-tasks">{{ t('eventDefinitions.retinalTasks.label') }}</FieldLabel>
            <div
              id="ed-edit-retinal-tasks"
              class="mt-1 flex flex-wrap gap-2"
              data-testid="ed-edit-retinal-tasks"
            >
              <button
                v-for="task in RETINAL_TASK_OPTIONS"
                :key="`rt-${task}`"
                type="button"
                :data-testid="`ed-edit-retinal-task-${task}`"
                :aria-pressed="editing.retinalTasks.includes(task)"
                class="inline-flex items-center gap-1.5 rounded-full text-[11px] font-semibold px-3 py-1 border transition"
                :class="editing.retinalTasks.includes(task)
                  ? 'bg-muw-sky-50 border-muw-sky-300 text-muw-sky-700'
                  : 'bg-white border-slate-200 text-slate-500 hover:bg-slate-50'"
                @click="toggleRetinalTask(task)"
              >
                <span class="font-mono text-[10px] uppercase">{{ task }}</span>
                <span class="text-slate-400">·</span>
                <span>{{ t(`retinal.task.${task}`) }}</span>
              </button>
            </div>
            <p class="mt-1.5 text-[11px] text-slate-500">
              {{ t('eventDefinitions.retinalTasks.hint') }}
            </p>
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
            :disabled="!editing || editing.name.trim() === '' || isSavingEdit"
            @click="submitEdit"
          >{{ isSavingEdit ? t('common.saving') : t('eventDefinitions.submitEdit') }}</button>
        </div>
      </div>
    </div>

    <EventCrfAssignmentsDialog
      v-model:open="assignDialogOpen"
      :study-oid="studyOid"
      :event-def="assignTarget"
    />
  </div>
</template>

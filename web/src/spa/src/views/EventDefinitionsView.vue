<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useEventDefinitionsStore } from '@/stores/eventDefinitions'
import { useAuthStore } from '@/stores/auth'
import type { EventDefinition, EventType } from '@/types/eventDefinition'

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
const { t } = useI18n()
const eventDefs = useEventDefinitionsStore()
const auth = useAuthStore()

const studyOid = computed(() => auth.user?.activeStudy?.oid ?? null)
const canManage = computed(() => {
  const role = auth.user?.role
  return role === 'Administrator' || role === 'Data Manager'
})

onMounted(() => { if (studyOid.value) eventDefs.load(studyOid.value) })

watch(studyOid, (next) => {
  if (next) eventDefs.load(next)
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
}
const editing = ref<EditState | null>(null)
const editErrors = ref<Record<string, string>>({})
const editFormError = ref<string | null>(null)
const isSavingEdit = ref(false)

function openEdit(row: EventDefinition) {
  editing.value = {
    oid: row.oid,
    name: row.name,
    description: row.description,
    category: row.category,
    type: (row.type as EventType) || 'scheduled',
    repeating: row.repeating,
  }
  editErrors.value = {}
  editFormError.value = null
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
    if (result.ok) {
      editing.value = null
    } else {
      editErrors.value = result.fieldErrors
      editFormError.value = result.message ?? null
    }
  } finally {
    isSavingEdit.value = false
  }
}

async function onDisable(row: EventDefinition) {
  if (!studyOid.value) return
  if (!confirm(t('eventDefinitions.disableConfirm', { name: row.name }))) return
  await eventDefs.disable(studyOid.value, row.oid)
}

const activeRows = computed(() => eventDefs.rows.filter((r) => r.status !== 'removed'))

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
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.buildStudy') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-4xl px-8 py-6">
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
                  class="text-slate-500 hover:text-muw-blue disabled:opacity-30"
                  :disabled="idx === 0"
                  @click="moveUp(idx)"
                  :aria-label="t('eventDefinitions.moveUp')"
                >↑</button>
                <button
                  class="text-slate-500 hover:text-muw-blue disabled:opacity-30"
                  :disabled="idx === activeRows.length - 1"
                  @click="moveDown(idx)"
                  :aria-label="t('eventDefinitions.moveDown')"
                >↓</button>
                <span class="text-slate-300">·</span>
                <button class="text-muw-blue hover:underline" @click="openEdit(row)">
                  {{ t('common.next') === 'Next' ? 'Edit' : 'Bearbeiten' }}
                </button>
                <span class="text-slate-300">·</span>
                <button class="text-rose-600 hover:underline" @click="onDisable(row)">
                  {{ t('eventDefinitions.disable') }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

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
    </main>
  </div>
</template>

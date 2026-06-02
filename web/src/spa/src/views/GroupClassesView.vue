<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useGroupClassesStore } from '@/stores/groupClasses'
import { useAuthStore } from '@/stores/auth'
import type { GroupClass, GroupClassType, SubjectAssignment } from '@/types/groupClass'

/**
 * Phase E A8.6 — subject group classes view.
 *
 * Manages the "groups" task tile from the build-study dashboard.
 * Each row is a `study_group_class` (Arm / Family / Demographic /
 * Other) with its child groups inlined.
 */
const { t } = useI18n()
const gc = useGroupClassesStore()
const auth = useAuthStore()

const studyOid = computed(() => auth.user?.activeStudy?.oid ?? null)
const canManage = computed(() => {
  const role = auth.user?.role
  return role === 'Administrator' || role === 'Data Manager'
})

onMounted(() => { if (studyOid.value) gc.load(studyOid.value) })
watch(studyOid, (next) => { if (next) gc.load(next) })

/* ----------------------------- Create ----------------------------- */

interface CreateGroupForm { name: string; description: string }
interface CreateForm {
  name: string
  groupClassType: GroupClassType
  subjectAssignment: SubjectAssignment
  groups: CreateGroupForm[]
}

const createOpen = ref(false)
const createForm = ref<CreateForm>(blankForm())
const createErrors = ref<Record<string, string>>({})
const createFormError = ref<string | null>(null)
const isCreating = ref(false)

function blankForm(): CreateForm {
  return {
    name: '',
    groupClassType: 'Arm',
    subjectAssignment: 'REQUIRED',
    groups: [{ name: '', description: '' }, { name: '', description: '' }],
  }
}

function openCreate() {
  createForm.value = blankForm()
  createErrors.value = {}
  createFormError.value = null
  createOpen.value = true
}

function addGroupRow() {
  createForm.value.groups.push({ name: '', description: '' })
}

function removeGroupRow(idx: number) {
  createForm.value.groups.splice(idx, 1)
}

const canSubmitCreate = computed(() => {
  if (createForm.value.name.trim() === '') return false
  const populated = createForm.value.groups.filter((g) => g.name.trim() !== '')
  return populated.length > 0
})

async function submitCreate() {
  if (!studyOid.value || !canSubmitCreate.value) return
  createErrors.value = {}
  createFormError.value = null
  isCreating.value = true
  try {
    const result = await gc.create(studyOid.value, {
      name: createForm.value.name.trim(),
      groupClassType: createForm.value.groupClassType,
      subjectAssignment: createForm.value.subjectAssignment,
      groups: createForm.value.groups
        .filter((g) => g.name.trim() !== '')
        .map((g) => ({ name: g.name.trim(), description: g.description.trim() || undefined })),
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

/* ----------------------------- Lifecycle -------------------------- */

async function onDisable(row: GroupClass) {
  if (!studyOid.value) return
  if (!confirm(t('groupClasses.disableConfirm', { name: row.name }))) return
  await gc.disable(studyOid.value, row.id)
}

async function onRestore(row: GroupClass) {
  if (!studyOid.value) return
  await gc.restore(studyOid.value, row.id)
}

const typeOptions: { v: GroupClassType; l: () => string }[] = [
  { v: 'Arm',         l: () => t('groupClasses.type.Arm') },
  { v: 'Family',      l: () => t('groupClasses.type.Family') },
  { v: 'Demographic', l: () => t('groupClasses.type.Demographic') },
  { v: 'Other',       l: () => t('groupClasses.type.Other') },
]
const assignmentOptions: { v: SubjectAssignment; l: () => string }[] = [
  { v: 'REQUIRED', l: () => t('groupClasses.assignment.REQUIRED') },
  { v: 'OPTIONAL', l: () => t('groupClasses.assignment.OPTIONAL') },
]

const visibleRows = computed(() => gc.rows)
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.buildStudy') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-5xl px-8 py-6">
      <div class="mb-4 flex items-end justify-between gap-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('groupClasses.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('groupClasses.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('groupClasses.intro') }}</p>
        </div>
        <button
          v-if="canManage"
          class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
          @click="openCreate"
        >
          {{ t('groupClasses.createAction') }}
        </button>
      </div>

      <p v-if="gc.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="gc.error" class="text-rose-700">{{ gc.error }}</p>
      <p v-else-if="visibleRows.length === 0" class="text-slate-500 italic">{{ t('groupClasses.empty') }}</p>

      <ul v-else class="space-y-3">
        <li
          v-for="row in visibleRows"
          :key="row.id"
          class="rounded-md border border-slate-200 bg-white p-4"
        >
          <div class="flex items-start gap-3">
            <div class="flex-1 min-w-0">
              <div class="flex items-baseline gap-2 flex-wrap">
                <span class="font-medium text-slate-800">{{ row.name }}</span>
                <span class="font-mono text-[10px] text-slate-400">#{{ row.id }}</span>
                <StatusPill variant="neutral">{{ t(`groupClasses.type.${row.groupClassType}`) }}</StatusPill>
                <StatusPill v-if="row.subjectAssignment === 'REQUIRED'" variant="warning">
                  {{ t('groupClasses.assignment.REQUIRED') }}
                </StatusPill>
                <StatusPill v-else variant="info">{{ t('groupClasses.assignment.OPTIONAL') }}</StatusPill>
                <StatusPill v-if="row.status === 'removed'" variant="neutral">{{ t('groupClasses.statusRemoved') }}</StatusPill>
              </div>
              <div v-if="row.groups.length > 0" class="mt-2 border-t border-slate-100 pt-2">
                <div class="text-[10px] uppercase tracking-wide text-slate-500 mb-1">
                  {{ t('groupClasses.groupsHeading', { count: row.groups.length }) }}
                </div>
                <ul class="flex flex-wrap gap-1.5">
                  <li
                    v-for="g in row.groups"
                    :key="g.id"
                    class="text-xs px-2 py-0.5 rounded-md bg-slate-50 border border-slate-200 text-slate-700"
                    :class="{ 'line-through text-slate-400': g.status === 'removed' }"
                  >
                    {{ g.name }}<span v-if="g.description" class="text-slate-500"> — {{ g.description }}</span>
                  </li>
                </ul>
              </div>
            </div>
            <div v-if="canManage" class="flex items-center gap-2 text-xs shrink-0">
              <button
                v-if="row.status !== 'removed'"
                class="text-rose-600 hover:underline"
                @click="onDisable(row)"
              >{{ t('groupClasses.disable') }}</button>
              <button
                v-else
                class="text-emerald-700 hover:underline"
                @click="onRestore(row)"
              >{{ t('groupClasses.restore') }}</button>
            </div>
          </div>
        </li>
      </ul>

      <!-- Inline create form -->
      <div v-if="createOpen" class="mt-6 rounded-md border border-slate-200 bg-white p-4">
        <h2 class="text-sm font-semibold mb-3">{{ t('groupClasses.createHeading') }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div class="col-span-2">
            <FieldLabel for="gc-name" required>{{ t('groupClasses.field.name') }}</FieldLabel>
            <TextInput id="gc-name" v-model="createForm.name" />
            <ErrorText v-if="createErrors.name">{{ createErrors.name }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="gc-type" required>{{ t('groupClasses.field.type') }}</FieldLabel>
            <SelectInput id="gc-type" v-model="createForm.groupClassType">
              <option v-for="opt in typeOptions" :key="opt.v" :value="opt.v">{{ opt.l() }}</option>
            </SelectInput>
            <ErrorText v-if="createErrors.groupClassType">{{ createErrors.groupClassType }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="gc-assign" required>{{ t('groupClasses.field.assignment') }}</FieldLabel>
            <SelectInput id="gc-assign" v-model="createForm.subjectAssignment">
              <option v-for="opt in assignmentOptions" :key="opt.v" :value="opt.v">{{ opt.l() }}</option>
            </SelectInput>
            <ErrorText v-if="createErrors.subjectAssignment">{{ createErrors.subjectAssignment }}</ErrorText>
          </div>
        </div>

        <div class="mt-4">
          <div class="flex items-center justify-between mb-2">
            <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-500">
              {{ t('groupClasses.childGroupsHeading') }}
            </h3>
            <button
              class="text-[10px] uppercase tracking-wider text-muw-blue hover:underline"
              @click="addGroupRow"
            >{{ t('groupClasses.addGroup') }}</button>
          </div>
          <div class="space-y-2">
            <div
              v-for="(g, idx) in createForm.groups"
              :key="idx"
              class="grid grid-cols-[1fr_2fr_auto] gap-2"
            >
              <TextInput v-model="g.name" :placeholder="t('groupClasses.field.groupName')" />
              <TextInput v-model="g.description" :placeholder="t('groupClasses.field.groupDescription')" />
              <button
                class="text-xs text-rose-600 hover:underline disabled:opacity-30"
                :disabled="createForm.groups.length <= 1"
                @click="removeGroupRow(idx)"
              >×</button>
            </div>
          </div>
        </div>

        <ErrorText v-if="createFormError">{{ createFormError }}</ErrorText>
        <div class="mt-4 flex items-center gap-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            @click="createOpen = false"
          >{{ t('common.cancel') }}</button>
          <button
            class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="!canSubmitCreate || isCreating"
            @click="submitCreate"
          >{{ isCreating ? t('common.saving') : t('groupClasses.submitCreate') }}</button>
        </div>
      </div>
    </main>
  </div>
</template>

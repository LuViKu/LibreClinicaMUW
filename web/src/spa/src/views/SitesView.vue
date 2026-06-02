<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useSitesStore } from '@/stores/sites'
import { useAuthStore } from '@/stores/auth'
import type { StudyIdentity } from '@/types/study'

/**
 * Phase E A8.4 — sites under the active study.
 *
 * The active study must be the top-level parent (the backend 409s
 * the GET when the resolved study is itself a site). The SPA hides
 * the surface entirely when the active study is a site — the parent
 * picker remains the only entry point into a multi-center hierarchy.
 *
 * Role-gated client-side to Administrator + Data Manager; backend
 * re-checks authoritatively against the sysadmin / director /
 * coordinator triad.
 */
const { t } = useI18n()
const sites = useSitesStore()
const auth = useAuthStore()

const parentOid = computed(() => auth.user?.activeStudy?.oid ?? null)
const canManage = computed(() => {
  const role = auth.user?.role
  return role === 'Administrator' || role === 'Data Manager'
})

onMounted(() => { if (parentOid.value) sites.load(parentOid.value) })

watch(parentOid, (next) => { if (next) sites.load(next) })

/* ----------------------------- Create ----------------------------- */

interface CreateForm {
  name: string
  uniqueProtocolId: string
  principalInvestigator: string
  briefSummary: string
  facilityName: string
  facilityCity: string
  facilityContactEmail: string
}
const createOpen = ref(false)
const createForm = ref<CreateForm>(blankForm())
const createErrors = ref<Record<string, string>>({})
const createFormError = ref<string | null>(null)
const isCreating = ref(false)

function blankForm(): CreateForm {
  return {
    name: '',
    uniqueProtocolId: '',
    principalInvestigator: '',
    briefSummary: '',
    facilityName: '',
    facilityCity: '',
    facilityContactEmail: '',
  }
}

function openCreate() {
  createForm.value = blankForm()
  createErrors.value = {}
  createFormError.value = null
  createOpen.value = true
}

const canSubmitCreate = computed(() => {
  return (
    createForm.value.name.trim() !== '' &&
    createForm.value.uniqueProtocolId.trim() !== '' &&
    createForm.value.principalInvestigator.trim() !== ''
  )
})

async function submitCreate() {
  if (!parentOid.value || !canSubmitCreate.value) return
  createErrors.value = {}
  createFormError.value = null
  isCreating.value = true
  try {
    const result = await sites.create(parentOid.value, {
      name: createForm.value.name.trim(),
      uniqueProtocolId: createForm.value.uniqueProtocolId.trim(),
      principalInvestigator: createForm.value.principalInvestigator.trim(),
      briefSummary: createForm.value.briefSummary.trim() || undefined,
      facilityName: createForm.value.facilityName.trim() || undefined,
      facilityCity: createForm.value.facilityCity.trim() || undefined,
      facilityContactEmail: createForm.value.facilityContactEmail.trim() || undefined,
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

async function onDisable(site: StudyIdentity) {
  if (!parentOid.value) return
  if (!confirm(t('sites.disableConfirm', { name: site.name }))) return
  await sites.disable(parentOid.value, site.oid)
}

async function onRestore(site: StudyIdentity) {
  if (!parentOid.value) return
  await sites.restore(parentOid.value, site.oid)
}

const visibleRows = computed(() => sites.rows)
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
          <div class="text-xs text-slate-500 mb-1">{{ t('sites.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('sites.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('sites.intro') }}</p>
        </div>
        <button
          v-if="canManage"
          class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
          @click="openCreate"
        >
          {{ t('sites.createAction') }}
        </button>
      </div>

      <p v-if="sites.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="sites.error" class="text-rose-700">{{ sites.error }}</p>
      <p v-else-if="visibleRows.length === 0" class="text-slate-500 italic">{{ t('sites.empty') }}</p>

      <ul v-else class="space-y-2">
        <li
          v-for="site in visibleRows"
          :key="site.oid"
          class="rounded-md border border-slate-200 bg-white p-3 flex items-start gap-3"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-baseline gap-2 flex-wrap">
              <span class="font-medium text-slate-800">{{ site.name }}</span>
              <span class="font-mono text-[10px] text-slate-400">{{ site.oid }}</span>
              <StatusPill v-if="site.status === 'removed'" variant="neutral">{{ t('sites.statusRemoved') }}</StatusPill>
              <StatusPill v-else-if="site.status === 'pending'" variant="warning">{{ t('sites.statusPending') }}</StatusPill>
            </div>
            <p v-if="site.briefSummary" class="text-xs text-slate-500 mt-0.5">{{ site.briefSummary }}</p>
            <div class="text-xs text-slate-500 mt-1">
              {{ t('sites.pi') }}: {{ site.principalInvestigator || '—' }}
            </div>
          </div>
          <div v-if="canManage" class="flex items-center gap-2 text-xs shrink-0">
            <button
              v-if="site.status !== 'removed'"
              class="text-rose-600 hover:underline"
              @click="onDisable(site)"
            >{{ t('sites.disable') }}</button>
            <button
              v-else
              class="text-emerald-700 hover:underline"
              @click="onRestore(site)"
            >{{ t('sites.restore') }}</button>
          </div>
        </li>
      </ul>

      <div v-if="createOpen" class="mt-6 rounded-md border border-slate-200 bg-white p-4">
        <h2 class="text-sm font-semibold mb-3">{{ t('sites.createHeading') }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <FieldLabel for="site-name" required>{{ t('sites.field.name') }}</FieldLabel>
            <TextInput id="site-name" v-model="createForm.name" />
            <ErrorText v-if="createErrors.name">{{ createErrors.name }}</ErrorText>
          </div>
          <div>
            <FieldLabel for="site-uid" required>{{ t('sites.field.uniqueProtocolId') }}</FieldLabel>
            <TextInput id="site-uid" v-model="createForm.uniqueProtocolId" />
            <ErrorText v-if="createErrors.uniqueProtocolId">{{ createErrors.uniqueProtocolId }}</ErrorText>
          </div>
          <div class="col-span-2">
            <FieldLabel for="site-pi" required>{{ t('sites.field.principalInvestigator') }}</FieldLabel>
            <TextInput id="site-pi" v-model="createForm.principalInvestigator" />
            <ErrorText v-if="createErrors.principalInvestigator">{{ createErrors.principalInvestigator }}</ErrorText>
          </div>
          <div class="col-span-2">
            <FieldLabel for="site-summary">{{ t('sites.field.briefSummary') }}</FieldLabel>
            <TextInput id="site-summary" v-model="createForm.briefSummary" />
          </div>
          <div>
            <FieldLabel for="site-fac-name">{{ t('sites.field.facilityName') }}</FieldLabel>
            <TextInput id="site-fac-name" v-model="createForm.facilityName" />
          </div>
          <div>
            <FieldLabel for="site-fac-city">{{ t('sites.field.facilityCity') }}</FieldLabel>
            <TextInput id="site-fac-city" v-model="createForm.facilityCity" />
          </div>
          <div class="col-span-2">
            <FieldLabel for="site-fac-email">{{ t('sites.field.facilityContactEmail') }}</FieldLabel>
            <TextInput id="site-fac-email" v-model="createForm.facilityContactEmail" type="email" />
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
            :disabled="!canSubmitCreate || isCreating"
            @click="submitCreate"
          >{{ isCreating ? t('common.saving') : t('sites.submitCreate') }}</button>
        </div>
      </div>
    </main>
  </div>
</template>

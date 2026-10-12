<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import BuildStudyRail from '@/components/BuildStudyRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import HelperText from '@/components/HelperText.vue'
import LocationSelect from '@/components/LocationSelect.vue'
import SiteLocationDialog from '@/components/SiteLocationDialog.vue'

import { useSitesStore } from '@/stores/sites'
import { useLocationOptionsStore } from '@/stores/locationOptions'
import { useNotificationsStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { mayBuildStudy, maySysadminOnly } from '@/lib/studyBuildAccess'
import { useConfirm } from '@/composables/useConfirm'
import SkeletonList from '@/components/SkeletonList.vue'
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
 *
 * DR-040 phase 4: every site (Prüfzentrum) is at a location (Standort).
 * The create form has a required Standort select over the locations the
 * caller may use (preselected when there is exactly one); the facility
 * name and address come from the location, so the form no longer asks for
 * them. The list shows each site's location, and in multi-location mode
 * those who may manage sites can move a site to another location.
 */
const { t } = useI18n()
const sites = useSitesStore()
const auth = useAuthStore()
const confirm = useConfirm()
const locationOptions = useLocationOptionsStore()

const parentOid = computed(() => auth.user?.activeStudy?.oid ?? null)
const canManage = computed(() => mayBuildStudy(auth.user?.role))
// Disable and restore are sysadmin-only in the backend (roleMayLifecycleStudy).
const canLifecycle = computed(() => maySysadminOnly(auth.user?.role))
// Moving a site exists only where there is more than one place to move it to.
const canMove = computed(() => canManage.value && auth.locationMode === 'multi')

onMounted(() => {
  if (parentOid.value) sites.load(parentOid.value)
  if (canManage.value) void locationOptions.load()
})

watch(parentOid, (next) => { if (next) sites.load(next) })

/* ----------------------------- Create ----------------------------- */

interface CreateForm {
  name: string
  uniqueProtocolId: string
  principalInvestigator: string
  briefSummary: string
  /** The site's location (Standort); the server copies the facility address from it. */
  locationId: number | null
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
    locationId: onlyLocationId(),
    facilityContactEmail: '',
  }
}

/** The one location there is to choose, else null (the person chooses). */
function onlyLocationId(): number | null {
  return locationOptions.options.length === 1 ? locationOptions.options[0].id : null
}

// The options may arrive after the form was opened: preselect then.
watch(() => locationOptions.options, () => {
  if (createOpen.value && createForm.value.locationId == null) createForm.value.locationId = onlyLocationId()
})

// A field error belongs to the choice it was given for.
watch(() => createForm.value.locationId, () => {
  if (createErrors.value.locationId) {
    const { locationId: _dropped, ...rest } = createErrors.value
    createErrors.value = rest
  }
})

/**
 * The Standort is required. The one exception is single-location mode with
 * no option to show (the list could not be loaded): there the server takes
 * the one location itself, so the form does not block on it.
 */
const locationMissing = computed(
  () =>
    createForm.value.locationId == null &&
    !(auth.locationMode === 'single' && locationOptions.options.length === 0 && !locationOptions.isLoading),
)

function openCreate() {
  if (!locationOptions.loaded && !locationOptions.isLoading) void locationOptions.load()
  createForm.value = blankForm()
  createErrors.value = {}
  createFormError.value = null
  createOpen.value = true
}

const canSubmitCreate = computed(() => {
  return (
    createForm.value.name.trim() !== '' &&
    createForm.value.uniqueProtocolId.trim() !== '' &&
    createForm.value.principalInvestigator.trim() !== '' &&
    !locationMissing.value
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
      locationId: createForm.value.locationId ?? undefined,
      facilityContactEmail: createForm.value.facilityContactEmail.trim() || undefined,
    })
    if (result.ok) {
      createOpen.value = false
      useNotificationsStore().success(t('sites.created', { name: createForm.value.name.trim() }))
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
  if (!(await confirm({ message: t('sites.disableConfirm', { name: site.name }), danger: true }))) return
  await sites.disable(parentOid.value, site.oid)
}

async function onRestore(site: StudyIdentity) {
  if (!parentOid.value) return
  await sites.restore(parentOid.value, site.oid)
}

/* ----------------------------- Move ------------------------------- */

const moveOpen = ref(false)
const moveSite = ref<StudyIdentity | null>(null)

function openMove(site: StudyIdentity) {
  moveSite.value = site
  moveOpen.value = true
}

function onMoved(site: StudyIdentity) {
  useNotificationsStore().success(
    t('sites.move.done', { name: site.name, location: site.locationName ?? '—' }),
  )
}

const visibleRows = computed(() => sites.rows)
</script>

<template>
  <div class="flex">
    <BuildStudyRail />

    <div class="flex-1 max-w-5xl px-8 py-6">
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

      <!-- Error as a dismissible banner ABOVE the list — a failed disable/
           restore no longer replaces (unmounts) the whole site list. -->
      <div
        v-if="sites.error"
        class="mb-3 flex items-start gap-2 rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800"
        role="alert"
        data-testid="sites-error"
      >
        <span class="flex-1">{{ sites.error }}</span>
        <button type="button" class="shrink-0 text-rose-500 hover:text-rose-700" :aria-label="t('common.dismiss')" @click="sites.error = null">✕</button>
      </div>
      <template v-if="sites.isLoading">
        <span class="sr-only" role="status">{{ t('common.loading') }}</span>
        <SkeletonList :rows="3" />
      </template>
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
            <div class="text-xs text-slate-500 mt-1 flex flex-wrap gap-x-4 gap-y-0.5">
              <span :data-testid="`site-location-${site.oid}`">{{ t('sites.location') }}: {{ site.locationName || '—' }}</span>
              <span>{{ t('sites.pi') }}: {{ site.principalInvestigator || '—' }}</span>
            </div>
          </div>
          <div v-if="canLifecycle || canMove" class="flex items-center gap-2 text-xs shrink-0">
            <button
              v-if="canMove"
              class="text-muw-blue hover:underline"
              :data-testid="`site-move-${site.oid}`"
              @click="openMove(site)"
            >{{ t('sites.move.action') }}</button>
            <template v-if="canLifecycle">
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
            </template>
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
          <div class="col-span-2">
            <FieldLabel for="site-location" required>{{ t('sites.field.location') }}</FieldLabel>
            <LocationSelect
              id="site-location"
              v-model="createForm.locationId"
              :options="locationOptions.options"
              :error="!!createErrors.locationId"
            />
            <ErrorText v-if="createErrors.locationId" data-testid="site-location-error">{{ createErrors.locationId }}</ErrorText>
            <ErrorText v-else-if="locationOptions.error">{{ locationOptions.error }}</ErrorText>
            <HelperText v-else-if="locationOptions.loaded && locationOptions.options.length === 0">{{ t('locationSelect.none') }}</HelperText>
            <HelperText v-else>{{ t('sites.field.locationHint') }}</HelperText>
          </div>
          <div class="col-span-2">
            <FieldLabel for="site-fac-email">{{ t('sites.field.facilityContactEmail') }}</FieldLabel>
            <TextInput id="site-fac-email" v-model="createForm.facilityContactEmail" type="email" />
            <ErrorText v-if="createErrors.facilityContactEmail">{{ createErrors.facilityContactEmail }}</ErrorText>
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

      <SiteLocationDialog
        v-if="canMove"
        v-model:open="moveOpen"
        :site="moveSite"
        :parent-oid="parentOid"
        :options="locationOptions.options"
        @saved="onMoved"
      />
    </div>
  </div>
</template>

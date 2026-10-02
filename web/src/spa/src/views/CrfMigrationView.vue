<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'

import BuildStudyRail from '@/components/BuildStudyRail.vue'
import ErrorText from '@/components/ErrorText.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import SelectInput from '@/components/SelectInput.vue'
import TextInput from '@/components/TextInput.vue'

import { useAuthStore } from '@/stores/auth'
import { useCrfLibraryStore } from '@/stores/crfLibrary'
import { migrationLogCsv, useCrfMigrationStore } from '@/stores/crfMigration'
import type { EventCrfMigrationRequest } from '@/types/crfMigration'
import type { CrfVersion, MigrateVersionResult } from '@/types/crfLibrary'

/**
 * Version migration of one CRF, two operations kept apart on purpose:
 *
 * 1. **Move existing event CRFs to another version** — the legacy batch CRF
 *    version migration (BatchCRFMigrationServlet / Controller), and, with
 *    the subject filter or "only this one", the single event CRF version
 *    change (ChangeCRFVersionController). Existing data is shown on the new
 *    version; SDV is cleared and the signatures over the moved CRFs are
 *    removed. Preview first; the run sends the previewed count, so a
 *    selection that changed in between is refused.
 * 2. **Change the default version for new event CRFs** — `migrate-to`,
 *    which moves no data.
 */
const { t } = useI18n()
const route = useRoute()
const auth = useAuthStore()
const lib = useCrfLibraryStore()
const mig = useCrfMigrationStore()

const crfOid = computed(() => String(route.params.crfOid ?? ''))
const studyOid = computed(() => auth.user?.activeStudy?.oid ?? null)

// The two sections load apart: moving data is study-scoped (Data Manager or
// CRC of the study), changing defaults is a CRF-library write (sysadmin or
// Data Manager / CRC anywhere), so either may be refused alone.
const crfName = ref<string | null>(null)
const crfVersions = ref<CrfVersion[]>([])
const crfLoadError = ref<string | null>(null)

onMounted(async () => {
  mig.reset()
  if (studyOid.value) void mig.loadOptions(crfOid.value, studyOid.value)
  const detail = await lib.fetchCrfDetail(crfOid.value)
  if (detail.ok) {
    crfName.value = detail.detail.name
    crfVersions.value = detail.detail.versions
  } else {
    crfLoadError.value = detail.message
  }
})

/* ---------------------- Move existing event CRFs ------------------ */

const form = reactive({
  source: '',
  target: '',
  allSites: true,
  siteOids: [] as string[],
  allEvents: true,
  eventOids: [] as string[],
  subjectLabel: '',
  eventCrfIds: [] as number[],
})
const confirmed = ref(false)

// Any change to the selection makes the preview stale: drop it, and the
// confirmation with it, so a run always matches what was last shown.
watch(
  () => JSON.stringify(form),
  () => {
    if (mig.preview) mig.clearPreview()
    confirmed.value = false
  },
  { flush: 'sync' },
)

const sourceVersions = computed(() =>
  (mig.options?.versions ?? []).filter((v) => v.status === 'available' || v.status === 'locked'),
)
const targetVersions = computed(() =>
  (mig.options?.versions ?? []).filter((v) => v.status === 'available' && v.oid !== form.source),
)

function request(): EventCrfMigrationRequest {
  return {
    studyOid: mig.options?.study.oid ?? studyOid.value ?? '',
    sourceVersionOid: form.source,
    targetVersionOid: form.target,
    siteOids: form.allSites ? [] : [...form.siteOids],
    eventDefinitionOids: form.allEvents ? [] : [...form.eventOids],
    studySubjectLabel: form.subjectLabel.trim() || undefined,
    eventCrfIds: form.eventCrfIds.length > 0 ? [...form.eventCrfIds] : undefined,
  }
}

const canPreview = computed(() => !!mig.options && form.source !== '' && form.target !== '' && !mig.isPreviewing)

async function onPreview() {
  confirmed.value = false
  await mig.runPreview(crfOid.value, request())
}

async function onlyThis(eventCrfId: number) {
  form.eventCrfIds = [eventCrfId]
  await onPreview()
}

async function clearOnlyThis() {
  form.eventCrfIds = []
  await onPreview()
}

const canRun = computed(() =>
  !!mig.preview && mig.preview.eventCrfCount > 0 && confirmed.value && !mig.isRunning,
)

async function onRun() {
  if (!canRun.value) return
  await mig.run(crfOid.value, request())
}

function skipReason(reason: string): string {
  const key = `crfMigration.skip.${reason}`
  const text = t(key)
  return text === key ? reason : text
}

function downloadLog() {
  if (!mig.result) return
  const blob = new Blob([migrationLogCsv(mig.result)], { type: 'text/csv;charset=utf-8' })
  const href = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = href
  a.download = `crf-version-migration-${mig.result.crfOid}-${mig.result.completedAt.replace(/[:]/g, '')}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(href)
}

/* ---------------- Change the default version (migrate-to) --------- */

const defaults = reactive({ from: '', to: '' })
const defaultsPreview = ref<MigrateVersionResult | null>(null)
const defaultsCommitted = ref<MigrateVersionResult | null>(null)
const defaultsError = ref<string | null>(null)
const defaultsBusy = ref(false)

watch(
  () => [defaults.from, defaults.to],
  () => {
    defaultsPreview.value = null
    defaultsCommitted.value = null
    defaultsError.value = null
  },
  { flush: 'sync' },
)

const defaultsSources = computed(() =>
  crfVersions.value.filter((v) => v.status === 'available' || v.status === 'locked'),
)
const defaultsTargets = computed(() =>
  crfVersions.value.filter((v) => v.status === 'available' && v.oid !== defaults.from),
)

async function defaultsCall(dryRun: boolean) {
  if (!defaults.from || !defaults.to) return
  defaultsBusy.value = true
  defaultsError.value = null
  try {
    const res = await lib.migrateVersion(crfOid.value, defaults.from, defaults.to, { dryRun })
    if (!res.ok) {
      defaultsError.value = t('crfLibrary.migrate.failed', { message: res.message })
      return
    }
    if (dryRun) defaultsPreview.value = res.result
    else {
      defaultsCommitted.value = res.result
      defaultsPreview.value = null
    }
  } catch (e) {
    // The store rethrows a refusal (403) and a lost session (401).
    const body = (e as { body?: { message?: string } }).body
    defaultsError.value = t('crfLibrary.migrate.failed', { message: body?.message ?? String(e) })
  } finally {
    defaultsBusy.value = false
  }
}
</script>

<template>
  <div class="flex">
    <BuildStudyRail />

    <div class="flex-1 max-w-6xl px-8 py-6">
      <RouterLink to="/crf-library" class="text-xs text-muw-blue hover:underline">
        ← {{ t('crfLibrary.detail.back') }}
      </RouterLink>
      <h1 class="mt-3 text-xl font-semibold tracking-tight">
        {{ t('crfMigration.title', { name: crfName ?? mig.options?.crfName ?? crfOid }) }}
      </h1>
      <p class="text-xs text-slate-500 mt-1 max-w-3xl leading-relaxed">{{ t('crfMigration.intro') }}</p>

      <!-- 1 · Move existing event CRFs -->
      <section class="mt-5 rounded-muw border border-slate-200 bg-white p-5" data-testid="crf-migration-move">
        <h2 class="text-sm font-semibold">{{ t('crfMigration.move.heading') }}</h2>
        <p class="text-xs text-slate-600 mt-1 leading-relaxed">{{ t('crfMigration.move.intro') }}</p>

        <p v-if="!studyOid" class="mt-3 text-xs text-rose-700" role="alert">{{ t('crfMigration.noStudy') }}</p>
        <p v-else-if="mig.isLoading" class="mt-3 text-xs text-slate-500 italic">{{ t('common.loading') }}</p>
        <p
          v-else-if="!mig.options && mig.error"
          class="mt-3 text-xs text-rose-700"
          role="alert"
          data-testid="crf-migration-options-error"
        >{{ mig.error }}</p>

        <template v-if="mig.options">
          <p class="text-xs text-slate-500 mt-1">{{ t('crfMigration.move.study', { study: mig.options.study.name }) }}</p>

          <div class="grid grid-cols-2 gap-4 mt-4">
            <div>
              <FieldLabel for="mig-source" required>{{ t('crfMigration.move.source') }}</FieldLabel>
              <SelectInput id="mig-source" v-model="form.source" :error="!!mig.fieldErrors.sourceVersionOid">
                <option value="">—</option>
                <option v-for="v in sourceVersions" :key="v.oid" :value="v.oid">
                  {{ t('crfMigration.move.versionOption', { name: v.name, count: v.eventCrfCount }) }}
                </option>
              </SelectInput>
              <ErrorText v-if="mig.fieldErrors.sourceVersionOid">{{ mig.fieldErrors.sourceVersionOid }}</ErrorText>
            </div>
            <div>
              <FieldLabel for="mig-target" required>{{ t('crfMigration.move.target') }}</FieldLabel>
              <SelectInput id="mig-target" v-model="form.target" :error="!!mig.fieldErrors.targetVersionOid">
                <option value="">—</option>
                <option v-for="v in targetVersions" :key="v.oid" :value="v.oid">{{ v.name }}</option>
              </SelectInput>
              <ErrorText v-if="mig.fieldErrors.targetVersionOid">{{ mig.fieldErrors.targetVersionOid }}</ErrorText>
            </div>

            <fieldset>
              <legend class="text-xs font-medium text-slate-700 mb-1">{{ t('crfMigration.move.sites') }}</legend>
              <label class="text-xs inline-flex items-center gap-1.5">
                <input v-model="form.allSites" type="checkbox" data-testid="mig-all-sites" />
                {{ t('crfMigration.move.allSites') }}
              </label>
              <div v-if="!form.allSites" class="mt-1 space-y-0.5">
                <label v-for="(s, i) in mig.options.sites" :key="s.oid" class="block text-xs">
                  <input v-model="form.siteOids" type="checkbox" :value="s.oid" :data-testid="`mig-site-${s.oid}`" />
                  {{ i === 0 ? t('crfMigration.move.studyLevel', { study: s.name }) : s.name }}
                </label>
              </div>
              <ErrorText v-if="mig.fieldErrors.siteOids">{{ mig.fieldErrors.siteOids }}</ErrorText>
            </fieldset>
            <fieldset>
              <legend class="text-xs font-medium text-slate-700 mb-1">{{ t('crfMigration.move.events') }}</legend>
              <label class="text-xs inline-flex items-center gap-1.5">
                <input v-model="form.allEvents" type="checkbox" data-testid="mig-all-events" />
                {{ t('crfMigration.move.allEvents') }}
              </label>
              <div v-if="!form.allEvents" class="mt-1 space-y-0.5">
                <label v-for="e in mig.options.eventDefinitions" :key="e.oid" class="block text-xs">
                  <input v-model="form.eventOids" type="checkbox" :value="e.oid" :data-testid="`mig-event-${e.oid}`" />
                  {{ e.name }}
                </label>
              </div>
              <ErrorText v-if="mig.fieldErrors.eventDefinitionOids">{{ mig.fieldErrors.eventDefinitionOids }}</ErrorText>
            </fieldset>

            <div>
              <FieldLabel for="mig-subject">{{ t('crfMigration.move.subject') }}</FieldLabel>
              <TextInput id="mig-subject" v-model="form.subjectLabel" />
              <p class="text-[11px] text-slate-500 mt-1">{{ t('crfMigration.move.subjectHint') }}</p>
              <ErrorText v-if="mig.fieldErrors.studySubjectLabel">{{ mig.fieldErrors.studySubjectLabel }}</ErrorText>
            </div>
          </div>

          <div v-if="form.eventCrfIds.length > 0" class="mt-3 text-xs" data-testid="mig-only-chip">
            <span class="rounded bg-slate-100 px-2 py-1">{{ t('crfMigration.move.onlyEventCrf', { id: form.eventCrfIds[0] }) }}</span>
            <button class="ml-2 text-muw-blue hover:underline" @click="clearOnlyThis">{{ t('crfMigration.move.clearOnly') }}</button>
          </div>

          <p v-if="mig.error" class="mt-3 text-xs text-rose-700" role="alert" data-testid="mig-error">{{ mig.error }}</p>
          <ErrorText v-if="mig.fieldErrors.studyOid || mig.fieldErrors.expectedEventCrfCount">
            {{ mig.fieldErrors.studyOid ?? mig.fieldErrors.expectedEventCrfCount }}
          </ErrorText>

          <div class="mt-4">
            <button
              class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
              :disabled="!canPreview"
              data-testid="mig-preview"
              @click="onPreview"
            >{{ mig.isPreviewing ? t('common.loading') : t('crfMigration.move.preview') }}</button>
          </div>

          <!-- Preview -->
          <div v-if="mig.preview" class="mt-5 border-t border-slate-100 pt-4" data-testid="mig-preview-panel">
            <p class="text-sm" data-testid="mig-preview-summary">
              {{ t('crfMigration.preview.summary', {
                count: mig.preview.eventCrfCount,
                subjects: mig.preview.subjectCount,
                from: mig.preview.sourceVersion.name,
                to: mig.preview.targetVersion.name,
              }) }}
            </p>

            <div
              v-if="mig.preview.eventCrfCount > 0"
              class="mt-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900"
              role="note"
              data-testid="mig-warning"
            >
              <p class="font-semibold">{{ t('crfMigration.preview.warningHeading') }}</p>
              <ul class="list-disc pl-5 mt-1 space-y-0.5">
                <li>{{ t('crfMigration.preview.sdv', { count: mig.preview.sdvVerifiedCount }) }}</li>
                <li>{{ t('crfMigration.preview.signatures', {
                  subjects: mig.preview.signedSubjectCount,
                  events: mig.preview.signedEventCount,
                  crfs: mig.preview.signedEventCrfCount,
                }) }}</li>
                <li v-if="mig.preview.hiddenValueCount > 0" data-testid="mig-hidden">
                  {{ t('crfMigration.preview.hidden', { count: mig.preview.hiddenValueCount, to: mig.preview.targetVersion.name }) }}
                  {{ mig.preview.hiddenItems.map((h) => `${h.name} (${h.valueCount})`).join(', ') }}
                </li>
              </ul>
            </div>

            <div v-if="mig.preview.locked.length > 0" class="mt-3 text-xs" data-testid="mig-locked">
              <p class="font-medium">{{ t('crfMigration.preview.locked', { count: mig.preview.locked.length }) }}</p>
              <ul class="list-disc pl-5 mt-1 space-y-0.5">
                <li v-for="s in mig.preview.locked" :key="s.row.eventCrfId">
                  {{ s.row.studySubjectLabel }} · {{ s.row.eventName }} — {{ skipReason(s.reason) }}
                </li>
              </ul>
            </div>

            <div v-if="mig.preview.notOffered.length > 0" class="mt-3 text-xs" data-testid="mig-not-offered">
              <p class="font-medium">{{ t('crfMigration.preview.notOffered') }}</p>
              <ul class="list-disc pl-5 mt-1 space-y-0.5">
                <li v-for="n in mig.preview.notOffered" :key="`${n.siteOid}-${n.eventDefinitionOid}`">
                  {{ t('crfMigration.preview.notOfferedRow', { site: n.siteName, event: n.eventName, count: n.eventCrfCount }) }}
                </li>
              </ul>
            </div>

            <table v-if="mig.preview.eventCrfs.length > 0" class="mt-4 w-full text-xs border border-slate-200 rounded-md">
              <thead class="bg-slate-50 text-slate-500 text-left">
                <tr>
                  <th class="px-3 py-2 font-medium">{{ t('crfMigration.col.subject') }}</th>
                  <th class="px-3 py-2 font-medium">{{ t('crfMigration.col.site') }}</th>
                  <th class="px-3 py-2 font-medium">{{ t('crfMigration.col.event') }}</th>
                  <th class="px-3 py-2 font-medium">{{ t('crfMigration.col.clears') }}</th>
                  <th class="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="r in mig.preview.eventCrfs"
                  :key="r.eventCrfId"
                  class="border-t border-slate-100"
                  :data-testid="`mig-row-${r.eventCrfId}`"
                >
                  <td class="px-3 py-1.5 font-mono">{{ r.studySubjectLabel }}</td>
                  <td class="px-3 py-1.5">{{ r.siteName }}</td>
                  <td class="px-3 py-1.5">{{ r.eventName }}<span v-if="r.eventOrdinal > 1"> #{{ r.eventOrdinal }}</span></td>
                  <td class="px-3 py-1.5 text-slate-600">
                    <span v-if="r.sdvVerified">{{ t('crfMigration.flag.sdv') }} </span>
                    <span v-if="r.subjectSigned">{{ t('crfMigration.flag.subject') }} </span>
                    <span v-if="r.eventSigned">{{ t('crfMigration.flag.event') }} </span>
                    <span v-if="r.eventCrfSigned">{{ t('crfMigration.flag.crf') }}</span>
                  </td>
                  <td class="px-3 py-1.5 text-right">
                    <button
                      v-if="mig.preview.eventCrfCount > 1"
                      class="text-muw-blue hover:underline"
                      :data-testid="`mig-only-${r.eventCrfId}`"
                      @click="onlyThis(r.eventCrfId)"
                    >{{ t('crfMigration.move.onlyThis') }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-if="mig.preview.eventCrfsTruncated" class="mt-1 text-[11px] text-slate-500">
              {{ t('crfMigration.preview.truncated', { shown: mig.preview.eventCrfs.length, count: mig.preview.eventCrfCount }) }}
            </p>

            <div v-if="mig.preview.eventCrfCount > 0" class="mt-4">
              <label class="text-xs inline-flex items-start gap-2">
                <input v-model="confirmed" type="checkbox" data-testid="mig-confirm" />
                <span>{{ t('crfMigration.preview.confirm', { count: mig.preview.eventCrfCount }) }}</span>
              </label>
              <div class="mt-3">
                <button
                  class="px-4 py-1.5 text-xs bg-rose-700 text-white rounded-md hover:bg-rose-800 font-medium disabled:opacity-50"
                  :disabled="!canRun"
                  data-testid="mig-run"
                  @click="onRun"
                >{{ mig.isRunning ? t('common.saving') : t('crfMigration.move.run', { count: mig.preview.eventCrfCount }) }}</button>
              </div>
            </div>
          </div>

          <!-- Result -->
          <div v-if="mig.result" class="mt-5 border-t border-slate-100 pt-4" data-testid="mig-result">
            <p class="text-sm font-medium text-emerald-800">
              {{ t('crfMigration.result.summary', {
                count: mig.result.migratedEventCrfCount,
                subjects: mig.result.subjectCount,
                from: mig.result.sourceVersion.name,
                to: mig.result.targetVersion.name,
              }) }}
            </p>
            <p class="text-xs text-slate-600 mt-1">
              {{ t('crfMigration.result.cleared', {
                sdv: mig.result.sdvClearedCount,
                subjects: mig.result.unsignedSubjectCount,
                events: mig.result.unsignedEventCount,
                crfs: mig.result.unsignedEventCrfCount,
              }) }}
            </p>
            <p class="text-xs text-slate-600 mt-1">
              {{ t('crfMigration.result.where') }}
              <RouterLink :to="{ name: 'audit-log' }" class="text-muw-blue hover:underline">{{ t('crfMigration.result.auditLog') }}</RouterLink>
            </p>
            <button
              class="mt-2 px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700"
              data-testid="mig-download-log"
              @click="downloadLog"
            >{{ t('crfMigration.result.download') }}</button>
            <table class="mt-3 w-full text-xs border border-slate-200 rounded-md" data-testid="mig-log">
              <thead class="bg-slate-50 text-slate-500 text-left">
                <tr>
                  <th class="px-3 py-2 font-medium">{{ t('crfMigration.col.subject') }}</th>
                  <th class="px-3 py-2 font-medium">{{ t('crfMigration.col.site') }}</th>
                  <th class="px-3 py-2 font-medium">{{ t('crfMigration.col.event') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in mig.result.log" :key="r.eventCrfId" class="border-t border-slate-100">
                  <td class="px-3 py-1.5 font-mono">{{ r.studySubjectLabel }}</td>
                  <td class="px-3 py-1.5">{{ r.siteName }}</td>
                  <td class="px-3 py-1.5">{{ r.eventName }}<span v-if="r.eventOrdinal > 1"> #{{ r.eventOrdinal }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </section>

      <!-- 2 · Change the default version for new event CRFs -->
      <section class="mt-6 rounded-muw border border-slate-200 bg-white p-5" data-testid="crf-migration-defaults">
        <h2 class="text-sm font-semibold">{{ t('crfMigration.defaults.heading') }}</h2>
        <p class="text-xs text-slate-600 mt-1 leading-relaxed">{{ t('crfMigration.defaults.intro') }}</p>
        <p v-if="crfLoadError" class="mt-3 text-xs text-rose-700" role="alert">{{ crfLoadError }}</p>
        <div class="grid grid-cols-2 gap-4 mt-4">
          <div>
            <FieldLabel for="def-from" required>{{ t('crfMigration.defaults.from') }}</FieldLabel>
            <SelectInput id="def-from" v-model="defaults.from">
              <option value="">—</option>
              <option v-for="v in defaultsSources" :key="v.oid" :value="v.oid">{{ v.name }}</option>
            </SelectInput>
          </div>
          <div>
            <FieldLabel for="def-to" required>{{ t('crfMigration.defaults.to') }}</FieldLabel>
            <SelectInput id="def-to" v-model="defaults.to">
              <option value="">—</option>
              <option v-for="v in defaultsTargets" :key="v.oid" :value="v.oid">{{ v.name }}</option>
            </SelectInput>
          </div>
        </div>
        <p v-if="defaultsError" class="mt-3 text-xs text-rose-700" role="alert">{{ defaultsError }}</p>
        <div class="mt-4 flex items-center gap-2">
          <button
            class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700 disabled:opacity-50"
            :disabled="!defaults.from || !defaults.to || defaultsBusy"
            data-testid="def-preview"
            @click="defaultsCall(true)"
          >{{ t('crfLibrary.migrate.preview') }}</button>
        </div>
        <div v-if="defaultsPreview" class="mt-4 text-xs" data-testid="def-preview-panel">
          <p class="font-medium">{{ t('crfLibrary.migrate.dryRunHeading') }}</p>
          <p v-if="defaultsPreview.perSed.length === 0" class="mt-1 text-slate-500 italic">
            {{ t('crfLibrary.migrate.noSeds', { from: defaultsPreview.fromVersionOid }) }}
          </p>
          <table v-else class="mt-2 w-full border border-slate-200 rounded-md">
            <thead class="bg-slate-50 text-slate-500 text-left">
              <tr>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.migrate.sedColumn') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.migrate.studyColumn') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.migrate.migratedColumn') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in defaultsPreview.perSed" :key="row.sedOid" class="border-t border-slate-100">
                <td class="px-3 py-1.5">{{ row.sedName ?? row.sedOid }}</td>
                <td class="px-3 py-1.5 font-mono">{{ row.studyOid }}</td>
                <td class="px-3 py-1.5">
                  {{ row.migrated ? t('crfLibrary.migrate.migratedYes') : t('crfLibrary.migrate.migratedNo') }}
                  <span v-if="row.reasonSkipped" class="text-slate-500">— {{ row.reasonSkipped }}</span>
                </td>
              </tr>
            </tbody>
          </table>
          <button
            v-if="defaultsPreview.totalMigrated > 0"
            class="mt-3 px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            :disabled="defaultsBusy"
            data-testid="def-commit"
            @click="defaultsCall(false)"
          >{{ t('crfLibrary.migrate.commit') }}</button>
        </div>
        <p v-if="defaultsCommitted" class="mt-3 text-xs text-emerald-800" data-testid="def-committed">
          {{ t('crfLibrary.migrate.committed', { count: defaultsCommitted.totalMigrated }) }}
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Every study on the platform, for the system administrator: the SPA
 * replacement of the legacy "Administer Studies" list (/ListStudy).
 *
 * Top-level studies with their sites under them, removed ones included,
 * with the columns the legacy list had minus the facility: name, unique
 * protocol id, OID, principal investigator, created date and status. A
 * study can be removed or restored here (sites have their own page), and
 * any study's ODM metadata downloaded for the archive.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SystemRail from '@/components/SystemRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import StudyLifecycleDialog from '@/components/StudyLifecycleDialog.vue'

import { isRemoved, useAdminStudiesStore } from '@/stores/adminStudies'
import { formatDate } from '@/lib/dateFormat'
import type { AdminStudy, AdminStudyStatus } from '@/types/study'

const { t } = useI18n()
const store = useAdminStudiesStore()

onMounted(() => { void store.load() })

const query = ref('')

function matches(s: AdminStudy, q: string): boolean {
  return [s.name, s.uniqueIdentifier, s.oid, s.principalInvestigator]
    .some((v) => (v ?? '').toLowerCase().includes(q))
}

/** A study stays when it or one of its sites matches; a site when it or its study does. */
const rows = computed<{ study: AdminStudy; parent: AdminStudy | null }[]>(() => {
  const q = query.value.trim().toLowerCase()
  const out: { study: AdminStudy; parent: AdminStudy | null }[] = []
  for (const s of store.studies) {
    const parentHit = q === '' || matches(s, q)
    const sites = s.sites.filter((site) => parentHit || matches(site, q))
    if (!parentHit && sites.length === 0) continue
    out.push({ study: s, parent: null })
    for (const site of sites) out.push({ study: site, parent: s })
  }
  return out
})

const siteCount = computed(() => store.studies.reduce((n, s) => n + s.sites.length, 0))

function statusVariant(status: AdminStudyStatus): 'success' | 'info' | 'warning' | 'neutral' {
  switch (status) {
    case 'AVAILABLE': return 'success'
    case 'PENDING': return 'info'
    case 'FROZEN':
    case 'LOCKED': return 'warning'
    default: return 'neutral'
  }
}

const dialogOpen = ref(false)
const dialogStudy = ref<AdminStudy | null>(null)
const dialogAction = ref<'remove' | 'restore'>('remove')

function openLifecycle(study: AdminStudy, action: 'remove' | 'restore') {
  dialogStudy.value = study
  dialogAction.value = action
  dialogOpen.value = true
}

const downloading = ref<string | null>(null)
const downloadError = ref<string | null>(null)

async function download(study: AdminStudy) {
  downloading.value = study.oid
  downloadError.value = null
  try {
    await store.downloadMetadata(study.oid)
  } catch (e) {
    downloadError.value = t('adminStudies.downloadFailed', {
      study: study.name,
      reason: e instanceof Error ? e.message : '',
    })
  } finally {
    downloading.value = null
  }
}
</script>

<template>
  <div class="flex">
    <SystemRail />

    <div class="flex-1 max-w-6xl px-8 py-6">
      <div class="flex items-end justify-between mb-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('system.rail.heading') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('adminStudies.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1">
            {{ t('adminStudies.subtitle', { studies: store.studies.length, sites: siteCount }) }}
          </p>
        </div>
        <RouterLink
          to="/studies/new"
          class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700"
        >
          {{ t('adminStudies.create') }}
        </RouterLink>
      </div>

      <div v-if="store.error" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800" role="alert">
        {{ store.error }}
      </div>
      <div v-if="downloadError" class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800" role="alert">
        {{ downloadError }}
      </div>

      <div class="w-72 mb-4">
        <TextInput
          id="admin-studies-search"
          v-model="query"
          type="search"
          inputmode="search"
          :placeholder="t('adminStudies.searchPlaceholder')"
        />
      </div>

      <DenseTable>
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminStudies.column.name') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminStudies.column.uniqueIdentifier') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminStudies.column.oid') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminStudies.column.principalInvestigator') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('adminStudies.column.created') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('adminStudies.column.status') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right"><span class="sr-only">{{ t('common.actions') }}</span></th>
          </tr>
        </template>

        <tr v-if="store.isLoading">
          <td :colspan="7" class="px-3 py-6 text-center text-slate-500 italic">{{ t('common.loading') }}</td>
        </tr>
        <tr v-else-if="rows.length === 0">
          <td :colspan="7" class="px-3 py-6 text-center text-slate-500">{{ t('adminStudies.empty') }}</td>
        </tr>

        <template v-else>
        <tr
          v-for="row in rows"
          :key="row.study.oid"
          :data-testid="`admin-study-${row.study.oid}`"
          :class="row.parent ? 'text-slate-600' : ''"
        >
          <td class="px-3 py-2">
            <span v-if="row.parent" class="pl-5 inline-flex items-center gap-1.5">
              <span aria-hidden="true" class="text-slate-400">↳</span>
              <span>{{ row.study.name }}</span>
              <StatusPill compact variant="neutral">{{ t('studyPicker.siteBadge') }}</StatusPill>
            </span>
            <span v-else class="font-medium text-slate-900">{{ row.study.name }}</span>
          </td>
          <td class="px-3 py-2 font-mono text-xs">{{ row.study.uniqueIdentifier ?? '' }}</td>
          <td class="px-3 py-2 font-mono text-xs">{{ row.study.oid }}</td>
          <td class="px-3 py-2">{{ row.study.principalInvestigator ?? '' }}</td>
          <td class="px-3 py-2 font-mono text-xs">{{ formatDate(row.study.createdDate) }}</td>
          <td class="px-3 py-2">
            <StatusPill :variant="statusVariant(row.study.status)" :data-testid="`status-${row.study.oid}`">
              {{ t(`adminStudies.status.${row.study.status}`) }}
            </StatusPill>
          </td>
          <td class="px-3 py-2 text-right">
            <div class="inline-flex items-center gap-2 text-xs whitespace-nowrap">
              <button
                type="button"
                class="text-muw-blue hover:underline disabled:opacity-50"
                :disabled="downloading === row.study.oid"
                :data-testid="`metadata-${row.study.oid}`"
                @click="download(row.study)"
              >
                {{ t('adminStudies.metadata') }}
              </button>
              <template v-if="!row.parent">
                <span class="text-slate-400">·</span>
                <button
                  v-if="isRemoved(row.study)"
                  type="button"
                  class="text-emerald-700 hover:underline"
                  :data-testid="`restore-${row.study.oid}`"
                  @click="openLifecycle(row.study, 'restore')"
                >
                  {{ t('adminStudies.restore') }}
                </button>
                <button
                  v-else
                  type="button"
                  class="text-rose-600 hover:underline"
                  :data-testid="`remove-${row.study.oid}`"
                  @click="openLifecycle(row.study, 'remove')"
                >
                  {{ t('adminStudies.remove') }}
                </button>
              </template>
            </div>
          </td>
        </tr>
        </template>
      </DenseTable>

      <p class="text-xs text-slate-500 mt-3">{{ t('adminStudies.sitesNote') }}</p>
    </div>

    <StudyLifecycleDialog v-model:open="dialogOpen" :study="dialogStudy" :action="dialogAction" />
  </div>
</template>

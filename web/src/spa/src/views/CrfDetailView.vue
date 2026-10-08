<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'

import BuildStudyRail from '@/components/BuildStudyRail.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useCrfLibraryStore } from '@/stores/crfLibrary'
import type { CrfDetail, CrfItemRow } from '@/types/crfLibrary'

/**
 * The CRF view — what the legacy ViewCRF page (`/ViewCRF?crfId=`) showed:
 * the CRF's metadata and versions, its item table with the integrity check
 * (an item must keep one item group across versions), and the studies whose
 * event definitions use it. Read-only: the actions stay on the library list.
 * "Run all rules for this CRF" is not carried over.
 */
const { t } = useI18n()
const route = useRoute()
const lib = useCrfLibraryStore()

const crfOid = computed(() => String(route.params.crfOid ?? ''))
const detail = ref<CrfDetail | null>(null)
const loadError = ref<string | null>(null)
const isLoading = ref(false)

async function load(): Promise<void> {
  isLoading.value = true
  loadError.value = null
  try {
    const result = await lib.fetchCrfDetail(crfOid.value)
    if (result.ok) detail.value = result.detail
    else loadError.value = result.message
  } finally {
    isLoading.value = false
  }
}

onMounted(load)

const flaggedItems = computed(() => detail.value?.items.filter((i) => i.integrity !== 'ok').length ?? 0)

function integrityVariant(item: CrfItemRow): 'success' | 'warning' | 'danger' {
  if (item.integrity === 'problem') return 'danger'
  if (item.integrity === 'warning') return 'warning'
  return 'success'
}

function statusLabel(status: string): string {
  switch (status) {
    case 'removed':
      return t('crfLibrary.statusRemoved')
    case 'auto-removed':
      return t('crfLibrary.statusAutoRemoved')
    case 'locked':
      return t('crfLibrary.lock')
    default:
      return status
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

      <p v-if="isLoading" class="mt-4 text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="loadError" class="mt-4 text-rose-700" role="alert" data-testid="crf-detail-error">{{ loadError }}</p>

      <template v-else-if="detail">
        <div class="mt-3 mb-5">
          <div class="flex items-baseline gap-2">
            <h1 class="text-xl font-semibold tracking-tight">{{ detail.name }}</h1>
            <span class="text-xs text-slate-400 font-mono">{{ detail.oid }}</span>
            <StatusPill v-if="detail.status !== 'available'" variant="neutral">{{ statusLabel(detail.status) }}</StatusPill>
          </div>
          <p v-if="detail.description" class="text-sm text-slate-600 mt-1">{{ detail.description }}</p>
        </div>

        <!-- Versions -->
        <section class="mb-6">
          <h2 class="text-sm font-semibold mb-2">{{ t('crfLibrary.versions') }}</h2>
          <table class="w-full text-xs border border-slate-200 rounded-md bg-white">
            <thead class="bg-slate-50 text-slate-500 text-left">
              <tr>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.version') }}</th>
                <th class="px-3 py-2 font-medium">OID</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.description') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.revisionNotes') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.status') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in detail.versions" :key="v.oid" class="border-t border-slate-100">
                <td class="px-3 py-2 font-mono">{{ v.name }}</td>
                <td class="px-3 py-2 text-slate-500 font-mono">{{ v.oid }}</td>
                <td class="px-3 py-2">{{ v.description }}</td>
                <td class="px-3 py-2">{{ v.revisionNotes }}</td>
                <td class="px-3 py-2">{{ statusLabel(v.status) }}</td>
              </tr>
            </tbody>
          </table>
        </section>

        <!-- Items -->
        <section class="mb-6">
          <div class="flex items-baseline justify-between mb-2">
            <h2 class="text-sm font-semibold">{{ t('crfLibrary.detail.itemsHeading', { count: detail.items.length }) }}</h2>
            <span
              v-if="flaggedItems > 0"
              class="text-xs text-amber-800"
              data-testid="crf-detail-integrity-summary"
            >{{ t('crfLibrary.detail.integritySummary', { count: flaggedItems }) }}</span>
          </div>
          <p class="text-[11px] text-slate-500 mb-2">{{ t('crfLibrary.detail.integrityHint') }}</p>
          <p v-if="detail.items.length === 0" class="text-xs text-slate-500 italic">{{ t('crfLibrary.detail.noItems') }}</p>
          <table v-else class="w-full text-xs border border-slate-200 rounded-md bg-white" data-testid="crf-detail-items">
            <thead class="bg-slate-50 text-slate-500 text-left">
              <tr>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.name') }}</th>
                <th class="px-3 py-2 font-medium">OID</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.description') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.dataType') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.versions') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.integrity') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in detail.items"
                :key="item.oid"
                class="border-t border-slate-100 align-top"
                :data-testid="`crf-detail-item-${item.oid}`"
              >
                <td class="px-3 py-2 font-mono">{{ item.name }}</td>
                <td class="px-3 py-2 text-slate-500 font-mono">{{ item.oid }}</td>
                <td class="px-3 py-2">{{ item.description }}</td>
                <td class="px-3 py-2 font-mono">{{ item.dataType }}</td>
                <td class="px-3 py-2">{{ item.versions.join(', ') }}</td>
                <td class="px-3 py-2">
                  <StatusPill :variant="integrityVariant(item)">{{ t(`crfLibrary.detail.integrity.${item.integrity}`) }}</StatusPill>
                  <ul v-if="item.placements.length > 0" class="mt-1 space-y-0.5 text-[11px] text-slate-600">
                    <li v-for="(p, i) in item.placements" :key="i">
                      {{ t('crfLibrary.detail.placement', { group: p.groupLabel || '—', version: p.versionName }) }}
                    </li>
                  </ul>
                </td>
              </tr>
            </tbody>
          </table>
        </section>

        <!-- Studies using the CRF -->
        <section>
          <h2 class="text-sm font-semibold mb-2">{{ t('crfLibrary.detail.studiesHeading') }}</h2>
          <p v-if="detail.studies.length === 0" class="text-xs text-slate-500 italic">{{ t('crfLibrary.detail.noStudies') }}</p>
          <table v-else class="w-full text-xs border border-slate-200 rounded-md bg-white" data-testid="crf-detail-studies">
            <thead class="bg-slate-50 text-slate-500 text-left">
              <tr>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.study') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.protocolId') }}</th>
                <th class="px-3 py-2 font-medium">OID</th>
                <th class="px-3 py-2 font-medium">{{ t('crfLibrary.detail.col.status') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in detail.studies" :key="s.oid" class="border-t border-slate-100">
                <td class="px-3 py-2">
                  {{ s.name }}
                  <span v-if="s.parentName" class="text-slate-400">· {{ t('crfLibrary.detail.siteOf', { study: s.parentName }) }}</span>
                </td>
                <td class="px-3 py-2">{{ s.uniqueProtocolId }}</td>
                <td class="px-3 py-2 text-slate-500 font-mono">{{ s.oid }}</td>
                <td class="px-3 py-2">{{ s.status }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>
    </div>
  </div>
</template>

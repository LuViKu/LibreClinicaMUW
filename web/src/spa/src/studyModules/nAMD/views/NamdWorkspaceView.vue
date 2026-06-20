<script setup lang="ts">
/**
 * nAMD treat-and-extend — workspace top-level view.
 *
 * Composes the four-tab workspace: header + patient banner + tab strip +
 * active tab body. Wired from {@code studyModules/nAMD/index.ts} as the
 * module's single route ({@code /studies/:studyOid/modules/namd}).
 *
 * Tab state is component-local; persisting it across reloads is out of
 * scope for v1 — the workspace is a per-session triage surface, not a
 * deep-link target.
 *
 * Data flow:
 *   1. Resolve {@code studySubjectOid} from the route's {@code subjectOid}
 *      query (the SubjectDetail CTA passes it) or fall through to mock
 *      data when {@code ?mock=1} is present (useful when no real subject
 *      has retinal jobs yet).
 *   2. {@link useNamdVisitData} assembles the typed shape from existing
 *      endpoints + emits a reactive ref.
 *   3. Render the active tab body lazily via {@code Suspense} so the
 *      heavy Compare / Viewer bundles don't ship to the Overview tab.
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import NamdHeader from '../components/NamdHeader.vue'
import NamdPatientBanner from '../components/NamdPatientBanner.vue'
import NamdTabs, { type NamdTabId } from '../components/NamdTabs.vue'
import NamdOverviewTab from './NamdOverviewTab.vue'
import NamdViewerTab from './NamdViewerTab.vue'
import NamdCompareTab from './NamdCompareTab.vue'
import NamdReportTab from './NamdReportTab.vue'
import { useNamdVisitData } from '../composables/useNamdVisitData'

const route = useRoute()
const auth = useAuthStore()
const { t } = useI18n()

const tab = ref<NamdTabId>('overview')

const studySubjectOid = computed(() => {
  const v = route.query.subjectOid
  return typeof v === 'string' && v.length > 0 ? v : null
})

const isMock = computed(() => route.query.mock === '1')

const { data, loading, error } = useNamdVisitData({
  studySubjectOid,
  mock: isMock,
})

const studyLabel = computed(() => {
  const s = auth.user?.activeStudy
  if (!s) return ''
  return s.name ?? ''
})

const userLabel = computed(() => auth.user?.displayName ?? auth.user?.username ?? '')
</script>

<template>
  <div data-testid="namd-workspace-view" class="min-h-screen bg-slate-50">
    <NamdHeader :study-label="studyLabel" :user-label="userLabel" />

    <NamdPatientBanner
      v-if="data"
      :patient="data.patient"
      :current="data.current"
      :prev="data.prev"
    />

    <NamdTabs v-model="tab" />

    <main
      data-testid="namd-workspace-main"
      class="max-w-[1240px] mx-auto px-6 py-6 print:px-0 print:py-0"
    >
      <div
        v-if="loading"
        data-testid="namd-workspace-loading"
        class="rounded-muw bg-white border border-slate-200 p-6 text-sm text-slate-500"
      >
        {{ t('studyModules.namd.loading') }}
      </div>
      <div
        v-else-if="error"
        data-testid="namd-workspace-error"
        class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800"
      >
        {{ t('studyModules.namd.errorPrefix') }} {{ error }}
      </div>
      <template v-else-if="data">
        <NamdOverviewTab v-if="tab === 'overview'" :data="data" />
        <NamdViewerTab v-else-if="tab === 'viewer'" :data="data" />
        <NamdCompareTab v-else-if="tab === 'compare'" :data="data" />
        <NamdReportTab v-else-if="tab === 'report'" :data="data" />
      </template>
    </main>
  </div>
</template>

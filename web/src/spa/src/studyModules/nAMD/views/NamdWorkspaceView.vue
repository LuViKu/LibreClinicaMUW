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
import NamdPatientBanner from '../components/NamdPatientBanner.vue'
import NamdTabs, { type NamdTabId } from '../components/NamdTabs.vue'
import NamdOverviewTab from './NamdOverviewTab.vue'
import NamdViewerTab from './NamdViewerTab.vue'
import NamdCompareTab from './NamdCompareTab.vue'
import NamdReportTab from './NamdReportTab.vue'
import { useNamdVisitData } from '../composables/useNamdVisitData'
import { useViewBreadcrumb } from '@/composables/useViewBreadcrumb'

const route = useRoute()
const { t } = useI18n()

const tab = ref<NamdTabId>('overview')

const studySubjectOid = computed(() => {
  const v = route.query.subjectOid
  return typeof v === 'string' && v.length > 0 ? v : null
})

/**
 * 2026-06-24 user-feedback round — subjectLabel is the site-scoped
 * patient ID (e.g. "EIAMD150"). The CTA on SubjectDetail forwards it
 * via the route query alongside the numeric subjectOid; without it
 * the workspace banner reads "Patient 106" because the composable
 * falls back to the numeric oid for display. Empty / missing label
 * → the composable's existing fallback kicks in.
 */
const studySubjectLabel = computed(() => {
  const v = route.query.subjectLabel
  return typeof v === 'string' && v.length > 0 ? v : null
})

const isMock = computed(() => route.query.mock === '1')

const { data, loading, error, availableEyes, selectedEye, setEye, refresh } = useNamdVisitData({
  studySubjectOid,
  studySubjectLabel,
  mock: isMock,
})

// 2026-06-23 user-feedback round — nested breadcrumb trail:
// "<study> > Studienteilnehmer > <subject> > nAMD".
useViewBreadcrumb(computed(() => {
  const subjLabel = studySubjectLabel.value ?? data.value?.patient.id ?? studySubjectOid.value
  if (!subjLabel) return null
  return [
    { label: t('nav.subjectMatrix'), to: '/subjects' },
    { label: subjLabel, to: `/subjects/${encodeURIComponent(subjLabel)}` },
    { label: t('studyModules.namd.workspaceBreadcrumb'), to: null },
  ]
}))
</script>

<template>
  <div data-testid="namd-workspace-view" class="min-h-screen bg-slate-50">
    <!-- The shared TopBar handles study breadcrumb + workspace nav entry.
         The workspace view starts at the patient banner. -->
    <NamdPatientBanner
      v-if="data"
      :patient="data.patient"
      :current="data.current"
      :prev="data.prev"
      :available-eyes="availableEyes"
      :selected-eye="selectedEye"
      @switch-eye="setEye"
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
      <template v-else-if="data && data.visits.length > 0">
        <NamdOverviewTab
          v-if="tab === 'overview'"
          :data="data"
          :selected-eye="selectedEye"
          @refresh="refresh"
        />
        <NamdViewerTab v-else-if="tab === 'viewer'" :data="data" />
        <NamdCompareTab v-else-if="tab === 'compare'" :data="data" />
        <NamdReportTab v-else-if="tab === 'report'" :data="data" />
      </template>
      <!-- Empty state — subject has no inference jobs yet; no silent mock fallback. -->
      <div
        v-else
        data-testid="namd-workspace-empty"
        class="rounded-muw bg-white border border-dashed border-slate-300 px-6 py-10 text-center"
      >
        <div class="text-base font-semibold text-slate-700">
          {{ t('studyModules.namd.empty.title') }}
        </div>
        <p class="text-xs text-slate-500 mt-2 max-w-md mx-auto leading-relaxed">
          {{ t('studyModules.namd.empty.hint') }}
        </p>
      </div>
    </main>
  </div>
</template>

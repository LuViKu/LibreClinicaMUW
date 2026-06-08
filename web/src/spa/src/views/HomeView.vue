<script setup lang="ts">
/**
 * Phase E.6 (2026-06-03) — role-aware landing.
 *
 * Replaces the Phase E.4 mock-era static catalog (10 hardcoded cards
 * pointing at demo subjects + demo CRFs) with per-role landings driven
 * by the session's projected SPA role.
 *
 * <p>Visibility:
 * <ul>
 *   <li>Investigator (incl. CRC) — subject-centred workflows</li>
 *   <li>Monitor                  — SDV + queries + audit</li>
 *   <li>Data Manager             — study build + user admin + import</li>
 *   <li>Administrator            — DM cards + study/site/event-definition admin</li>
 * </ul>
 *
 * <p>Counts are eager-loaded from existing list endpoints on mount via
 * {@code Promise.allSettled} — no new C-category endpoints, no
 * duplicate fetches (navigation into the underlying view reuses the
 * already-loaded store state).
 *
 * <p>Source-of-truth for the role taxonomy: {@link AuthenticatedUser.role}
 * (post-PR-#96 the sysadmin/techadmin projection always lands at
 * "Administrator"; the legacy {@code admin} role-row also projects there
 * via {@code RoleMapper.toSpaRole}).
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import LandingCard from '@/components/LandingCard.vue'
import { useAuthStore } from '@/stores/auth'
import { useSdvStore } from '@/stores/sdv'
import { useNotesStore } from '@/stores/notes'
import { useUsersStore } from '@/stores/users'
import { useRulesStore } from '@/stores/rules'

const { t } = useI18n()
const auth = useAuthStore()
const sdv = useSdvStore()
const notes = useNotesStore()
const users = useUsersStore()
const rules = useRulesStore()

/**
 * The SPA UserRole taxonomy is 5 wide ('Investigator' | 'Monitor' |
 * 'Data Manager' | 'Administrator' | 'CRC'). CRC inherits the
 * Investigator landing in v1 — it's a thin variant of Investigator and
 * a dedicated landing is a separate slice after operator feedback.
 *
 * Phase E.6 (2026-06-03): Administrator is no longer a Data Manager
 * superset. Per operator feedback the Admin landing surfaces only
 * platform-administration paths (Manage Users, Study identity,
 * Sites, Audit log). Users needing data-management workflows sign
 * in under a Data Manager binding.
 */
const role = computed(() => auth.user?.role ?? null)
const showInvestigator = computed(() => role.value === 'Investigator' || role.value === 'CRC')
const showMonitor = computed(() => role.value === 'Monitor')
const showDataManager = computed(() => role.value === 'Data Manager')
const showAdministrator = computed(() => role.value === 'Administrator')

/**
 * Phase E.6 — surface a "Switch active study" card once the user has
 * access to more than one study. Lazy-loads availableStudies via the
 * existing /me/studies endpoint; failure modes (offline, single-study
 * user) collapse to hiding the card.
 */
const canSwitchStudy = computed(
  () => (auth.availableStudies?.length ?? 0) > 1,
)

/**
 * Cross-study cards (Heutige offene CRFs, Patientenübersicht) share a
 * single section above the role-specific landings. Because the section
 * is role-agnostic but LandingCard still expects a role variant for
 * the chip color, derive the operator's dominant role variant from
 * the existing show* flags.
 */
const primaryRoleVariant = computed<
  'investigator' | 'monitor' | 'data-manager' | 'administrator'
>(() =>
  showAdministrator.value
    ? 'administrator'
    : showDataManager.value
    ? 'data-manager'
    : showMonitor.value
    ? 'monitor'
    : 'investigator',
)

/* ---------- count derivations from already-loaded store state ---------- */
//
// All store .rows arrays are populated by the eager onMounted load. We
// derive badges from them. Each computed is null until the load has
// resolved (LandingCard hides null badges); after that it reflects the
// current snapshot. If the user navigates away + back, the store
// state survives and the counts stay live without re-fetching.

const sdvPendingCount = computed<number | null>(() => {
  if (!sdv.rows) return null
  // SDV "pending" rows are those still awaiting verification.
  return sdv.rows.filter((r) => r.status === 'pending').length
})

const openQueriesCount = computed<number | null>(() => {
  if (!notes.rows) return null
  // Open discrepancies per NoteStatus union: anything that's not
  // 'closed' (resolved + dispositioned) and not 'not-applicable'
  // (operator-marked-noise).
  return notes.rows.filter((r) => r.status !== 'closed' && r.status !== 'not-applicable').length
})

// Investigator-only "queries assigned to me" derivation. The store
// load was issued with assignedTo=current-username, so notes.rows is
// already server-narrowed to the right scope — we just collapse the
// open/closed dimension on top.
const openQueriesAssignedToMeCount = computed<number | null>(() => {
  if (!notes.rows) return null
  const me = auth.user?.username
  if (!me) return null
  return notes.rows.filter(
    (r) =>
      r.assignedTo === me &&
      r.status !== 'closed' &&
      r.status !== 'not-applicable',
  ).length
})

const pendingInvitesCount = computed<number | null>(() => {
  if (!users.rows) return null
  // Pending invites are users with .auth='pending-invite' per the
  // ManageUsersView convention (also surfaces a top-of-page chip there).
  return users.rows.filter((u) => u.auth === 'pending-invite').length
})

const activeRuleSetsCount = computed<number | null>(() => {
  if (!rules.rows) return null
  return rules.rows.filter((rs) => rs.status === 'available').length
})

/* ---------- eager load on mount ---------- */
onMounted(() => {
  // Kick everything off in parallel; tolerate per-action failures so
  // one transient backend hiccup doesn't cascade-blank the whole
  // landing. The shape is identical to a sequential await chain but
  // with no head-of-line blocking.
  const inflight: Array<Promise<unknown>> = []
  if (showMonitor.value || showDataManager.value) {
    inflight.push(sdv.load())
    inflight.push(rules.load())
  }
  // Notes is cross-role (Monitor + DM + Administrator all surface it).
  if (showMonitor.value || showDataManager.value || showAdministrator.value) {
    inflight.push(notes.load())
  }
  // Investigator surfaces only the queries assigned to them; ask the
  // server to narrow before the response ships.
  if (showInvestigator.value && auth.user?.username) {
    inflight.push(notes.load({ assignedTo: auth.user.username }))
  }
  if (showAdministrator.value || showDataManager.value) {
    inflight.push(users.load())
  }
  // Always populate availableStudies for the multi-study switch card.
  inflight.push(auth.loadStudies())
  // Promise.allSettled never rejects — fire and forget.
  void Promise.allSettled(inflight)
})

const activeStudyOid = computed(() => auth.user?.activeStudy?.oid ?? '')
const activeStudyName = computed(() => auth.user?.activeStudy?.name ?? '')
</script>

<template>
  <div class="max-w-6xl mx-auto px-6 py-14">
    <div class="flex items-center gap-2 text-[11px] font-medium uppercase tracking-[0.14em] text-muw-coral-700 mb-4">
      <span class="muw-rule"></span>
      <span>{{ t('home.eyebrow') }}</span>
    </div>
    <h1 class="muw-display text-5xl font-medium text-muw-blue leading-[1.05] mb-5">
      {{ t('app.name') }}
    </h1>
    <p class="text-slate-600 text-base leading-relaxed max-w-2xl mb-10">
      {{ t('app.tagline') }}
    </p>

    <!-- Universal "general" section (study-independent actions) shown
         once at the top, mirroring the Administrator's
         platform-wide / study-scoped split. Followed by a divider
         carrying the active study's name + then the role-specific
         landings. Today's open CRFs and Patientenübersicht render
         here for every role (they don't depend on the bound study).
         Administrator additionally gets Manage Users / Create Study
         / Modalities here so they don't need a separate top section. -->
    <template v-if="role">
      <section
        :aria-label="t('home.generalSectionLabel')"
        class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-6"
      >
        <LandingCard
          :to="{ name: 'subject-matrix', query: { filter: 'today' } }"
          :role-variant="primaryRoleVariant"
          :role-label="t('home.crossStudy.cardRoleLabel')"
          :title="t('home.crossStudy.todaysCrfsTitle')"
          :description="t('home.crossStudy.todaysCrfsDesc')"
        />
        <LandingCard
          :to="{ name: 'patients-overview' }"
          :role-variant="primaryRoleVariant"
          :role-label="t('home.crossStudy.cardRoleLabel')"
          :title="t('home.cards.patientsOverview.title')"
          :description="t('home.cards.patientsOverview.description')"
        />
        <!-- Administrator additions: platform-wide user / study
             provisioning + the modality catalogue. Kept in this
             general section instead of a separate admin-only one. -->
        <template v-if="showAdministrator">
          <LandingCard
            :to="{ name: 'manage-users' }"
            role-variant="administrator"
            :role-label="t('home.role.Administrator')"
            :title="t('manageUsers.title')"
            :description="t('home.administrator.manageUsersDesc')"
            :badge="pendingInvitesCount"
            :badge-aria-label="t('home.administrator.pendingInvitesBadgeAria', { n: pendingInvitesCount ?? 0 })"
          />
          <LandingCard
            :to="{ name: 'study-create' }"
            role-variant="administrator"
            :role-label="t('home.role.Administrator')"
            :title="t('home.administrator.createStudyTitle')"
            :description="t('home.administrator.createStudyDesc')"
          />
          <LandingCard
            :to="{ name: 'modalities' }"
            role-variant="administrator"
            :role-label="t('home.role.Administrator')"
            :title="t('modalities.title')"
            :description="t('home.administrator.modalitiesDesc')"
          />
        </template>
        <!-- Switch-study card surfaces for every role with >1 grant. -->
        <LandingCard
          v-if="canSwitchStudy"
          :to="{ name: 'pick-study' }"
          :role-variant="primaryRoleVariant"
          :role-label="t('home.crossStudy.cardRoleLabel')"
          :title="t('home.switchStudyTitle')"
          :description="t('home.switchStudyDesc')"
        />
      </section>

      <!-- Divider with active study name — same copy as the admin
           layout already used, lifted to apply to every role. -->
      <div class="max-w-5xl mb-6 flex items-center gap-3">
        <hr class="flex-1 border-t border-slate-200" />
        <span class="text-[11px] uppercase tracking-[0.14em] text-slate-500 whitespace-nowrap">
          {{ t('home.administrator.studyScopedLabel', { study: activeStudyName }) }}
        </span>
        <hr class="flex-1 border-t border-slate-200" />
      </div>
    </template>

    <!-- Investigator + CRC landing -->
    <section v-if="showInvestigator" :aria-label="t('home.investigator.sectionLabel')" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-8">
      <LandingCard
        :to="{ name: 'subject-matrix' }"
        role-variant="investigator"
        :role-label="t('home.role.Investigator')"
        :title="t('subjectMatrix.title')"
        :description="t('home.investigator.subjectMatrixDesc')"
      />
      <LandingCard
        :to="{ name: 'subject-new' }"
        role-variant="investigator"
        :role-label="t('home.role.Investigator')"
        :title="t('addSubject.title')"
        :description="t('home.investigator.addSubjectDesc')"
      />
      <LandingCard
        :to="{ name: 'subject-matrix', query: { filter: 'ready-to-sign' } }"
        role-variant="investigator"
        :role-label="t('home.role.Investigator')"
        :title="t('home.investigator.signQueueTitle')"
        :description="t('home.investigator.signQueueDesc')"
      />
      <LandingCard
        :to="{ name: 'notes', query: { assignedTo: auth.user?.username ?? '' } }"
        role-variant="investigator"
        :role-label="t('home.role.Investigator')"
        :title="t('home.investigator.openQueriesTitle')"
        :description="t('home.investigator.openQueriesDesc')"
        :badge="openQueriesAssignedToMeCount"
      />
    </section>

    <!-- Monitor landing -->
    <section v-if="showMonitor" :aria-label="t('home.monitor.sectionLabel')" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-8">
      <LandingCard
        :to="{ name: 'subject-matrix' }"
        role-variant="monitor"
        :role-label="t('home.role.Monitor')"
        :title="t('subjectMatrix.title')"
        :description="t('home.monitor.subjectMatrixDesc')"
      />
      <LandingCard
        :to="{ name: 'sdv' }"
        role-variant="monitor"
        :role-label="t('home.role.Monitor')"
        :title="t('sdv.title')"
        :description="t('home.monitor.sdvDesc')"
        :badge="sdvPendingCount"
        :badge-aria-label="t('home.monitor.sdvBadgeAria', { n: sdvPendingCount ?? 0 })"
      />
      <LandingCard
        :to="{ name: 'notes' }"
        role-variant="monitor"
        :role-label="t('home.role.Monitor')"
        :title="t('notes.title')"
        :description="t('home.monitor.openQueriesDesc')"
        :badge="openQueriesCount"
        :badge-aria-label="t('home.monitor.openQueriesBadgeAria', { n: openQueriesCount ?? 0 })"
      />
      <LandingCard
        :to="{ name: 'audit-log' }"
        role-variant="monitor"
        :role-label="t('home.role.Monitor')"
        :title="t('auditLog.title')"
        :description="t('home.monitor.auditLogDesc')"
      />
    </section>

    <!-- Data Manager landing — strict role (Administrator no longer inherits). -->
    <section v-if="showDataManager" :aria-label="t('home.dataManager.sectionLabel')" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-8">
      <LandingCard
        :to="{ name: 'build-study' }"
        role-variant="data-manager"
        :role-label="t('home.role.Data Manager')"
        :title="t('buildStudy.title')"
        :description="t('home.dataManager.buildStudyDesc')"
      />
      <LandingCard
        :to="{ name: 'notes' }"
        role-variant="data-manager"
        :role-label="t('home.role.Data Manager')"
        :title="t('notes.title')"
        :description="t('home.dataManager.openQueriesDesc')"
        :badge="openQueriesCount"
      />
      <LandingCard
        :to="{ name: 'audit-log' }"
        role-variant="data-manager"
        :role-label="t('home.role.Data Manager')"
        :title="t('auditLog.title')"
        :description="t('home.dataManager.auditLogDesc')"
      />
      <LandingCard
        :to="{ name: 'import-crf-data' }"
        role-variant="data-manager"
        :role-label="t('home.role.Data Manager')"
        :title="t('importCrf.title')"
        :description="t('home.dataManager.importCrfDesc')"
      />
      <LandingCard
        :to="{ name: 'rules' }"
        role-variant="data-manager"
        :role-label="t('home.role.Data Manager')"
        :title="t('rules.title')"
        :description="t('home.dataManager.rulesDesc')"
        :badge="activeRuleSetsCount"
      />
      <LandingCard
        :to="{ name: 'data-export' }"
        role-variant="data-manager"
        :role-label="t('home.role.Data Manager')"
        :title="t('home.dataManager.dataExportTitle')"
        :description="t('home.dataManager.dataExportDesc')"
      />
    </section>

    <!-- Administrator study-scoped landing. Platform-wide cards
         (Manage Users, Create Study, Modalities, Switch Study) now
         live in the universal general section above the divider, so
         only the study-scoped actions render here. -->
    <template v-if="showAdministrator">
      <section
        :aria-label="t('home.administrator.sectionLabel')"
        class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-8"
      >
        <LandingCard
          v-if="activeStudyOid"
          :to="{ name: 'study-edit', params: { oid: activeStudyOid } }"
          role-variant="administrator"
          :role-label="t('home.role.Administrator')"
          :title="t('home.administrator.editStudyTitle')"
          :description="t('home.administrator.editStudyDesc')"
        />
        <LandingCard
          :to="{ name: 'sites' }"
          role-variant="administrator"
          :role-label="t('home.role.Administrator')"
          :title="t('home.administrator.sitesTitle')"
          :description="t('home.administrator.sitesDesc')"
        />
        <LandingCard
          :to="{ name: 'audit-log' }"
          role-variant="administrator"
          :role-label="t('home.role.Administrator')"
          :title="t('auditLog.title')"
          :description="t('home.administrator.auditLogDesc')"
        />
        <LandingCard
          :to="{ name: 'notes' }"
          role-variant="administrator"
          :role-label="t('home.role.Administrator')"
          :title="t('notes.title')"
          :description="t('home.dataManager.openQueriesDesc')"
          :badge="openQueriesCount"
        />
        <LandingCard
          :to="{ name: 'data-export' }"
          role-variant="administrator"
          :role-label="t('home.role.Administrator')"
          :title="t('home.administrator.dataExportTitle')"
          :description="t('home.administrator.dataExportDesc')"
        />
      </section>
    </template>

    <!-- Switch-study card now lives in the universal general section
         above (rendered when canSwitchStudy is true for any role), so
         no separate bottom block is needed. -->

    <!-- Fallback when role hasn't loaded yet (auth.bootstrap() in flight). -->
    <p v-if="!role" class="text-slate-500 text-sm italic">
      {{ t('common.loading') }}
    </p>
  </div>
</template>

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
  if (showAdministrator.value || showDataManager.value) {
    inflight.push(users.load())
  }
  // Always populate availableStudies for the multi-study switch card.
  inflight.push(auth.loadStudies())
  // Promise.allSettled never rejects — fire and forget.
  void Promise.allSettled(inflight)
})

const activeStudyOid = computed(() => auth.user?.activeStudy?.oid ?? '')
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

    <!-- Investigator + CRC landing -->
    <section v-if="showInvestigator" :aria-label="t('home.investigator.sectionLabel')" class="grid grid-cols-1 md:grid-cols-2 gap-4 max-w-3xl mb-8">
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
        :to="{ name: 'subject-matrix', query: { filter: 'today' } }"
        role-variant="investigator"
        :role-label="t('home.role.Investigator')"
        :title="t('home.investigator.todaysCrfsTitle')"
        :description="t('home.investigator.todaysCrfsDesc')"
      />
    </section>

    <!-- Monitor landing -->
    <section v-if="showMonitor" :aria-label="t('home.monitor.sectionLabel')" class="grid grid-cols-1 md:grid-cols-2 gap-4 max-w-3xl mb-8">
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
        :to="{ name: 'manage-users' }"
        role-variant="data-manager"
        :role-label="t('home.role.Data Manager')"
        :title="t('manageUsers.title')"
        :description="t('home.dataManager.manageUsersDesc')"
        :badge="pendingInvitesCount"
        :badge-aria-label="t('home.dataManager.pendingInvitesBadgeAria', { n: pendingInvitesCount ?? 0 })"
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
    </section>

    <!-- Administrator-only landing (platform admin: users, study identity, sites, audit log). -->
    <section v-if="showAdministrator" :aria-label="t('home.administrator.sectionLabel')" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-8">
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
        v-if="activeStudyOid"
        :to="{ name: 'study-edit', params: { oid: activeStudyOid } }"
        role-variant="administrator"
        :role-label="t('home.role.Administrator')"
        :title="t('home.administrator.editStudyTitle')"
        :description="t('home.administrator.editStudyDesc')"
      />
      <LandingCard
        :to="{ name: 'study-create' }"
        role-variant="administrator"
        :role-label="t('home.role.Administrator')"
        :title="t('home.administrator.createStudyTitle')"
        :description="t('home.administrator.createStudyDesc')"
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
      <!-- Phase E.6: Notes/queries is cross-role and Admin can legitimately
           review pending queries during compliance/incident investigations. -->
      <LandingCard
        :to="{ name: 'notes' }"
        role-variant="administrator"
        :role-label="t('home.role.Administrator')"
        :title="t('notes.title')"
        :description="t('home.dataManager.openQueriesDesc')"
        :badge="openQueriesCount"
      />
      <LandingCard
        v-if="canSwitchStudy"
        :to="{ name: 'pick-study' }"
        role-variant="administrator"
        :role-label="t('home.role.Administrator')"
        :title="t('home.switchStudyTitle')"
        :description="t('home.switchStudyDesc')"
      />
    </section>

    <!-- Switch-study card (per-role landings other than Administrator). -->
    <section v-if="canSwitchStudy && !showAdministrator" :aria-label="t('home.switchStudySectionLabel')" class="max-w-3xl mb-8">
      <LandingCard
        :to="{ name: 'pick-study' }"
        :role-variant="role === 'Monitor' ? 'monitor' : role === 'Data Manager' ? 'data-manager' : 'investigator'"
        :role-label="role ? t('home.role.' + role) : ''"
        :title="t('home.switchStudyTitle')"
        :description="t('home.switchStudyDesc')"
      />
    </section>

    <!-- Fallback when role hasn't loaded yet (auth.bootstrap() in flight). -->
    <p v-if="!role" class="text-slate-500 text-sm italic">
      {{ t('common.loading') }}
    </p>
  </div>
</template>

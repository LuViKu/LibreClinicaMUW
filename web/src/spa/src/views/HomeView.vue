<script setup lang="ts">
/**
 * Phase E.6 (2026-06-03) — role-aware landing.
 *
 * Multi-role per (user, study) — M2 (2026-06-08): the four per-role
 * sections that previously duplicated cards by role have been
 * collapsed into a single de-duplicated catalogue. Each card carries
 * an explicit {@code allowedRoles} list; visibility is the
 * intersection of {@code allowedRoles} with the user's active-study
 * binding. When a card matches more than one of the user's roles its
 * {@link RoleDots} surface the full set, so a Monitor + Data Manager
 * operator sees the Notes card once with both dots stacked in the
 * header.
 *
 * <p>Visibility:
 * <ul>
 *   <li>Investigator (incl. CRC) — subject-centred workflows</li>
 *   <li>Monitor                  — SDV + queries + audit</li>
 *   <li>Data Manager             — study build + user admin + import</li>
 *   <li>Administrator            — platform admin + study identity</li>
 * </ul>
 *
 * <p>Counts are eager-loaded from existing list endpoints on mount via
 * {@code Promise.allSettled} — no new C-category endpoints, no
 * duplicate fetches (navigation into the underlying view reuses the
 * already-loaded store state).
 *
 * <p>Source-of-truth for the role set: {@code AuthenticatedUser.activeStudy.roles}
 * (M2+) with a fallback chain to {@code activeStudy.role} →
 * top-level {@code user.role} for the M1 wire shape.
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import LandingCard, { type RoleVariant } from '@/components/LandingCard.vue'
import { useAuthStore } from '@/stores/auth'
import { useSdvStore } from '@/stores/sdv'
import { useNotesStore } from '@/stores/notes'
import { useUsersStore } from '@/stores/users'
import { useRulesStore } from '@/stores/rules'
import type { UserRole } from '@/types/auth'
import type { RouteLocationRaw } from 'vue-router'

const { t } = useI18n()
const auth = useAuthStore()
const sdv = useSdvStore()
const notes = useNotesStore()
const users = useUsersStore()
const rules = useRulesStore()

/* ---------- role set with M1 → M2 fallback chain ---------- */
const userRoles = computed<UserRole[]>(() => {
  const active = auth.user?.activeStudy
  if (active?.roles && active.roles.length > 0) return [...active.roles]
  if (active?.role) return [active.role]
  if (auth.user?.role) return [auth.user.role]
  return []
})

/**
 * Role priority (highest-to-lowest) — mirrors the backend
 * UsersApiController.rolePriority projection so the card's accent
 * colour reflects the strongest binding the user holds.
 */
const ROLE_PRIORITY: Record<UserRole, number> = {
  Administrator: 5,
  'Data Manager': 4,
  Monitor: 3,
  CRC: 2,
  Investigator: 1,
}

const ROLE_TO_VARIANT: Record<UserRole, RoleVariant> = {
  Investigator: 'investigator',
  CRC: 'investigator',
  Monitor: 'monitor',
  'Data Manager': 'data-manager',
  Administrator: 'administrator',
}

/**
 * Surface a "Switch active study" card once the user has access to
 * more than one study. Lazy-loads availableStudies via the existing
 * /me/studies endpoint; failure modes (offline, single-study user)
 * collapse to hiding the card.
 */
const canSwitchStudy = computed(
  () => (auth.availableStudies?.length ?? 0) > 1,
)

const activeStudyOid = computed(() => auth.user?.activeStudy?.oid ?? '')
const activeStudyName = computed(() => auth.user?.activeStudy?.name ?? '')

/* ---------- count derivations from already-loaded store state ---------- */
//
// All store .rows arrays are populated by the eager onMounted load. We
// derive badges from them. Each computed is null until the load has
// resolved (LandingCard hides null badges); after that it reflects the
// current snapshot. If the user navigates away + back, the store
// state survives and the counts stay live without re-fetching.

const sdvPendingCount = computed<number | null>(() => {
  if (!sdv.rows) return null
  return sdv.rows.filter((r) => r.status === 'pending').length
})

const openQueriesCount = computed<number | null>(() => {
  if (!notes.rows) return null
  return notes.rows.filter((r) => r.status !== 'closed' && r.status !== 'not-applicable').length
})

const pendingInvitesCount = computed<number | null>(() => {
  if (!users.rows) return null
  return users.rows.filter((u) => u.auth === 'pending-invite').length
})

const activeRuleSetsCount = computed<number | null>(() => {
  if (!rules.rows) return null
  return rules.rows.filter((rs) => rs.status === 'available').length
})

/* ---------- de-duplicated card catalogue ---------- */

interface CatalogEntry {
  id: string
  to: RouteLocationRaw | (() => RouteLocationRaw)
  titleKey: string
  descKey: string
  allowedRoles: UserRole[]
  /**
   * Scope determines which lane the card renders in:
   * - 'general'      → above the divider (platform-wide, cross-study, navigation)
   * - 'study-scoped' → below the divider (operates against the active study)
   *
   * The divider carries the active study's name so the operator can
   * tell at a glance which actions are bound to the currently bound
   * study vs which apply across their grant set.
   */
  scope: 'general' | 'study-scoped'
  /** Optional getter producing the badge count; resolved at render time. */
  badge?: () => number | string | null
  badgeAriaKey?: string
  /** Optional gate evaluated after role match (e.g. activeStudyOid presence). */
  visibleWhen?: () => boolean
}

const CATALOG = computed<CatalogEntry[]>(() => [
  // ---------- subject-centred workflows ----------
  {
    id: 'subject-matrix',
    to: { name: 'subject-matrix' },
    titleKey: 'subjectMatrix.title',
    descKey: 'home.investigator.subjectMatrixDesc',
    allowedRoles: ['Investigator', 'CRC', 'Monitor'],
    scope: 'study-scoped',
  },
  {
    id: 'subject-new',
    to: { name: 'subject-new' },
    titleKey: 'addSubject.title',
    descKey: 'home.investigator.addSubjectDesc',
    allowedRoles: ['Investigator', 'CRC'],
    scope: 'study-scoped',
  },
  {
    id: 'sign-queue',
    to: { name: 'subject-matrix', query: { filter: 'ready-to-sign' } },
    titleKey: 'home.investigator.signQueueTitle',
    descKey: 'home.investigator.signQueueDesc',
    allowedRoles: ['Investigator', 'CRC'],
    scope: 'study-scoped',
  },
  {
    id: 'todays-crfs',
    to: { name: 'subject-matrix', query: { filter: 'today' } },
    titleKey: 'home.investigator.todaysCrfsTitle',
    descKey: 'home.investigator.todaysCrfsDesc',
    allowedRoles: ['Investigator', 'CRC'],
    scope: 'general',
  },

  // ---------- SDV (Monitor-only) ----------
  {
    id: 'sdv',
    to: { name: 'sdv' },
    titleKey: 'sdv.title',
    descKey: 'home.monitor.sdvDesc',
    allowedRoles: ['Monitor'],
    badge: () => sdvPendingCount.value,
    badgeAriaKey: 'home.monitor.sdvBadgeAria',
    scope: 'study-scoped',
  },

  // ---------- cross-role Notes (consolidated) ----------
  {
    id: 'notes',
    to: { name: 'notes' },
    titleKey: 'notes.title',
    descKey: 'home.monitor.openQueriesDesc',
    allowedRoles: ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
    badge: () => openQueriesCount.value,
    badgeAriaKey: 'home.monitor.openQueriesBadgeAria',
    scope: 'study-scoped',
  },

  // ---------- cross-role Audit log ----------
  {
    id: 'audit-log',
    to: { name: 'audit-log' },
    titleKey: 'auditLog.title',
    descKey: 'home.administrator.auditLogDesc',
    allowedRoles: ['Monitor', 'Data Manager', 'Administrator'],
    scope: 'study-scoped',
  },

  // ---------- Data Manager workflows ----------
  {
    id: 'build-study',
    to: { name: 'build-study' },
    titleKey: 'buildStudy.title',
    descKey: 'home.dataManager.buildStudyDesc',
    allowedRoles: ['Data Manager'],
    scope: 'study-scoped',
  },
  {
    id: 'import-crf-data',
    to: { name: 'import-crf-data' },
    titleKey: 'importCrf.title',
    descKey: 'home.dataManager.importCrfDesc',
    allowedRoles: ['Data Manager'],
    scope: 'study-scoped',
  },
  {
    id: 'rules',
    to: { name: 'rules' },
    titleKey: 'rules.title',
    descKey: 'home.dataManager.rulesDesc',
    allowedRoles: ['Data Manager'],
    badge: () => activeRuleSetsCount.value,
    scope: 'study-scoped',
  },

  // ---------- cross-role Data Export ----------
  {
    id: 'data-export',
    to: { name: 'data-export' },
    titleKey: 'home.dataManager.dataExportTitle',
    descKey: 'home.dataManager.dataExportDesc',
    allowedRoles: ['Monitor', 'Data Manager', 'Administrator'],
    scope: 'study-scoped',
  },

  // ---------- Administrator platform actions ----------
  {
    id: 'manage-users',
    to: { name: 'manage-users' },
    titleKey: 'manageUsers.title',
    descKey: 'home.administrator.manageUsersDesc',
    allowedRoles: ['Administrator'],
    badge: () => pendingInvitesCount.value,
    badgeAriaKey: 'home.administrator.pendingInvitesBadgeAria',
    scope: 'general',
  },
  {
    id: 'study-create',
    to: { name: 'study-create' },
    titleKey: 'home.administrator.createStudyTitle',
    descKey: 'home.administrator.createStudyDesc',
    allowedRoles: ['Administrator'],
    scope: 'general',
  },
  {
    id: 'modalities',
    to: { name: 'modalities' },
    titleKey: 'modalities.title',
    descKey: 'home.administrator.modalitiesDesc',
    allowedRoles: ['Administrator'],
    scope: 'general',
  },
  {
    id: 'study-edit',
    to: () => ({ name: 'study-edit', params: { oid: activeStudyOid.value } }),
    titleKey: 'home.administrator.editStudyTitle',
    descKey: 'home.administrator.editStudyDesc',
    allowedRoles: ['Administrator'],
    visibleWhen: () => activeStudyOid.value !== '',
    scope: 'study-scoped',
  },
  {
    id: 'sites',
    to: { name: 'sites' },
    titleKey: 'home.administrator.sitesTitle',
    descKey: 'home.administrator.sitesDesc',
    allowedRoles: ['Administrator'],
    scope: 'study-scoped',
  },

  // ---------- cross-study Patient Overview (Phase E.6) ----------
  {
    id: 'patients-overview',
    to: { name: 'patients-overview' },
    titleKey: 'home.cards.patientsOverview.title',
    descKey: 'home.cards.patientsOverview.description',
    allowedRoles: ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
    scope: 'general',
  },

  // ---------- cross-role Switch active study ----------
  {
    id: 'switch-study',
    to: { name: 'pick-study' },
    titleKey: 'home.switchStudyTitle',
    descKey: 'home.switchStudyDesc',
    allowedRoles: ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
    visibleWhen: () => canSwitchStudy.value,
    scope: 'general',
  },
])

interface VisibleCard {
  id: string
  to: RouteLocationRaw
  titleKey: string
  descKey: string
  grantingRoles: UserRole[]
  roleVariants: RoleVariant[]
  badge: number | string | null
  badgeAriaKey?: string
  scope: 'general' | 'study-scoped'
}

const visibleCards = computed<VisibleCard[]>(() => {
  const rs = userRoles.value
  if (rs.length === 0) return []
  return CATALOG.value
    .filter((c) => c.allowedRoles.some((r) => rs.includes(r)))
    .filter((c) => (c.visibleWhen ? c.visibleWhen() : true))
    .map((c) => {
      // grantingRoles are ordered by priority (highest first) so the
      // card's accent colour and the first dot reflect the strongest
      // binding the user holds against this card.
      const granting = c.allowedRoles
        .filter((r) => rs.includes(r))
        .sort((a, b) => ROLE_PRIORITY[b] - ROLE_PRIORITY[a])
      // Variants follow grantingRoles but collapse duplicate variants
      // (CRC + Investigator both map to 'investigator' — render one dot).
      const variants: RoleVariant[] = []
      for (const r of granting) {
        const v = ROLE_TO_VARIANT[r]
        if (!variants.includes(v)) variants.push(v)
      }
      return {
        id: c.id,
        to: typeof c.to === 'function' ? c.to() : c.to,
        titleKey: c.titleKey,
        descKey: c.descKey,
        grantingRoles: granting,
        roleVariants: variants,
        badge: c.badge ? c.badge() : null,
        badgeAriaKey: c.badgeAriaKey,
        scope: c.scope,
      }
    })
})

const generalCards = computed<VisibleCard[]>(() =>
  visibleCards.value.filter((c) => c.scope === 'general'),
)
const studyScopedCards = computed<VisibleCard[]>(() =>
  visibleCards.value.filter((c) => c.scope === 'study-scoped'),
)

/* ---------- eager load on mount ---------- */
onMounted(() => {
  // Kick everything off in parallel; tolerate per-action failures so
  // one transient backend hiccup doesn't cascade-blank the whole
  // landing. The shape is identical to a sequential await chain but
  // with no head-of-line blocking.
  const rs = userRoles.value
  const has = (r: UserRole) => rs.includes(r)
  const inflight: Array<Promise<unknown>> = []
  if (has('Monitor') || has('Data Manager')) {
    inflight.push(sdv.load())
    inflight.push(rules.load())
  }
  // Notes is cross-role — load whenever the user can see the catalog
  // entry (Investigator/CRC/Monitor/DM/Administrator).
  if (rs.length > 0) {
    inflight.push(notes.load())
  }
  if (has('Administrator') || has('Data Manager')) {
    inflight.push(users.load())
  }
  // Always populate availableStudies for the multi-study switch card.
  inflight.push(auth.loadStudies())
  void Promise.allSettled(inflight)
})
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

    <!-- Two-lane catalogue: general (study-independent / platform /
         cross-study navigation) above the divider, study-scoped below.
         Each card carries the full set of roles that grant access so
         a multi-role operator sees their RoleDots stacked in the card
         header — PR #160's de-duplication contract is preserved
         per-lane. The divider carries the active study name so the
         operator can tell at a glance which actions are bound to
         the currently active study. -->
    <section
      v-if="generalCards.length > 0"
      :aria-label="t('home.administrator.globalSectionLabel')"
      class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-6"
    >
      <LandingCard
        v-for="card in generalCards"
        :key="card.id"
        :data-card-id="card.id"
        :to="card.to"
        :role-variants="card.roleVariants"
        :title="t(card.titleKey)"
        :description="t(card.descKey)"
        :badge="card.badge"
        :badge-aria-label="card.badgeAriaKey ? t(card.badgeAriaKey, { n: card.badge ?? 0 }) : undefined"
      />
    </section>

    <!-- Divider with active-study label. Only renders when there are
         cards in BOTH lanes (otherwise the divider would dangle above
         or below an empty section). -->
    <div
      v-if="generalCards.length > 0 && studyScopedCards.length > 0"
      class="max-w-5xl mb-6 flex items-center gap-3"
      data-testid="home-study-scoped-divider"
    >
      <hr class="flex-1 border-t border-slate-200" />
      <span class="text-[11px] uppercase tracking-[0.14em] text-slate-500 whitespace-nowrap">
        {{ t('home.administrator.studyScopedLabel', { study: activeStudyName }) }}
      </span>
      <hr class="flex-1 border-t border-slate-200" />
    </div>

    <section
      v-if="studyScopedCards.length > 0"
      :aria-label="t('home.sectionLabel', { study: activeStudyName })"
      class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mb-8"
    >
      <LandingCard
        v-for="card in studyScopedCards"
        :key="card.id"
        :data-card-id="card.id"
        :to="card.to"
        :role-variants="card.roleVariants"
        :title="t(card.titleKey)"
        :description="t(card.descKey)"
        :badge="card.badge"
        :badge-aria-label="card.badgeAriaKey ? t(card.badgeAriaKey, { n: card.badge ?? 0 }) : undefined"
      />
    </section>

    <!-- Fallback when role hasn't loaded yet (auth.bootstrap() in flight). -->
    <p v-if="userRoles.length === 0" class="text-slate-500 text-sm italic">
      {{ t('common.loading') }}
    </p>
  </div>
</template>

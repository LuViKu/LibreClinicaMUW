<script setup lang="ts">
/**
 * The home page: what is waiting for this operator, then where else they
 * can go.
 *
 * <p>Redesigned 2026-09-19 from a flat grid of equally weighted cards. The
 * page had grown into the application's only navigation — the top bar
 * carried no menu, the side rail is mounted per view — so an overview page
 * was doing the work of a menu, and it did that as a catalogue of seven to
 * eleven cards in declaration order with no numbers on the ones that mean
 * "work to do". The manual promised a dashboard that "summarizes what needs
 * your attention"; for a physician it summarised nothing.
 *
 * <p>Now there are two kinds of card. A <strong>work queue</strong> carries a
 * count — today's open visits, subjects ready to sign, open queries, files in
 * the inbox, due visits, CRFs awaiting verification — and links to the list
 * with that filter already applied, so the number on the card is the number
 * of rows the operator lands on. A <strong>workspace</strong> is a
 * destination, listed compactly below, split into what acts on the active
 * study and what is platform-wide. Cross-workflow navigation lives in the top
 * bar now ({@code primaryNav.ts}), so this page no longer has to be the whole
 * map.
 *
 * <p>Multi-role per (user, study): every card carries the roles that grant
 * it; a card the operator holds through two roles shows both dots once. The
 * role set comes from {@code activeStudy.roles}, with the M1 fallbacks.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LandingCard, { type RoleVariant } from '@/components/LandingCard.vue'
import WorkQueueCard from '@/components/WorkQueueCard.vue'
import { useAuthStore } from '@/stores/auth'
import { useSdvStore } from '@/stores/sdv'
import { useNotesStore } from '@/stores/notes'
import { useUsersStore } from '@/stores/users'
import { useRulesStore } from '@/stores/rules'
import { useSubjectsStore, matchesStatusFilter } from '@/stores/subjects'
import { useStudyModuleStore } from '@/stores/studyModules'
import { entryAllowsRoles } from '@/studyModules/roleGate'
import { ingestInboxCounts } from '@/api/ingest'
import { listDueVisits } from '@/api/events'
import type { UserRole } from '@/types/auth'
import type { RouteLocationRaw } from 'vue-router'

const { t, locale } = useI18n()

const auth = useAuthStore()
const sdv = useSdvStore()
const notes = useNotesStore()
const users = useUsersStore()
const rules = useRulesStore()
const subjects = useSubjectsStore()
const studyModules = useStudyModuleStore()

const userRoles = computed<UserRole[]>(() => {
  const active = auth.user?.activeStudy
  if (active?.roles && active.roles.length > 0) return [...active.roles]
  if (active?.role) return [active.role]
  if (auth.user?.role) return [auth.user.role]
  return []
})

/**
 * Role priority (highest-to-lowest) — mirrors the backend
 * UsersApiController.rolePriority projection so a card's accent colour
 * reflects the strongest binding the user holds.
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

const canSwitchStudy = computed(() => (auth.availableStudies?.length ?? 0) > 1)
const activeStudyOid = computed(() => auth.user?.activeStudy?.oid ?? '')
const activeStudyName = computed(() => auth.user?.activeStudy?.name ?? '')
const displayName = computed(() => auth.user?.displayName || auth.user?.username || '')

/** Today, in the operator's language — the one thing a dashboard header should say. */
const todayLabel = computed(() => {
  try {
    return new Intl.DateTimeFormat(locale.value, { weekday: 'long', day: 'numeric', month: 'long' })
      .format(new Date())
  } catch {
    return new Date().toLocaleDateString()
  }
})

/* ------------------------------------------------------------------ */
/* Counts. null until the source has answered; LandingCard hides null  */
/* badges, WorkQueueCard shows a dash. A store's rows survive          */
/* navigation, so returning here does not re-fetch.                    */
/* ------------------------------------------------------------------ */

const subjectsLoaded = ref(false)
const inboxUnbound = ref<number | null>(null)
const dueVisitsCount = ref<number | null>(null)

const todaysCount = computed<number | null>(() =>
  subjectsLoaded.value ? subjects.rows.filter((s) => matchesStatusFilter(s, 'today')).length : null,
)
const readyToSignCount = computed<number | null>(() =>
  subjectsLoaded.value ? subjects.rows.filter((s) => matchesStatusFilter(s, 'ready-to-sign')).length : null,
)
const sdvPendingCount = computed<number | null>(() =>
  sdv.rows ? sdv.rows.filter((r) => r.status === 'pending').length : null,
)
const openQueriesCount = computed<number | null>(() =>
  notes.rows ? notes.rows.filter((r) => r.status !== 'closed' && r.status !== 'not-applicable').length : null,
)
const pendingInvitesCount = computed<number | null>(() =>
  users.rows ? users.rows.filter((u) => u.auth === 'pending-invite').length : null,
)
const activeRuleSetsCount = computed<number | null>(() =>
  rules.rows ? rules.rows.filter((rs) => rs.status === 'available').length : null,
)

/* ------------------------------------------------------------------ */
/* The catalogue                                                       */
/* ------------------------------------------------------------------ */

interface QueueEntry {
  id: string
  to: RouteLocationRaw
  titleKey: string
  descKey: string
  allowedRoles: UserRole[]
  count: () => number | null
  /** i18n key taking {n}: the spoken form of the count. */
  countAriaKey: string
}

interface WorkspaceEntry {
  id: string
  to: RouteLocationRaw | (() => RouteLocationRaw)
  titleKey: string
  descKey: string
  allowedRoles: UserRole[]
  /** 'study' acts on the active study; 'platform' applies across the grant set. */
  group: 'study' | 'platform'
  badge?: () => number | string | null
  badgeAriaKey?: string
  visibleWhen?: () => boolean
}

/**
 * Work queues, in the order a role reaches for them: what is on the plate
 * today first, then what waits on the operator, then what waits on others.
 */
const QUEUES: QueueEntry[] = [
  {
    id: 'todays-crfs',
    to: { name: 'subject-matrix', query: { filter: 'today' } },
    titleKey: 'home.investigator.todaysCrfsTitle',
    descKey: 'home.investigator.todaysCrfsDesc',
    allowedRoles: ['Investigator', 'CRC'],
    count: () => todaysCount.value,
    countAriaKey: 'home.investigator.todaysCrfsCountAria',
  },
  {
    id: 'sign-queue',
    to: { name: 'subject-matrix', query: { filter: 'ready-to-sign' } },
    titleKey: 'home.investigator.signQueueTitle',
    descKey: 'home.investigator.signQueueDesc',
    allowedRoles: ['Investigator', 'CRC'],
    count: () => readyToSignCount.value,
    countAriaKey: 'home.investigator.signQueueCountAria',
  },
  {
    id: 'sdv',
    to: { name: 'sdv' },
    titleKey: 'sdv.title',
    descKey: 'home.monitor.sdvDesc',
    allowedRoles: ['Monitor'],
    count: () => sdvPendingCount.value,
    countAriaKey: 'home.monitor.sdvBadgeAria',
  },
  {
    id: 'notes',
    to: { name: 'notes' },
    titleKey: 'notes.title',
    descKey: 'home.monitor.openQueriesDesc',
    allowedRoles: ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
    count: () => openQueriesCount.value,
    countAriaKey: 'home.monitor.openQueriesBadgeAria',
  },
  {
    id: 'image-inbox',
    to: { name: 'ingest-inbox' },
    titleKey: 'ingestInbox.title',
    descKey: 'ingestInbox.cardDesc',
    allowedRoles: ['Data Manager', 'Investigator'],
    count: () => inboxUnbound.value,
    countAriaKey: 'ingestInbox.countAria',
  },
  {
    id: 'due-visits',
    to: { name: 'due-visits' },
    titleKey: 'dueVisits.title',
    descKey: 'dueVisits.cardDesc',
    allowedRoles: ['Data Manager', 'Investigator', 'Monitor'],
    count: () => dueVisitsCount.value,
    countAriaKey: 'dueVisits.countAria',
  },
]

/**
 * Destinations. Every allowedRoles list mirrors the route's own role meta:
 * a card that opens a route the role cannot enter is a dead click that
 * bounces back here — the CRC's Patientenübersicht did exactly that.
 */
const WORKSPACES = computed<WorkspaceEntry[]>(() => [
  {
    id: 'subject-matrix',
    to: { name: 'subject-matrix' },
    titleKey: 'subjectMatrix.title',
    descKey: 'home.investigator.subjectMatrixDesc',
    allowedRoles: ['Investigator', 'CRC', 'Monitor'],
    group: 'study',
  },
  {
    id: 'subject-new',
    to: { name: 'subject-new' },
    titleKey: 'addSubject.title',
    descKey: 'home.investigator.addSubjectDesc',
    allowedRoles: ['Investigator', 'CRC'],
    group: 'study',
  },
  {
    id: 'build-study',
    to: { name: 'build-study' },
    titleKey: 'buildStudy.title',
    descKey: 'home.dataManager.buildStudyDesc',
    allowedRoles: ['Data Manager'],
    group: 'study',
  },
  {
    id: 'import-crf-data',
    to: { name: 'import-crf-data' },
    titleKey: 'importCrf.title',
    descKey: 'home.dataManager.importCrfDesc',
    allowedRoles: ['Data Manager'],
    group: 'study',
  },
  {
    id: 'rules',
    to: { name: 'rules' },
    titleKey: 'rules.title',
    descKey: 'home.dataManager.rulesDesc',
    allowedRoles: ['Data Manager'],
    badge: () => activeRuleSetsCount.value,
    group: 'study',
  },
  {
    id: 'data-export',
    to: { name: 'data-export' },
    titleKey: 'home.dataManager.dataExportTitle',
    descKey: 'home.dataManager.dataExportDesc',
    allowedRoles: ['Monitor', 'Data Manager', 'Administrator'],
    group: 'study',
  },
  {
    id: 'audit-log',
    to: { name: 'audit-log' },
    titleKey: 'auditLog.title',
    descKey: 'home.administrator.auditLogDesc',
    allowedRoles: ['Monitor', 'Data Manager', 'Administrator'],
    group: 'study',
  },
  {
    id: 'sites',
    to: { name: 'sites' },
    titleKey: 'home.administrator.sitesTitle',
    descKey: 'home.administrator.sitesDesc',
    allowedRoles: ['Data Manager', 'Administrator'],
    group: 'study',
  },
  {
    id: 'study-edit',
    to: () => ({ name: 'study-edit', params: { oid: activeStudyOid.value } }),
    titleKey: 'home.administrator.editStudyTitle',
    descKey: 'home.administrator.editStudyDesc',
    allowedRoles: ['Administrator'],
    visibleWhen: () => activeStudyOid.value !== '',
    group: 'study',
  },

  {
    id: 'patients-overview',
    to: { name: 'patients-overview' },
    titleKey: 'home.cards.patientsOverview.title',
    descKey: 'home.cards.patientsOverview.description',
    // Not CRC: /patients does not admit the role, so the card was a dead click.
    allowedRoles: ['Investigator', 'Monitor', 'Data Manager', 'Administrator'],
    group: 'platform',
  },
  {
    id: 'manage-users',
    to: { name: 'manage-users' },
    titleKey: 'manageUsers.title',
    descKey: 'home.administrator.manageUsersDesc',
    allowedRoles: ['Administrator'],
    badge: () => pendingInvitesCount.value,
    badgeAriaKey: 'home.administrator.pendingInvitesBadgeAria',
    group: 'platform',
  },
  {
    id: 'study-create',
    to: { name: 'study-create' },
    titleKey: 'home.administrator.createStudyTitle',
    descKey: 'home.administrator.createStudyDesc',
    allowedRoles: ['Administrator'],
    group: 'platform',
  },
  {
    id: 'modalities',
    to: { name: 'modalities' },
    titleKey: 'modalities.title',
    descKey: 'home.administrator.modalitiesDesc',
    allowedRoles: ['Administrator'],
    group: 'platform',
  },
  {
    id: 'switch-study',
    to: { name: 'pick-study' },
    titleKey: 'home.switchStudyTitle',
    descKey: 'home.switchStudyDesc',
    allowedRoles: ['Investigator', 'CRC', 'Monitor', 'Data Manager', 'Administrator'],
    visibleWhen: () => canSwitchStudy.value,
    group: 'platform',
  },
])

/** The roles that grant a card, strongest first; and the dots that shows. */
function grantsFor(allowed: UserRole[]): { roles: UserRole[]; variants: RoleVariant[] } {
  const rs = userRoles.value
  const roles = allowed
    .filter((r) => rs.includes(r))
    .sort((a, b) => ROLE_PRIORITY[b] - ROLE_PRIORITY[a])
  const variants: RoleVariant[] = []
  for (const r of roles) {
    const v = ROLE_TO_VARIANT[r]
    if (!variants.includes(v)) variants.push(v)
  }
  return { roles, variants }
}

interface VisibleQueue {
  id: string
  to: RouteLocationRaw
  titleKey: string
  descKey: string
  roleVariants: RoleVariant[]
  count: number | null
  countAriaKey: string
}

interface VisibleWorkspace {
  id: string
  to: RouteLocationRaw
  titleKey: string
  descKey: string
  roleVariants: RoleVariant[]
  badge: number | string | null
  badgeAriaKey?: string
  group: 'study' | 'platform'
}

const queues = computed<VisibleQueue[]>(() => {
  const rs = userRoles.value
  if (rs.length === 0) return []
  return QUEUES
    .filter((q) => q.allowedRoles.some((r) => rs.includes(r)))
    .map((q) => ({
      id: q.id,
      to: q.to,
      titleKey: q.titleKey,
      descKey: q.descKey,
      roleVariants: grantsFor(q.allowedRoles).variants,
      count: q.count(),
      countAriaKey: q.countAriaKey,
    }))
})

const workspaces = computed<VisibleWorkspace[]>(() => {
  const rs = userRoles.value
  if (rs.length === 0) return []
  return WORKSPACES.value
    .filter((c) => c.allowedRoles.some((r) => rs.includes(r)))
    .filter((c) => (c.visibleWhen ? c.visibleWhen() : true))
    .map((c) => ({
      id: c.id,
      to: typeof c.to === 'function' ? c.to() : c.to,
      titleKey: c.titleKey,
      descKey: c.descKey,
      roleVariants: grantsFor(c.allowedRoles).variants,
      badge: c.badge ? c.badge() : null,
      badgeAriaKey: c.badgeAriaKey,
      group: c.group,
    }))
})

const studyWorkspaces = computed(() => workspaces.value.filter((c) => c.group === 'study'))
const platformWorkspaces = computed(() => workspaces.value.filter((c) => c.group === 'platform'))

/**
 * Cards contributed by the study modules enrolled on the bound study. They
 * render with the study's workspaces because that is what they are, and
 * only for the roles the module names — an entry that opens a route the
 * role cannot enter would be a dead click.
 */
const moduleCards = computed(() =>
  studyModules.injectionsFor('home.cards').filter((entry) => entryAllowsRoles(entry, userRoles.value)),
)

onMounted(() => {
  // Everything in parallel; one source failing must not blank the rest. A
  // count whose source failed stays null, which the queue card shows as a
  // dash rather than as a zero the operator might act on.
  const rs = userRoles.value
  const has = (r: UserRole) => rs.includes(r)
  const inflight: Array<Promise<unknown>> = []
  if (has('Investigator') || has('CRC')) {
    inflight.push(subjects.load().finally(() => { subjectsLoaded.value = true }))
  }
  if (has('Monitor') || has('Data Manager')) {
    inflight.push(sdv.load())
    inflight.push(rules.load())
  }
  if (rs.length > 0) {
    inflight.push(notes.load())
  }
  if (has('Administrator') || has('Data Manager')) {
    inflight.push(users.load())
  }
  if (has('Investigator') || has('Data Manager')) {
    inflight.push(
      ingestInboxCounts().then((c) => { inboxUnbound.value = c?.unbound ?? 0 }),
    )
  }
  if (has('Investigator') || has('Data Manager') || has('Monitor')) {
    inflight.push(
      listDueVisits({}).then((r) => { dueVisitsCount.value = r?.visits?.length ?? 0 }),
    )
  }
  inflight.push(auth.loadStudies())
  void Promise.allSettled(inflight)
})
</script>

<template>
  <div class="max-w-6xl mx-auto px-6 py-10">
    <!-- A greeting, the study, the date. The brand is in the top bar; the
         page's job is to get the operator somewhere, so it starts working
         within the first screen. -->
    <header class="mb-8">
      <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-muw-coral-700 mb-2 flex items-center gap-2">
        <span class="muw-rule"></span>
        <span data-testid="home-context">{{ activeStudyName }}<template v-if="activeStudyName"> · </template>{{ todayLabel }}</span>
      </p>
      <h1 class="muw-display text-3xl font-medium text-muw-blue leading-tight" data-testid="home-greeting">
        {{ displayName ? t('home.greeting', { name: displayName }) : t('home.greetingPlain') }}
      </h1>
    </header>

    <!-- What is waiting: counted queues, the reason to open this page. -->
    <section
      v-if="queues.length > 0"
      :aria-label="t('home.sections.queues')"
      class="mb-10"
      data-testid="home-queues"
    >
      <h2 class="text-[11px] uppercase tracking-[0.14em] text-slate-500 mb-3">
        {{ t('home.sections.queues') }}
      </h2>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <WorkQueueCard
          v-for="card in queues"
          :key="card.id"
          :data-card-id="card.id"
          :to="card.to"
          :role-variants="card.roleVariants"
          :title="t(card.titleKey)"
          :description="t(card.descKey)"
          :count="card.count"
          :count-aria-label="t(card.countAriaKey, { n: card.count ?? 0 })"
          :loading-label="t('home.queue.loading')"
        />
      </div>
    </section>

    <!-- Where else to go, in the active study. -->
    <section
      v-if="studyWorkspaces.length > 0 || moduleCards.length > 0"
      :aria-label="t('home.sections.study', { study: activeStudyName })"
      class="mb-10"
      data-testid="home-study-workspaces"
    >
      <h2 class="text-[11px] uppercase tracking-[0.14em] text-slate-500 mb-3 flex items-center gap-3">
        <span class="whitespace-nowrap">{{ t('home.sections.study', { study: activeStudyName }) }}</span>
        <hr class="flex-1 border-t border-slate-200" />
      </h2>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        <LandingCard
          v-for="card in studyWorkspaces"
          :key="card.id"
          :data-card-id="card.id"
          :to="card.to"
          :role-variants="card.roleVariants"
          :title="t(card.titleKey)"
          :description="t(card.descKey)"
          :badge="card.badge"
          :badge-aria-label="card.badgeAriaKey ? t(card.badgeAriaKey, { n: card.badge ?? 0 }) : undefined"
          compact
        />
        <component
          :is="entry.component"
          v-for="entry in moduleCards"
          :key="entry.key"
        />
      </div>
    </section>

    <!-- And across studies / the platform. Only renders when there is
         something in it, so nobody gets a heading over a single card. -->
    <section
      v-if="platformWorkspaces.length > 0"
      :aria-label="t('home.sections.platform')"
      class="mb-6"
      data-testid="home-platform-workspaces"
    >
      <h2 class="text-[11px] uppercase tracking-[0.14em] text-slate-500 mb-3 flex items-center gap-3">
        <span class="whitespace-nowrap">{{ t('home.sections.platform') }}</span>
        <hr class="flex-1 border-t border-slate-200" />
      </h2>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        <LandingCard
          v-for="card in platformWorkspaces"
          :key="card.id"
          :data-card-id="card.id"
          :to="card.to"
          :role-variants="card.roleVariants"
          :title="t(card.titleKey)"
          :description="t(card.descKey)"
          :badge="card.badge"
          :badge-aria-label="card.badgeAriaKey ? t(card.badgeAriaKey, { n: card.badge ?? 0 }) : undefined"
          compact
        />
      </div>
    </section>

    <!-- Fallback while auth.bootstrap() is in flight. -->
    <p v-if="userRoles.length === 0" class="text-slate-500 text-sm italic">
      {{ t('common.loading') }}
    </p>
  </div>
</template>

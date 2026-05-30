import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuditEvent, AuditEventVariant } from '@/types/audit'

/**
 * Phase E.6 — Audit log store.
 *
 * Backs the StudyAuditLogView. Mock-hydrated for now; the planned
 * backend adapter at `GET /pages/api/v1/audit?dateRange=…&user=…&
 * actionType=…&subject=…` consumes the same shape (see api-surface.md
 * row 8). The before/after fields on `data-edit` events drive the
 * <DiffCard> primitive inside <TimelineEvent>.
 */
export const useAuditLogStore = defineStore('auditLog', () => {
  const events = ref<AuditEvent[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  // Filter state.
  const actorFilter = ref<string>('') // empty = all
  const variantFilter = ref<'all' | AuditEventVariant>('all')
  const subjectFilter = ref<string>('')

  const filtered = computed<AuditEvent[]>(() => {
    return events.value.filter((e) => {
      if (actorFilter.value && e.actor !== actorFilter.value) return false
      if (variantFilter.value !== 'all' && e.variant !== variantFilter.value) return false
      if (subjectFilter.value && e.subjectId !== subjectFilter.value) return false
      return true
    })
  })

  const totalCount = computed(() => events.value.length)
  const visibleCount = computed(() => filtered.value.length)

  /** All distinct actor usernames seen — used to populate the actor filter. */
  const actors = computed<string[]>(() => {
    const set = new Set<string>()
    for (const e of events.value) set.add(e.actor)
    return [...set].sort()
  })

  /** All distinct subjects — used to populate the subject filter. */
  const subjects = computed<string[]>(() => {
    const set = new Set<string>()
    for (const e of events.value) if (e.subjectId) set.add(e.subjectId)
    return [...set].sort()
  })

  /** Filtered events grouped by ISO date (YYYY-MM-DD), newest first. */
  const groupedByDate = computed<{ date: string; events: AuditEvent[] }[]>(() => {
    const map = new Map<string, AuditEvent[]>()
    for (const e of filtered.value) {
      const date = e.occurredAt.slice(0, 10)
      const bucket = map.get(date)
      if (bucket) bucket.push(e)
      else map.set(date, [e])
    }
    return [...map.entries()]
      .sort((a, b) => (a[0] < b[0] ? 1 : -1))
      .map(([date, events]) => ({ date, events }))
  })

  function clearFilters() {
    actorFilter.value = ''
    variantFilter.value = 'all'
    subjectFilter.value = ''
  }

  async function load(_studyOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      // TODO(E.4): apiGet<AuditEvent[]>('/pages/api/v1/audit?...').
      events.value = await loadMock()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error loading audit log'
    } finally {
      isLoading.value = false
    }
  }

  return {
    events,
    isLoading,
    error,
    actorFilter,
    variantFilter,
    subjectFilter,
    filtered,
    totalCount,
    visibleCount,
    actors,
    subjects,
    groupedByDate,
    clearFilters,
    load,
  }
})

/* -------------------------------------------------------------------------- */
/* Mock loader                                                                */
/* -------------------------------------------------------------------------- */

async function loadMock(): Promise<AuditEvent[]> {
  await new Promise((resolve) => setTimeout(resolve, 30))
  return MOCK_EVENTS
}

const MOCK_EVENTS: AuditEvent[] = [
  {
    id: 'ev-100',
    occurredAt: '2026-05-30T13:42:08Z',
    variant: 'signed',
    actor: 'user_demo',
    actorRole: 'Investigator',
    title: 'Subject sign-off',
    subjectId: 'M-001',
    details: 'All 11 CRFs locked against site edit. IP 10.0.4.21 · München.',
  },
  {
    id: 'ev-099',
    occurredAt: '2026-05-30T12:31:55Z',
    variant: 'reason-for-change',
    actor: 'user_demo',
    actorRole: 'Investigator',
    title: 'Data edit · Reason for change',
    subjectId: 'M-001',
    scope: 'AE · ae_severity',
    before: 'Severe',
    after: 'Moderate',
    reason: 'Re-graded after consult with PI. Original entry erroneously logged Grade 3 instead of Grade 2.',
  },
  {
    id: 'ev-098',
    occurredAt: '2026-05-30T09:08:14Z',
    variant: 'sdv',
    actor: 'monitor_demo',
    actorRole: 'Monitor',
    title: 'SDV verified',
    subjectId: 'M-002',
    scope: 'V1 Inclusion · Demographics',
    details: 'All 12 items in CRF verified against source documents during on-site visit.',
  },
  {
    id: 'ev-097',
    occurredAt: '2026-05-30T07:54:31Z',
    variant: 'admin',
    actor: 'dm_demo',
    actorRole: 'Data Manager',
    title: 'CRF reset · sign reverted',
    subjectId: 'M-003',
    scope: 'V2 Day 30 · Vitals',
    details: 'Sign-off reverted per SOP-04-12 to allow data correction.',
    reason: 'Investigator needs to correct data entry error in vital signs.',
  },
  {
    id: 'ev-096',
    occurredAt: '2026-05-29T16:11:09Z',
    variant: 'data',
    actor: 'user_demo',
    actorRole: 'Investigator',
    title: 'CRF marked complete',
    subjectId: 'M-001',
    scope: 'V3 Day 90 · Final Visit Summary',
    details: '14 items entered, 0 failed validations, 0 queries.',
  },
  {
    id: 'ev-095',
    occurredAt: '2026-05-29T14:02:33Z',
    variant: 'query',
    actor: 'monitor_demo',
    actorRole: 'Monitor',
    title: 'Query opened',
    subjectId: 'M-001',
    scope: 'Demographics · date_einverst',
    reason: 'Source document shows 05-Oct-2020 — please verify.',
  },
  {
    id: 'ev-094',
    occurredAt: '2026-05-28T11:00:00Z',
    variant: 'admin',
    actor: 'dm_demo',
    actorRole: 'Data Manager',
    title: 'New user provisioned',
    details: 'm.mueller@meduniwien.ac.at granted Investigator role at site München via SSO JIT provisioning.',
  },
]

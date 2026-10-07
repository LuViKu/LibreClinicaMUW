import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useNotesStore } from '../notes'
import { useAuthStore } from '../auth'
import type { DiscrepancyNote } from '@/types/note'

/**
 * "Assigned to me" keeps the notes assigned to the signed-in user. It used to
 * match the fixed name `monitor_demo`, which hid every note from everybody
 * else.
 */

function signIn(username: string | null) {
  const auth = useAuthStore()
  auth.user = username === null
    ? null
    : ({ username, role: 'Monitor', activeStudy: null } as unknown as ReturnType<typeof useAuthStore>['user'])
}

function note(id: string, assignedTo: string | null): DiscrepancyNote {
  return {
    id,
    type: 'query',
    status: 'new',
    subjectId: 'M-001',
    itemOid: 'I_HEIGHT_CM',
    description: `note ${id}`,
    assignedTo,
    daysOpen: 1,
    lastActivityAt: '2026-09-29T08:00:00Z',
    thread: [],
    itemLabel: null,
    itemValue: null,
    eventCrfOid: null,
    eventName: null,
  }
}

describe('notes store — Assigned to me', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('keeps the notes assigned to the signed-in user, whatever the case of the name', () => {
    signIn('manual_monitor')
    const store = useNotesStore()
    store.rows = [
      note('1', 'manual_monitor'),
      note('2', 'monitor_demo'),
      note('3', null),
      note('4', 'Manual_Monitor'),
    ]
    store.onlyAssignedToMe = true

    expect(store.filtered.map((n) => n.id)).toEqual(['1', '4'])
  })

  it('names the signed-in user as me', () => {
    signIn('manual_investigator')
    expect(useNotesStore().me).toBe('manual_investigator')
  })

  it('keeps nothing while nobody is signed in', () => {
    signIn(null)
    const store = useNotesStore()
    store.rows = [note('1', 'monitor_demo'), note('2', null)]
    store.onlyAssignedToMe = true

    expect(store.filtered).toEqual([])
  })
})

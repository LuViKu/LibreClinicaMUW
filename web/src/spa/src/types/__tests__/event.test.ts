import { describe, expect, it } from 'vitest'

import { canCancelEvent, canEditEvent, type StudyEventStatus } from '../event'
import type { UserRole } from '../auth'

/**
 * The role + state guards behind a visit's action menu.
 *
 * The `removed` cases come from production testing on 2026-09-24: a
 * cancelled visit was still offered Edit and Cancel, and the second cancel
 * answered "already cancelled" — an error the operator could do nothing
 * about. The backend refuses both, so the menu must not offer them.
 */
describe('event action guards', () => {
  const writers: UserRole[] = ['Investigator', 'CRC', 'Data Manager', 'Administrator']

  it('offers nothing on a cancelled visit, whatever the role', () => {
    for (const role of writers) {
      expect(canEditEvent(role, 'removed')).toBe(false)
      expect(canCancelEvent(role, 'removed')).toBe(false)
    }
  })

  it('still offers edit and cancel on a live visit', () => {
    for (const role of writers) {
      for (const status of ['scheduled', 'data-entry-started', 'stopped'] as StudyEventStatus[]) {
        expect(canEditEvent(role, status)).toBe(true)
        expect(canCancelEvent(role, status)).toBe(true)
      }
    }
  })

  it('keeps refusing the terminal statuses', () => {
    for (const status of ['signed', 'locked'] as StudyEventStatus[]) {
      expect(canEditEvent('Data Manager', status)).toBe(false)
      expect(canCancelEvent('Data Manager', status)).toBe(false)
    }
  })

  it('refuses a role that may not write', () => {
    expect(canEditEvent('Monitor', 'scheduled')).toBe(false)
    expect(canCancelEvent('Monitor', 'scheduled')).toBe(false)
  })
})

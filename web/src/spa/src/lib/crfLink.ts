import type { UserRole } from '@/types/auth'

/**
 * Where a link to an event CRF goes for the signed-in roles.
 *
 * A role that may enter data opens the CRF for entry (route `crf-entry`:
 * Investigator, CRC through it, Administrator). A Monitor may only look, and
 * opens the read-only CRF (route `crf-readonly`), as legacy sends a monitor
 * to View Section Data Entry. Anyone else keeps the entry link.
 *
 * @param itemOid scrolls the CRF to this item (the `?item=` deep link)
 */
export function eventCrfLink(roles: UserRole[], eventCrfOid: string, itemOid?: string | null): string {
  const base = `/event-crfs/${encodeURIComponent(eventCrfOid)}`
  const path = viewsCrfReadOnly(roles) ? `${base}/readonly` : base
  return itemOid ? `${path}?item=${encodeURIComponent(itemOid)}` : path
}

/** True for a Monitor who holds no role that enters data. */
export function viewsCrfReadOnly(roles: UserRole[]): boolean {
  const entersData = roles.some((r) => r === 'Investigator' || r === 'CRC' || r === 'Administrator')
  return !entersData && roles.includes('Monitor')
}

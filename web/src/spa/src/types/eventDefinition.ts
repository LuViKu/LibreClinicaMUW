/**
 * Phase E A8.2 — event-definition types.
 *
 * Wire shape returned by the
 * `GET/POST/PUT /api/v1/studies/{studyOid}/event-definitions[/{sedOid}]`
 * endpoints and the per-row reorder/disable surfaces. CRF assignments
 * are owned by A8.3 — they're queried via a separate types file when
 * that slice ships.
 */

export type EventType = 'scheduled' | 'unscheduled' | 'common'

export interface EventDefinition {
  oid: string
  name: string
  description: string
  category: string
  type: string
  repeating: boolean
  ordinal: number
  /** Legacy Status.getName() string — "available", "removed", etc. */
  status: string
}

export interface CreateEventDefinitionInput {
  name: string
  type: EventType
  description?: string
  category?: string
  repeating?: boolean
}

export interface UpdateEventDefinitionInput {
  name?: string
  description?: string
  category?: string
  type?: EventType
  repeating?: boolean
}

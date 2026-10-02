/**
 * Moving existing event CRFs to another version of their CRF —
 * `/api/v1/crfs/{crfOid}/event-crf-migration` (the legacy batch CRF version
 * migration). Not the same as `migrate-to` in `crfLibrary.ts`, which only
 * changes the version that new event CRFs get.
 */

export interface MigrationRef {
  oid: string
  name: string
}

export interface MigrationVersionOption {
  oid: string
  name: string
  status: string
  /** Event CRFs of the study (and its sites) on this version. */
  eventCrfCount: number
}

export interface EventCrfMigrationOptions {
  crfOid: string
  crfName: string
  /** The study the move runs in; a site resolves to its parent study. */
  study: MigrationRef
  versions: MigrationVersionOption[]
  /** The study itself first, then its available sites. */
  sites: MigrationRef[]
  eventDefinitions: MigrationRef[]
}

/**
 * Empty `siteOids` / `eventDefinitionOids` mean all available ones.
 * `studySubjectLabel` and `eventCrfIds` narrow the move to one subject or
 * to single event CRFs. The run needs `expectedEventCrfCount` and
 * `expectedSelectionDigest`: the count and the `selectionDigest` its preview
 * showed.
 */
export interface EventCrfMigrationRequest {
  studyOid: string
  sourceVersionOid: string
  targetVersionOid: string
  siteOids?: string[]
  eventDefinitionOids?: string[]
  studySubjectLabel?: string
  eventCrfIds?: number[]
  expectedEventCrfCount?: number
  expectedSelectionDigest?: string
}

/** One event CRF. In a preview the flags say what the move will clear; in a result, what it cleared. */
export interface EventCrfMigrationRow {
  eventCrfId: number
  studySubjectLabel: string
  siteOid: string
  siteName: string
  eventDefinitionOid: string
  eventName: string
  eventOrdinal: number
  sdvVerified: boolean
  subjectSigned: boolean
  eventSigned: boolean
  eventCrfSigned: boolean
}

export type MigrationSkipReason = 'subject-locked' | 'event-locked' | 'event-crf-locked' | string

export interface EventCrfMigrationSkipped {
  row: EventCrfMigrationRow
  reason: MigrationSkipReason
}

export interface EventCrfMigrationNotOffered {
  siteOid: string
  siteName: string
  eventDefinitionOid: string
  eventName: string
  eventCrfCount: number
}

export interface EventCrfMigrationHiddenItem {
  name: string
  oid: string
  valueCount: number
}

export interface EventCrfMigrationPreview {
  crfOid: string
  crfName: string
  study: MigrationRef
  sourceVersion: MigrationRef
  targetVersion: MigrationRef
  sites: MigrationRef[]
  eventDefinitions: MigrationRef[]
  studySubjectLabel: string | null
  eventCrfCount: number
  subjectCount: number
  sdvVerifiedCount: number
  signedSubjectCount: number
  signedEventCount: number
  signedEventCrfCount: number
  eventCrfs: EventCrfMigrationRow[]
  eventCrfsTruncated: boolean
  locked: EventCrfMigrationSkipped[]
  notOffered: EventCrfMigrationNotOffered[]
  hiddenValueCount: number
  hiddenItems: EventCrfMigrationHiddenItem[]
  /** Names the event CRFs counted here; the run sends it back, so it moves exactly these. */
  selectionDigest: string
}

export interface EventCrfMigrationResult {
  crfOid: string
  crfName: string
  study: MigrationRef
  sourceVersion: MigrationRef
  targetVersion: MigrationRef
  migratedEventCrfCount: number
  subjectCount: number
  sdvClearedCount: number
  unsignedSubjectCount: number
  unsignedEventCount: number
  unsignedEventCrfCount: number
  log: EventCrfMigrationRow[]
  completedAt: string
}

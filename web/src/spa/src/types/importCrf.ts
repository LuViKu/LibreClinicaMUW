/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */

/**
 * Phase E.6 bulk-import — TypeScript mirror of the backend DTOs
 * declared under
 * {@code at.ac.meduniwien.ophthalmology.libreclinica.controller.api}.
 *
 * Keep this file in lockstep with:
 *   - ImportCrfPreviewDto.java
 *   - PreviewRowsPageDto.java
 *   - ImportCrfCommitResult.java
 *
 * The PreviewRowsPageDto wrapper is declared explicitly (not Spring's
 * PageImpl) per the playbook §4 reviewer flag — keeps the SPA
 * deserialisation path stable across Spring minor versions.
 */

/** Row status — what the preview row shows to the operator. */
export type ImportRowStatus = 'ready' | 'overwrite' | 'warning' | 'error'

/** Commit action — what the commit step will do with the row. */
export type ImportRowAction = 'insert' | 'overwrite' | 'skip' | 'flag'

/** Validator finding severity — drives operator UX colouring. */
export type ImportIssueSeverity = 'ERROR' | 'WARNING'

/** One preview row — flat projection of a single ODM ItemData leaf. */
export interface ImportCrfPreviewRow {
  status: ImportRowStatus
  action: ImportRowAction
  subjectOid: string
  eventOid: string
  crfOid: string
  itemOid: string
  /** Existing value in the database (null on insert). */
  before: string | null
  /** Incoming value from the ODM payload (null on skip). */
  after: string | null
  /** Diagnostic message for warnings + errors (null on ready/overwrite). */
  detail: string | null
}

/** One validator finding — surfaced before the commit. */
export interface ImportCrfIssue {
  /** "metadata" — OID resolution; "row" — per-row validator. */
  scope: 'metadata' | 'row' | string
  /** Subject/event/item triple or offending OID. */
  identifier: string
  severity: ImportIssueSeverity
  /** Resolved OCRERR_* text. */
  message: string
}

/**
 * Preview shape returned by {@code POST /pages/api/v1/import}.
 * {@code rows} carries the first 200 rows inline; page the rest via
 * {@link ImportCrfRowsPage}.
 */
export interface ImportCrfPreview {
  previewToken: string
  studyOid: string
  filename: string
  subjectCount: number
  eventCount: number
  crfCount: number
  rowCount: number
  insertCount: number
  overwriteCount: number
  errorCount: number
  warningCount: number
  rows: ImportCrfPreviewRow[]
  issues: ImportCrfIssue[]
}

/**
 * Wire-shape returned by
 * {@code GET /pages/api/v1/import/{token}/rows?offset=&limit=}.
 * Explicit wrapper (not PageImpl) — see file-level comment.
 */
export interface ImportCrfRowsPage {
  total: number
  offset: number
  limit: number
  rows: ImportCrfPreviewRow[]
}

/**
 * Commit summary returned by {@code POST /pages/api/v1/import/commit}.
 *
 * {@code committedAt} is server-side ISO-8601; {@code auditLogStudyId}
 * is the active study id at commit time (helper for the audit-trail
 * link).
 */
export interface ImportCrfCommitResult {
  rowsInserted: number
  rowsOverwritten: number
  rowsSkipped: number
  discrepancyNotes: number
  committedAt: string
  auditLogStudyId: number
}

/** Operator-chosen mode for the commit step. */
export type ImportOverwriteMode = 'replace' | 'skip'

/** Body for {@code POST /pages/api/v1/import/commit}. */
export interface ImportCrfCommitRequest {
  previewToken: string
  /** Required when overwrites would apply (21 CFR Part 11). */
  reasonForChange?: string
  /** Defaults server-side to "replace". */
  overwriteMode?: ImportOverwriteMode
}

/* ------------------------------------------------------------------- */
/* Discriminated-union store results                                    */
/* ------------------------------------------------------------------- */

/** Upload result — unifies success + 4xx/5xx + network branches. */
export type ImportUploadResult =
  | { ok: true; preview: ImportCrfPreview }
  | { ok: false; message: string; errors: string[] }

/** Rows-page fetch result. */
export type ImportRowsPageResult =
  | { ok: true; page: ImportCrfRowsPage }
  | { ok: false; message: string; expired: boolean }

/**
 * Commit result. {@code expired} is true when the backend returned 410
 * (unknown / expired / already-used token) — the SPA renders a
 * "re-upload the XML" CTA in that case rather than a generic error.
 */
export type ImportCommitResult =
  | { ok: true; result: ImportCrfCommitResult }
  | { ok: false; message: string; expired: boolean }

/**
 * Phase E.6 — Data Export MVP.
 *
 * Wire shape of the {@code /pages/api/v1/datasets} surface. The SPA
 * surfaces the legacy ExportDataset stack as a saved-dataset list +
 * per-dataset format picker + per-dataset file table.
 *
 * The MVP intentionally re-uses the legacy `dataset` /
 * `archived_dataset_file` / `export_format` tables — no schema
 * changes — and refers to a dataset by its numeric primary key.
 * The `oid` field on {@link DatasetDto} is the same id rendered as a
 * string so the SPA can keep its "everything has an OID" convention
 * for future evolution (the legacy schema has no OID column on
 * dataset rows).
 */

/** One saved dataset row in the SPA's table. */
export interface DatasetDto {
  /** Dataset id as a string — stable handle for SPA. */
  oid: string
  /** Dataset id as the numeric primary key. */
  id: number
  name: string
  description: string | null
  /** Username of the operator who saved the dataset, or null if removed. */
  ownerName: string | null
  /** ISO-8601 UTC. */
  dateCreated: string | null
  /** ISO-8601 UTC, or null when the dataset has never been exported. */
  lastRunAt: string | null
  /** Number of {@code archived_dataset_file} rows currently on disk. */
  fileCount: number
}

/** One generated export file row in the SPA's per-dataset sub-table. */
export interface ArchivedFileDto {
  /** archived_dataset_file.id */
  id: number
  /** Zip / xls filename. */
  name: string
  /** Pretty label — "ODM", "CSV", "Excel", "TXT", "PDF", "Other". */
  formatName: string
  /** Raw byte size on disk. */
  sizeBytes: number
  /** ISO-8601 UTC. */
  generatedAt: string | null
  /** Relative URL the SPA hits to stream the file. */
  downloadUrl: string
}

/**
 * Export formats the SPA's format picker offers. Maps 1:1 to the
 * backend's {@code DatasetsApiController.ExportFormatKey} enum.
 */
export type ExportFormat = 'odm' | 'csv' | 'tsv' | 'excel' | 'sas' | 'spss'

/** Wire shape of {@code POST /datasets/{id}/export}. */
export interface ExportTriggerRequest {
  format: ExportFormat
}

/** Wire shape of the export-trigger response. */
export interface ExportTriggerResponse {
  archivedDatasetFileId: number
  downloadUrl: string
}

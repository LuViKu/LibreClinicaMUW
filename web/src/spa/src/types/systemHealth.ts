/**
 * DR-033 — the shapes of GET /api/v1/admin/uploaders and
 * GET /api/v1/admin/storage. Hand-written: both endpoints answer with plain
 * maps, so the generated api.ts has no schema for them.
 */

export type UploaderStatus = 'ok' | 'warning' | 'disabled' | 'stopped' | 'offline'

export interface Uploader {
  id: number
  /** `export-watcher`, `optomed-bridge`, or whatever a newer program calls itself. */
  kind: string
  name: string
  version: string | null
  status: UploaderStatus
  /** Coded reasons: the program's own first, then the server's (files-failed, pending-stale, disk-low). */
  issues: string[]
  /** Set when status is `stopped`: `exit` (closed from its menu) or `session-end` (logoff / shutdown). */
  stopReason: string | null
  firstSeenAt: string | null
  lastSeenAt: string | null
  secondsSinceSeen: number
  heartbeatIntervalSec: number
  running: boolean
  enabled: boolean
  lastActivityAt: string | null
  lastUploadAt: string | null
  uploadedToday: number | null
  pendingFiles: number | null
  oldestPendingMinutes: number | null
  failedFiles: number | null
  diskFreeBytes: number | null
  diskTotalBytes: number | null
}

export interface IngestByDevice {
  device: string | null
  sourceKind: string
  lastReceivedAt: string | null
  last24h: number
  last7d: number
}

export interface UploadersResponse {
  heartbeatEnabled: boolean
  uploaders: Uploader[]
  ingestByDevice: IngestByDevice[]
}

export interface StorageStore {
  key: string
  path: string | null
  present: boolean
  usedBytes: number
  fileCount: number | null
  /** false when the scan stopped at its budget: the size is a floor. */
  complete: boolean
  fsKey: string | null
  /** true when the store is in the container's own layer — lost on recreate. */
  containerLayer: boolean
  usedBytesBefore: number | null
  beforeAt: string | null
}

export interface StorageFilesystem {
  key: string
  type: string | null
  containerLayer: boolean
  totalBytes: number
  usableBytes: number
  usedPercent: number | null
  usableBytesBefore: number | null
  beforeAt: string | null
  daysUntilFull: number | null
  stores: string[]
}

export interface StorageDatabase {
  sizeBytes: number
  sizeBytesBefore: number | null
  beforeAt: string | null
  largestTables: { name: string; bytes: number }[]
}

export interface StorageResponse {
  scanning: boolean
  sampledAt: string | null
  scanDurationMs: number | null
  stores: StorageStore[]
  filesystems: StorageFilesystem[]
  database: StorageDatabase
}

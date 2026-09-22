/**
 * DR-029 — the combined uploader's row state machine.
 *
 * Grown out of the OCT portal store, which already did the hard part: one
 * review row per thing to file, resolved against the register from what the
 * file says about itself, confirmed or parked one at a time or in a sweep,
 * undoable for a minute. What changes:
 *
 *  - a row can be an OCT scan (one file → one row per volume), a DICOM
 *    export (one row, eye and date read from the header) or a JPEG/PNG (one
 *    row, eye chosen on the row);
 *  - a batch visit: pick the visit once and every reviewable row is filed
 *    against it, because a Clarus visit is half a dozen files and re-picking
 *    it per file is how the sixth ends up on the wrong patient;
 *  - two modes, public and staff, which differ only in which client the
 *    store talks to.
 *
 * The OCT-specific parts (per-scan resolve, park, jobs, undo by job id) are
 * kept as they were; the other kinds file against a visit or land in the
 * inbox, and undo by ingest item.
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { parseE2e, type E2eScan } from '@/lib/e2eParser'
import { sniffFile, type UploadFormat, type UploadKind } from '@/lib/fileKind'
import { readDicomHints, type DicomHints } from '@/lib/dicomHeader'
import {
  commitFile,
  preflight,
  resolveRows,
  sha256OfFile,
  undoItem,
  undoJob,
  type EventCandidate,
  type ResolveCandidate,
  type ResolveRowRequest,
  type ResolveScanResult,
  type UploadMode,
} from '@/api/uploadWorkbench'

export type RowState =
  | 'parsing'
  | 'suggested'
  | 'confirmed'
  | 'novisit'
  | 'nopatient'
  | 'ambiguous'
  | 'error'
  | 'committing'
  | 'committed'
  | 'duplicate'

export type Laterality = 'OD' | 'OS' | 'OU'

export interface UploadRow {
  rowId: string
  file: File
  kind?: UploadKind
  format?: UploadFormat
  /** OCT rows: the parsed volume. One file yields one row per volume. */
  scan?: E2eScan
  /** DICOM rows: what the header said. */
  hints?: DicomHints
  /** The label the row is resolved by — the OCT header's, or what the operator typed. */
  patientId: string
  /** ISO acquisition date — the OCT scan's, the DICOM header's, or the operator's. */
  date: string | null
  /** The eye. Read from the file for OCT and DICOM; chosen on the row for an image. */
  laterality: Laterality | null
  state: RowState
  error?: string
  candidates?: ResolveCandidate[]
  selectedCandidate?: ResolveCandidate
  selectedEvent?: EventCandidate | null
  jobId?: number
  ingestItemId?: number
  committedAt?: Date
  existingIngestItemId?: number
  existingJobId?: number
  fileHash?: string
}

/** The visit a batch is filed against. */
export interface BatchVisit {
  studyEventId: number
  eventCrfId: number | null
  studySubjectId: number | null
  subjectLabel: string
  eventLabel: string
  studyName: string
  studyId: number | null
  /** ISO, or null when the picker did not carry one. */
  dateStart: string | null
}

const REVIEWABLE: ReadonlySet<RowState> = new Set(['suggested', 'novisit', 'nopatient', 'ambiguous'])

function generateRowId(): string {
  const c: { randomUUID?: () => string } | undefined =
    typeof globalThis !== 'undefined'
      ? (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
      : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  const r = (): string => Math.floor(Math.random() * 0xffffffff).toString(16).padStart(8, '0')
  return `${r()}-${r()}-${r()}-${r()}`
}

/** Local-timezone ISO date — what the operator sees on the row (Vienna clock), not UTC. */
export function isoLocalDate(d: Date | null): string | null {
  if (d === null) return null
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export const useUploadWorkbenchStore = defineStore('uploadWorkbench', () => {
  const mode = ref<UploadMode>('public')
  const rows = ref<UploadRow[]>([])
  const uploadPct = ref<Map<string, number>>(new Map())
  const batchVisit = ref<BatchVisit | null>(null)
  /** Typed label, applied to rows whose file carries none. */
  const patientIdHint = ref('')
  /** Typed date, applied to image rows (a photo says nothing about when it was taken). */
  const dateHint = ref('')

  const isParsing = computed(() => rows.value.some((r) => r.state === 'parsing'))
  const reviewReady = computed(() => rows.value.length > 0 && !isParsing.value)

  const counts = computed(() => {
    const byState: Record<RowState, number> = {
      parsing: 0, suggested: 0, confirmed: 0, novisit: 0, nopatient: 0,
      ambiguous: 0, error: 0, committing: 0, committed: 0, duplicate: 0,
    }
    for (const r of rows.value) byState[r.state] = (byState[r.state] ?? 0) + 1
    return byState
  })

  function setMode(m: UploadMode): void {
    mode.value = m
  }

  /* ---------------- adding files ---------------- */

  async function addFiles(files: File[]): Promise<void> {
    if (files.length === 0) return
    const seeds = files.map((f) => ({ rowId: generateRowId(), file: f }))
    for (const seed of seeds) {
      rows.value.push({
        rowId: seed.rowId, file: seed.file, patientId: '', date: null, laterality: null, state: 'parsing',
      })
    }
    await Promise.all(seeds.map((seed) => classify(seed.rowId, seed.file)))
    await hashAndPreflight()
    if (batchVisit.value) {
      applyBatchVisit(batchVisit.value)
    } else {
      await resolveAwaiting()
    }
  }

  /** Replace a seed row by what the file turned out to be. */
  async function classify(rowId: string, file: File): Promise<void> {
    let sniffed
    try {
      sniffed = await sniffFile(file)
    } catch {
      sniffed = null
    }
    if (!sniffed) {
      replaceSeedWithError(rowId, file, 'unsupported')
      return
    }
    if (sniffed.kind === 'e2e') {
      try {
        const scans = await parseE2e(file)
        if (scans.length === 0) {
          replaceSeedWithError(rowId, file, 'noVolumes')
          return
        }
        replaceSeed(rowId, scans.map((scan) => ({
          file, kind: 'e2e' as const, format: 'e2e' as const, scan,
          patientId: scan.patientId, date: isoLocalDate(scan.scanDate), laterality: scan.laterality,
        })))
      } catch {
        replaceSeedWithError(rowId, file, 'headerError')
      }
      return
    }
    if (sniffed.kind === 'dicom') {
      const hints = await readDicomHints(file)
      replaceSeed(rowId, [{
        file, kind: 'dicom', format: 'dicom', hints: hints ?? undefined,
        patientId: patientIdHint.value.trim(),
        date: hints?.acquisitionDate ?? (dateHint.value || null),
        laterality: hints?.laterality ?? null,
      }])
      return
    }
    replaceSeed(rowId, [{
      file, kind: 'image', format: sniffed.format,
      patientId: patientIdHint.value.trim(), date: dateHint.value || null, laterality: null,
    }])
  }

  function replaceSeed(
    rowId: string,
    replacements: Array<Omit<UploadRow, 'rowId' | 'state'>>,
  ): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    rows.value.splice(idx, 1, ...replacements.map((r, i) => ({
      ...r, rowId: i === 0 ? rowId : generateRowId(), state: 'parsing' as const,
    })))
  }

  function replaceSeedWithError(rowId: string, file: File, error: string): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    rows.value.splice(idx, 1, {
      rowId, file, patientId: '', date: null, laterality: null, state: 'error', error,
    })
  }

  /** Hash each file once, drop re-drops, and ask the backend what it already has. */
  async function hashAndPreflight(): Promise<void> {
    const targets = rows.value.filter((r) => r.state === 'parsing' && r.kind)
    const seen = new Map<File, string>()
    for (const r of targets) {
      let hash = seen.get(r.file)
      if (hash == null) {
        try {
          hash = await sha256OfFile(r.file)
          seen.set(r.file, hash)
        } catch {
          continue
        }
      }
      patch(r.rowId, { fileHash: hash })
    }
    dedupSessionRows()
    for (const r of rows.value.filter((x) => x.state === 'parsing' && x.fileHash != null)) {
      try {
        const pf = await preflight(mode.value, r.fileHash!, r.kind === 'e2e' ? r.scan!.scanIndex : null)
        if (pf.exists) {
          patch(r.rowId, {
            state: 'duplicate',
            existingIngestItemId: pf.ingestItemId ?? undefined,
            existingJobId: pf.jobId ?? undefined,
          })
        }
      } catch {
        // Best-effort: the commit-time unique index still gates the race.
      }
    }
  }

  function dedupSessionRows(): void {
    const seen = new Set<string>()
    const keep: UploadRow[] = []
    for (const r of rows.value) {
      const key = r.fileHash != null ? `${r.fileHash}::${r.scan?.scanIndex ?? '-'}` : null
      if (key !== null) {
        if (seen.has(key)) continue
        seen.add(key)
      }
      keep.push(r)
    }
    if (keep.length !== rows.value.length) rows.value = keep
  }

  /* ---------------- resolution ---------------- */

  /** Resolve every row still waiting: by its label when it has one, else it needs a person. */
  async function resolveAwaiting(): Promise<void> {
    const awaiting = rows.value.filter((r) => r.state === 'parsing' && r.kind)
    if (awaiting.length === 0) return
    const headerless = awaiting.filter((r) => !r.patientId.trim())
    const targets = awaiting.filter((r) => r.patientId.trim().length > 0)
    for (const r of headerless) patch(r.rowId, { state: 'nopatient', candidates: [] })
    if (targets.length === 0) return

    const payload: ResolveRowRequest[] = targets.map((r) => ({
      patientId: r.patientId.trim(), scanDate: r.date, laterality: r.laterality,
    }))
    let response
    try {
      response = await resolveRows(mode.value, payload)
    } catch (e) {
      const msg = e instanceof Error && e.message ? e.message : 'resolveFailed'
      for (const r of targets) patch(r.rowId, { state: 'error', error: msg })
      return
    }
    targets.forEach((target, i) => {
      const result: ResolveScanResult | undefined = response.scans[i]
      if (!result) {
        patch(target.rowId, { state: 'error', error: 'resolveFailed' })
        return
      }
      applyResolveResult(target.rowId, result)
    })
  }

  function applyResolveResult(rowId: string, result: ResolveScanResult): void {
    const [first] = result.candidates
    const auto = result.candidates.length === 1 ? first : undefined
    patch(rowId, {
      state: result.state,
      candidates: result.candidates,
      selectedCandidate: auto,
      selectedEvent: auto?.matchingEvent ?? null,
    })
  }

  /** Re-run resolution for one row against a typed label (image and DICOM rows). */
  async function resolveRowByLabel(rowId: string): Promise<void> {
    const row = rows.value.find((r) => r.rowId === rowId)
    if (!row || !REVIEWABLE.has(row.state)) return
    patch(rowId, { state: 'parsing', candidates: undefined, selectedCandidate: undefined, selectedEvent: undefined })
    await resolveAwaiting()
  }

  /* ---------------- the batch visit ---------------- */

  /**
   * File every reviewable row against one visit. Rows already sent, being
   * sent, refused or duplicate are left alone; a row the operator afterwards
   * points elsewhere by hand wins over the batch.
   */
  function setBatchVisit(v: BatchVisit | null): void {
    batchVisit.value = v
    if (v) {
      applyBatchVisit(v)
    } else {
      // Back to what each file says about itself.
      for (const r of rows.value) {
        if (REVIEWABLE.has(r.state)) {
          patch(r.rowId, { state: 'parsing', candidates: undefined, selectedCandidate: undefined, selectedEvent: undefined })
        }
      }
      void resolveAwaiting()
    }
  }

  function applyBatchVisit(v: BatchVisit): void {
    const candidate: ResolveCandidate = {
      studyId: v.studyId ?? 0,
      studyName: v.studyName,
      studyOid: '',
      studySubjectId: v.studySubjectId ?? 0,
      subjectLabel: v.subjectLabel,
      siteName: null,
      matchingEvent: null,
    }
    const event: EventCandidate = {
      studyEventId: v.studyEventId,
      eventCrfId: v.eventCrfId,
      definitionLabel: v.eventLabel,
      dateStart: v.dateStart ?? '',
      matchPolicy: 'visit-picked',
    }
    for (const r of rows.value) {
      if (r.state === 'parsing' && !r.kind) continue
      if (r.state === 'parsing' || REVIEWABLE.has(r.state)) {
        patch(r.rowId, {
          state: 'suggested',
          candidates: [candidate],
          selectedCandidate: candidate,
          selectedEvent: event,
          patientId: r.kind === 'e2e' ? r.patientId : (r.patientId || v.subjectLabel),
        })
      }
    }
  }

  function setPatientIdHint(label: string): void {
    patientIdHint.value = label
  }

  function setDateHint(iso: string): void {
    dateHint.value = iso
    for (const r of rows.value) {
      if (r.kind === 'image' && REVIEWABLE.has(r.state)) patch(r.rowId, { date: iso || null })
    }
  }

  /** Apply the typed label to rows that have none, and resolve them. */
  async function applyPatientIdHint(): Promise<void> {
    const label = patientIdHint.value.trim()
    if (!label) return
    let touched = false
    for (const r of rows.value) {
      if (r.kind !== 'e2e' && (r.state === 'nopatient' || r.state === 'novisit' || r.state === 'suggested' || r.state === 'ambiguous')
          && r.patientId !== label && !batchVisit.value) {
        patch(r.rowId, { patientId: label, state: 'parsing', candidates: undefined, selectedCandidate: undefined, selectedEvent: undefined })
        touched = true
      }
    }
    if (touched) await resolveAwaiting()
  }

  function setRowLaterality(rowId: string, laterality: Laterality | null): void {
    patch(rowId, { laterality })
  }

  /* ---------------- committing ---------------- */

  async function confirm(rowId: string): Promise<void> {
    const row = rows.value.find((r) => r.rowId === rowId)
    if (!row || row.state !== 'suggested' || !row.selectedEvent) return
    await commitRow(row, row.selectedEvent.eventCrfId, row.selectedEvent.studyEventId, false)
  }

  async function confirmAll(): Promise<void> {
    const targets = rows.value.filter((r) => r.state === 'suggested' && r.selectedEvent)
    // Sequential rather than parallel: a batch of widefield exports in flight
    // at once is how a phone on clinic Wi-Fi times out on all of them.
    for (const r of targets) {
      await commitRow(r, r.selectedEvent!.eventCrfId, r.selectedEvent!.studyEventId, false)
    }
  }

  /** Send a row without a visit: an OCT scan is parked, anything else waits in the inbox. */
  async function park(rowId: string): Promise<void> {
    const row = rows.value.find((r) => r.rowId === rowId)
    if (!row || !REVIEWABLE.has(row.state)) return
    await commitRow(row, null, null, true)
  }

  async function commitRow(
    row: UploadRow,
    eventCrfId: number | null,
    studyEventId: number | null,
    withoutVisit: boolean,
  ): Promise<void> {
    if (!row.kind) return
    // The OCT route accepts exactly one of {park, eventCrfId, studyEventId};
    // prefer the open CRF, fall back to the planned visit.
    const boundEventCrfId = withoutVisit ? null : eventCrfId
    const boundStudyEventId = withoutVisit || boundEventCrfId != null ? null : studyEventId
    patch(row.rowId, { state: 'committing' })
    uploadPct.value.set(row.rowId, 0)
    try {
      const res = await commitFile(mode.value, {
        file: row.file,
        patientId: row.patientId.trim() || null,
        scanDate: row.date,
        laterality: row.kind === 'e2e' ? row.scan!.laterality : row.laterality,
        scanIndex: row.kind === 'e2e' ? row.scan!.scanIndex : null,
        eventCrfId: boundEventCrfId,
        studyEventId: boundStudyEventId,
        park: row.kind === 'e2e' && withoutVisit,
      }, (pct) => {
        uploadPct.value.set(row.rowId, pct)
        uploadPct.value = new Map(uploadPct.value)
      })
      patch(row.rowId, {
        state: 'committed',
        jobId: res.jobId ?? undefined,
        ingestItemId: res.ingestItemId,
        committedAt: new Date(),
        laterality: (res.laterality as Laterality | null | undefined) ?? row.laterality,
        date: res.acquisitionDate ?? row.date,
      })
    } catch (e) {
      const status = (e as { status?: number }).status
      if (status === 409) {
        const body = (e as { body?: { existingIngestItemId?: number; existingJobId?: number } }).body
        patch(row.rowId, {
          state: 'duplicate',
          existingIngestItemId: body?.existingIngestItemId ?? undefined,
          existingJobId: body?.existingJobId ?? undefined,
        })
      } else {
        patch(row.rowId, { state: 'error', error: e instanceof Error && e.message ? e.message : 'uploadFailed' })
      }
    } finally {
      uploadPct.value.delete(row.rowId)
      uploadPct.value = new Map(uploadPct.value)
    }
  }

  /* ---------------- undo / dismiss ---------------- */

  async function undo(rowId: string): Promise<void> {
    const row = rows.value.find((r) => r.rowId === rowId)
    if (!row || row.state !== 'committed') return
    if (row.jobId == null && row.ingestItemId == null) return
    patch(rowId, { state: 'committing' })
    try {
      if (row.jobId != null) await undoJob(mode.value, row.jobId)
      else await undoItem(mode.value, row.ingestItemId as number)
      const back: RowState = row.selectedEvent
        ? 'suggested'
        : row.candidates && row.candidates.length === 0 ? 'nopatient' : 'novisit'
      patch(rowId, { state: back, jobId: undefined, ingestItemId: undefined, committedAt: undefined })
    } catch (e) {
      patch(rowId, { state: 'committed', error: e instanceof Error && e.message ? e.message : 'undoFailed' })
    }
  }

  function dismiss(rowId: string): void {
    rows.value = rows.value.filter((r) => r.rowId !== rowId)
  }

  function reset(): void {
    rows.value = []
    batchVisit.value = null
    patientIdHint.value = ''
    dateHint.value = ''
    uploadPct.value = new Map()
  }

  /* ---------------- per-row picks ---------------- */

  async function assignFromSearch(
    rowId: string,
    subject: { studySubjectId: number; label: string; studyId: number; studyName: string; siteName: string | null },
  ): Promise<void> {
    const current = rows.value.find((r) => r.rowId === rowId)
    if (!current) return
    const seeded: ResolveCandidate = {
      studyId: subject.studyId, studyName: subject.studyName, studyOid: '',
      studySubjectId: subject.studySubjectId, subjectLabel: subject.label,
      siteName: subject.siteName, matchingEvent: null,
    }
    patch(rowId, {
      state: 'parsing', patientId: subject.label, candidates: [seeded], selectedCandidate: seeded, selectedEvent: null,
    })
    let response
    try {
      response = await resolveRows(mode.value, [{
        patientId: subject.label, scanDate: current.date, laterality: current.laterality,
      }])
    } catch (e) {
      patch(rowId, { state: 'error', error: e instanceof Error && e.message ? e.message : 'resolveFailed' })
      return
    }
    const result = response.scans[0]
    if (!result) {
      patch(rowId, { state: 'error', error: 'resolveFailed' })
      return
    }
    applyResolveResult(rowId, result)
    // The search knows the subject; the resolver only knows the label. Keep
    // the subject when the label alone was ambiguous.
    const after = rows.value.find((r) => r.rowId === rowId)
    if (after && after.state === 'ambiguous') {
      const match = after.candidates?.find((c) => c.studySubjectId === subject.studySubjectId)
      if (match) pickStudyCandidate(rowId, match)
    }
  }

  function pickStudyCandidate(rowId: string, candidate: ResolveCandidate): void {
    const current = rows.value.find((r) => r.rowId === rowId)
    if (!current || current.state !== 'ambiguous' || !current.candidates) return
    const match = current.candidates.find((c) => c.studySubjectId === candidate.studySubjectId)
    if (!match) return
    patch(rowId, {
      state: match.matchingEvent ? 'suggested' : 'novisit',
      candidates: [match], selectedCandidate: match, selectedEvent: match.matchingEvent ?? null,
    })
  }

  function setManualVisit(
    rowId: string,
    studyEventId: number,
    eventCrfId: number | null,
    definitionLabel: string,
    dateStart: string,
  ): void {
    const current = rows.value.find((r) => r.rowId === rowId)
    if (!current) return
    if (studyEventId <= 0) {
      patch(rowId, { error: 'invalidVisit' })
      return
    }
    if (!current.selectedCandidate) {
      patch(rowId, { state: 'error', error: 'noSubject' })
      return
    }
    patch(rowId, {
      state: 'suggested',
      selectedEvent: { studyEventId, eventCrfId, definitionLabel, dateStart, matchPolicy: 'manual' },
    })
  }

  function patch(rowId: string, changes: Partial<UploadRow>): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    rows.value[idx] = { ...rows.value[idx], ...changes }
  }

  return {
    mode, rows, uploadPct, batchVisit, patientIdHint, dateHint,
    isParsing, reviewReady, counts,
    setMode, addFiles, setBatchVisit, setPatientIdHint, applyPatientIdHint, setDateHint,
    setRowLaterality, resolveRowByLabel,
    confirm, confirmAll, park, undo, dismiss, reset,
    assignFromSearch, pickStudyCandidate, setManualVisit,
  }
})

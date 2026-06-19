/**
 * Phase E retinal-inference (Wave C) — OCT-Upload-Portal store.
 *
 * Owns the per-file row state machine that drives the three-artboard
 * SPA view (ready → parsing → review). Each {@link ReviewRow} is keyed
 * by a synthetic `rowId` (uuid-ish) so the operator can drop the same
 * file twice and we'll show two rows; we never use `File.name` as the
 * identity.
 *
 * State machine per row:
 *
 *   parsing → suggested  / novisit / nopatient / ambiguous / error
 *   suggested → committing → committed (auto on confirmAll)
 *   committed → (undo within 60 s) → suggested
 *   novisit / nopatient → committing → committed (park flow)
 *   any → dismissed (UI hides; row removed from `rows`)
 *
 * The store talks to {@link @/api/octPortal}; the view never imports
 * the API client directly so vitest can `vi.mock('@/api/octPortal')`
 * once and exercise the whole UI without touching `fetch`.
 *
 * <h2>Why a Pinia store rather than view-local state</h2>
 *
 * The store is the seam tests + the view share. Keeping `addFiles` /
 * `confirm` / `park` / `undo` as store actions also keeps the view
 * declarative (it just reads `store.rows`) and means the parse-then-
 * resolve fan-out lives in one place. The mockup's "X Vorschläge
 * bestätigen" batch button maps to `confirmAll()`.
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { parseE2e, type E2eScan } from '@/lib/e2eParser'
import {
  commitScan,
  preflightSha256,
  resolveScans,
  sha256OfFile,
  undoCommit,
  type EventCandidate,
  type ResolveCandidate,
  type ResolveScanRequest,
  type ResolveScanResult,
} from '@/api/octPortal'

/** Per-row state machine values. The five "review" sub-states
 *  (suggested/novisit/nopatient/ambiguous/error) map onto the
 *  mockup's `Assignment` sub-components 1:1. */
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
  /**
   * 2026-06-19 — pre-upload dedup gate. After client-side SHA-256
   * hashing + preflight call, the backend reported that an existing
   * job carries this exact (sha256, scanIndex). The row carries the
   * pointer in {@link ReviewRow.existingJobId} so the SPA can render
   * "Bereits hochgeladen — Job #X" instead of letting the operator
   * spend bandwidth on a no-op upload.
   */
  | 'duplicate'

/** Aggregated per-file row. Shape designed for the table cells:
 *  RowMeta (file/pid/date/eye), Assignment (study/visit), Action. */
export interface ReviewRow {
  /** Synthetic id — uuid-ish, stable across rerenders. */
  rowId: string
  /** Original File handed in by the dropzone; reused by /commit. */
  file: File
  /** Populated after parseE2e resolves; the parser may emit multiple
   *  scans per file → one ReviewRow per scan. */
  scan?: E2eScan
  state: RowState
  /** Error message — populated only in the `error` state. */
  error?: string
  /** Backend match candidates — empty when state ∈ {nopatient, error}. */
  candidates?: ResolveCandidate[]
  /** Operator's pick when {@code candidates.length > 1} (ambiguous),
   *  or the single auto-pick when there's only one candidate. */
  selectedCandidate?: ResolveCandidate
  /** Operator's visit pick. `null` = park; populated EventCandidate =
   *  bind to that event_crf. */
  selectedEvent?: EventCandidate | null
  /** Set after /commit returns; needed for the 60-s undo window. */
  jobId?: number
  committedAt?: Date
  /**
   * 2026-06-19 — pointer to a prior upload that already carries the
   * same (e2e_sha256, scan_index). Populated when the preflight call
   * returns {@code exists=true}; rendered as "Job #N" in the
   * duplicate state's UX so the operator knows where the scan
   * landed previously. Undefined for non-duplicate rows.
   */
  existingJobId?: number
}

/** UUID generator that works in jsdom (where crypto.randomUUID is
 *  missing on Node < 19). Mirrors the api/client.ts fallback so we
 *  don't pull in a third dependency. */
function generateRowId(): string {
  const c: { randomUUID?: () => string } | undefined =
    typeof globalThis !== 'undefined'
      ? (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
      : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  // Math.random is fine for row identity, never for auth.
  const r = (): string => Math.floor(Math.random() * 0xffffffff).toString(16).padStart(8, '0')
  return `${r()}-${r()}-${r()}-${r()}`
}

/** Format a Date as ISO yyyy-MM-dd in the LOCAL timezone — matches
 *  what the operator sees on the row (Vienna clock) rather than UTC.
 *  Keeps /resolve, /commit and the visible "Scan-Datum" cell in sync.
 *  Returns null when scanDate is null (fundus-only files have no
 *  acquisition-time chunk; the backend handles null as
 *  "search by patientId only"). */
function isoLocalDate(d: Date | null): string | null {
  if (d === null) return null
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export const useOctPortalStore = defineStore('octPortal', () => {
  /** All rows the operator has dropped this session (parsing + review
   *  + committed + errored — dismissed rows are pruned). */
  const rows = ref<ReviewRow[]>([])

  /**
   * 2026-06-19 — per-row upload progress percent (0..100). Populated
   * by {@link commitScan}'s XHR {@code onProgress} callback; the
   * ReviewRow component binds this to the {@code --muw-portal-fill-pct}
   * CSS custom property so the L→R green fill reflects REAL upload
   * progress instead of a deterministic JS timer (which diverged from
   * reality on slow disks + over-painted on 409 duplicates).
   *
   * <p>Cleared back to absent on terminal row states (committed,
   * error) so a re-paint doesn't show a stale percent. The keyed Map
   * shape lets the SPA distinguish "no progress yet" (key absent)
   * from "0 %" (start of upload).
   */
  const uploadPct = ref<Map<string, number>>(new Map())

  /** True while at least one row is in the `parsing` state — drives
   *  the view's `parsing` artboard vs `review`. */
  const isParsing = computed(() => rows.value.some((r) => r.state === 'parsing'))
  /** True once every row has left the `parsing` state — flips the
   *  view from the parsing artboard into review. */
  const reviewReady = computed(() => rows.value.length > 0 && !isParsing.value)

  /** Per-state counts — drives the SummaryBar pills + the batch
   *  confirm button's count. */
  const counts = computed(() => {
    const byState: Record<RowState, number> = {
      parsing: 0,
      suggested: 0,
      confirmed: 0,
      novisit: 0,
      nopatient: 0,
      ambiguous: 0,
      error: 0,
      committing: 0,
      committed: 0,
      duplicate: 0,
    }
    for (const r of rows.value) {
      byState[r.state] = (byState[r.state] ?? 0) + 1
    }
    return byState
  })

  /** Add freshly-dropped files. Each file is parsed → one row per
   *  scan emitted by `parseE2e`. Non-.e2e files (or parser failures)
   *  surface a single row in the `error` state so the operator can
   *  see what was rejected. */
  async function addFiles(files: File[]): Promise<void> {
    if (files.length === 0) return
    // Pass 1 — seed one placeholder row per file in the `parsing` state
    // so the parsing artboard renders immediately even for slow disks.
    const seeds: { rowId: string; file: File }[] = files.map((f) => ({
      rowId: generateRowId(),
      file: f,
    }))
    for (const seed of seeds) {
      rows.value.push({
        rowId: seed.rowId,
        file: seed.file,
        state: 'parsing',
      })
    }

    // Pass 2 — parse every file (in parallel) and replace each seed
    // row with one row per emitted scan (or a single error row when
    // parsing throws / the file is not .e2e).
    await Promise.all(
      seeds.map(async (seed) => {
        const lowerName = seed.file.name.toLowerCase()
        // The portal accepts .e2e only — anything else is a clear
        // operator error the mockup surfaces as "Kein .e2e-Format —
        // Datei übersprungen".
        if (!lowerName.endsWith('.e2e')) {
          replaceSeedWithError(seed.rowId, seed.file, 'Kein .e2e-Format — Datei übersprungen')
          return
        }
        try {
          const scans = await parseE2e(seed.file)
          if (scans.length === 0) {
            replaceSeedWithError(
              seed.rowId,
              seed.file,
              'Keine OCT-Volumen im Header gefunden',
            )
            return
          }
          replaceSeedWithScans(seed.rowId, seed.file, scans)
        } catch (e) {
          const msg =
            e instanceof Error && e.message
              ? `Header-Lesefehler: ${e.message}`
              : 'Header-Lesefehler'
          replaceSeedWithError(seed.rowId, seed.file, msg)
        }
      }),
    )

    // Pass 3 — fan out one /resolve call covering every row that
    // survived parsing. We deliberately batch (one HTTP round trip)
    // so the operator's drag-of-30 doesn't fire 30 requests.
    await resolveSurvivors()
  }

  function replaceSeedWithScans(rowId: string, file: File, scans: E2eScan[]): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    // Each parsed volume gets its own row — same file, different scan.
    const replacements: ReviewRow[] = scans.map((scan, i) => ({
      rowId: i === 0 ? rowId : generateRowId(),
      file,
      scan,
      // Hold in `parsing` until /resolve returns; the view shows
      // the "Studie & Visite werden ermittelt…" tail line.
      state: 'parsing',
    }))
    rows.value.splice(idx, 1, ...replacements)
  }

  function replaceSeedWithError(rowId: string, file: File, message: string): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    rows.value.splice(idx, 1, {
      rowId,
      file,
      state: 'error',
      error: message,
    })
  }

  /** Run /resolve against every parsing-but-already-has-scan row.
   *
   *  Rows whose parsed {@code patientId} is empty get short-circuited
   *  into the `nopatient` state directly — the backend's /resolve
   *  endpoint returns `nopatient` for blank ids anyway (see
   *  PublicOctUploadController:147), and skipping the round-trip
   *  prevents downstream "patientId is required" errors when an
   *  operator later clicks "Bestätigen" on a header-less scan.
   *  Operators clear the gap via the row's "Patient suchen" button. */
  async function resolveSurvivors(): Promise<void> {
    const allTargets = rows.value.filter((r) => r.state === 'parsing' && r.scan)
    if (allTargets.length === 0) return

    // 2026-06-19 — pre-upload dedup gate. Hash each file's bytes
    // client-side and ask the backend whether the (sha256, scanIndex)
    // pair already exists. Rows that match short-circuit to the
    // 'duplicate' state, sparing the operator a 200 MB upload that
    // would land on the same row anyway. The hash is computed once
    // per FILE (not per scan_index) and reused across all of that
    // file's review rows below.
    //
    // Best-effort: a network failure on /preflight leaves the row in
    // the original flow (commit-time uniqueness still gates the
    // race). Hash compute is awaited sequentially per file so the
    // browser doesn't OOM on a parade of large .e2e's.
    const seenFileHash = new Map<File, string>()
    for (const r of allTargets) {
      let hash = seenFileHash.get(r.file)
      if (hash == null) {
        try {
          hash = await sha256OfFile(r.file)
          seenFileHash.set(r.file, hash)
        } catch {
          // Hash compute failed — skip preflight for this file; commit
          // path will still catch the duplicate at unique-index time.
          continue
        }
      }
      try {
        const pf = await preflightSha256(hash, r.scan!.scanIndex)
        if (pf.exists && pf.jobId != null) {
          const idx = rows.value.findIndex((x) => x.rowId === r.rowId)
          if (idx !== -1) {
            rows.value[idx] = {
              ...rows.value[idx],
              state: 'duplicate',
              existingJobId: pf.jobId,
            }
          }
        }
      } catch {
        // /preflight 5xx / network — leave row in parsing so the
        // regular resolve flow takes over.
      }
    }

    // Now build the targets list for /resolve, EXCLUDING rows that
    // the preflight gate already flipped to 'duplicate'.
    const survivors = rows.value.filter((r) => r.state === 'parsing' && r.scan)
    // Split: empty patientId → directly nopatient; rest → /resolve.
    const headerLess = survivors.filter((r) => !r.scan!.patientId.trim())
    const targets = survivors.filter((r) => r.scan!.patientId.trim().length > 0)

    for (const r of headerLess) {
      const idx = rows.value.findIndex((x) => x.rowId === r.rowId)
      if (idx !== -1) {
        rows.value[idx] = {
          ...rows.value[idx],
          state: 'nopatient',
          candidates: [],
        }
      }
    }

    if (targets.length === 0) return
    const payload: ResolveScanRequest[] = targets.map((r) => ({
      patientId: r.scan!.patientId,
      scanDate: isoLocalDate(r.scan!.scanDate),
      laterality: r.scan!.laterality,
    }))
    let response
    try {
      response = await resolveScans(payload)
    } catch (e) {
      // Network-level failure or 5xx — flip every awaiting row to
      // `error` rather than leaving them spinning. The operator can
      // dismiss + retry once the backend recovers.
      const msg =
        e instanceof Error && e.message
          ? `Studie/Visite-Lookup fehlgeschlagen: ${e.message}`
          : 'Studie/Visite-Lookup fehlgeschlagen'
      for (const r of targets) {
        const idx = rows.value.findIndex((x) => x.rowId === r.rowId)
        if (idx !== -1) {
          rows.value[idx] = { ...rows.value[idx], state: 'error', error: msg }
        }
      }
      return
    }
    // Pair responses to rows positionally — resolveScans preserves order.
    for (let i = 0; i < targets.length; i++) {
      const target = targets[i]
      const result: ResolveScanResult | undefined = response.scans[i]
      if (!result) {
        flipRowToError(target.rowId, 'Keine Antwort vom Resolver')
        continue
      }
      applyResolveResult(target.rowId, result)
    }
  }

  function applyResolveResult(rowId: string, result: ResolveScanResult): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    const current = rows.value[idx]
    const [first] = result.candidates
    const auto = result.candidates.length === 1 ? first : undefined
    const event = auto?.matchingEvent ?? null
    rows.value[idx] = {
      ...current,
      state: result.state,
      candidates: result.candidates,
      selectedCandidate: auto,
      selectedEvent: event,
    }
  }

  function flipRowToError(rowId: string, message: string): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    rows.value[idx] = { ...rows.value[idx], state: 'error', error: message }
  }

  /** Confirm a single row — calls /commit with the pre-selected event,
   *  flips state to `committing` while in flight, then `committed`. */
  async function confirm(rowId: string): Promise<void> {
    const row = rows.value.find((r) => r.rowId === rowId)
    if (!row) return
    if (row.state !== 'suggested') return
    if (!row.selectedCandidate || !row.selectedEvent) return
    await commitRow(row, row.selectedEvent.eventCrfId, false)
  }

  /** Click "Bestätigen" for every row currently in `suggested`. The
   *  mockup's "X Vorschläge bestätigen" button calls this. */
  async function confirmAll(): Promise<void> {
    const targets = rows.value.filter((r) => r.state === 'suggested')
    await Promise.all(
      targets.map((r) => {
        if (!r.selectedCandidate || !r.selectedEvent) return Promise.resolve()
        return commitRow(r, r.selectedEvent.eventCrfId, false)
      }),
    )
  }

  /** Park a row whose scan doesn't have a matching event — operator
   *  chose "Später zuordnen" / "Parken". Calls /commit with
   *  park=true; the backend stamps status='PARKED' and returns. */
  async function park(rowId: string): Promise<void> {
    const row = rows.value.find((r) => r.rowId === rowId)
    if (!row) return
    if (row.state !== 'novisit' && row.state !== 'nopatient' && row.state !== 'ambiguous')
      return
    await commitRow(row, null, true)
  }

  async function commitRow(
    row: ReviewRow,
    eventCrfId: number | null,
    parkFlag: boolean,
  ): Promise<void> {
    if (!row.scan) return
    flipRowState(row.rowId, 'committing')
    // Reset progress to 0; the XHR.upload events drive it from there.
    uploadPct.value.set(row.rowId, 0)
    try {
      const res = await commitScan(
        {
          file: row.file,
          patientId: row.scan.patientId,
          scanDate: isoLocalDate(row.scan.scanDate),
          laterality: row.scan.laterality,
          scanIndex: row.scan.scanIndex,
          eventCrfId,
          park: parkFlag,
        },
        (pct) => {
          // 2026-06-19 — real upload-progress callback from XHR. Map
          // shape (rather than direct mutation on the row) keeps the
          // hot path off the rows array so a 1 KB/sec stream of pct
          // updates doesn't churn the rows-array reactivity.
          uploadPct.value.set(row.rowId, pct)
          // Trigger Map reactivity in Vue 3 (Maps need replacement
          // for shallow tracking — replace the Map reference so the
          // computed bindings in ReviewRow refresh).
          uploadPct.value = new Map(uploadPct.value)
        },
      )
      const idx = rows.value.findIndex((r) => r.rowId === row.rowId)
      if (idx === -1) return
      rows.value[idx] = {
        ...rows.value[idx],
        state: 'committed',
        jobId: res.jobId,
        committedAt: new Date(),
      }
      // Clear progress so the next render doesn't carry a stale 100 %.
      uploadPct.value.delete(row.rowId)
      uploadPct.value = new Map(uploadPct.value)
    } catch (e) {
      const msg =
        e instanceof Error && e.message
          ? `Upload fehlgeschlagen: ${e.message}`
          : 'Upload fehlgeschlagen'
      flipRowToError(row.rowId, msg)
      // Drop the progress so the fill doesn't linger on the error row.
      uploadPct.value.delete(row.rowId)
      uploadPct.value = new Map(uploadPct.value)
    }
  }

  /** Undo a recent commit — backend rejects with 410 outside the 60 s
   *  window; in that case we flip back to `committed` so the operator
   *  sees the upload stuck (the row still carries a jobId). */
  async function undo(rowId: string): Promise<void> {
    const row = rows.value.find((r) => r.rowId === rowId)
    if (!row || row.state !== 'committed' || row.jobId == null) return
    const jobId = row.jobId
    // Optimistic flip — the UI animates back to the suggested state
    // so the operator isn't left staring at an unchanged row while
    // the round-trip finishes.
    flipRowState(rowId, 'committing')
    try {
      await undoCommit(jobId)
      const idx = rows.value.findIndex((r) => r.rowId === rowId)
      if (idx === -1) return
      // After a successful undo the row goes back to whatever the
      // resolve verdict was. We've kept candidates + selectedEvent
      // on the row throughout, so we can just flip the state back.
      const back: RowState = row.selectedEvent
        ? 'suggested'
        : row.candidates && row.candidates.length === 0
          ? 'nopatient'
          : 'novisit'
      rows.value[idx] = {
        ...rows.value[idx],
        state: back,
        jobId: undefined,
        committedAt: undefined,
      }
    } catch (e) {
      // Most likely 410 outside the window — keep the commit state.
      const msg =
        e instanceof Error && e.message
          ? `Rückgängig fehlgeschlagen: ${e.message}`
          : 'Rückgängig fehlgeschlagen'
      // Restore committed + surface the error inline so the operator
      // sees why undo didn't take effect.
      const idx = rows.value.findIndex((r) => r.rowId === rowId)
      if (idx !== -1) {
        rows.value[idx] = {
          ...rows.value[idx],
          state: 'committed',
          error: msg,
        }
      }
    }
  }

  /** Remove an error row from the queue — the mockup's red ✕ chip. */
  function dismiss(rowId: string): void {
    rows.value = rows.value.filter((r) => r.rowId !== rowId)
  }

  function flipRowState(rowId: string, next: RowState): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    rows.value[idx] = { ...rows.value[idx], state: next }
  }

  /** Reset the entire queue — useful between sessions. Not wired into
   *  the UI yet but cheap to expose for tests. */
  function reset(): void {
    rows.value = []
  }

  /**
   * Wave 2B — operator picked a subject in the PatientSearchModal.
   *
   * Replaces the row's candidate with the picked subject so the
   * subject context is no longer "missing" and re-runs the per-row
   * /resolve call so the matching event(s) for the scan date come
   * back. The row transitions out of {@code nopatient} into whatever
   * the new resolve verdict is — typically {@code suggested} (event
   * found) or {@code novisit} (no event for the scan date).
   *
   * <p>Lightweight shape — the modal hit only carries study + subject
   * label + site context, not the matchingEvent the resolve endpoint
   * returns. We synthesise a {@link ResolveCandidate} from the hit so
   * the row's UI (StudyChip, subjectLabel) keeps working pre-resolve,
   * then let the resolve response overwrite it.
   */
  async function assignFromSearch(
    rowId: string,
    subject: {
      studySubjectId: number
      label: string
      studyId: number
      studyName: string
      siteName: string | null
    },
  ): Promise<void> {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    const current = rows.value[idx]
    if (!current.scan) return
    // Seed the row with the picked subject so the UI updates
    // immediately while the resolve call is in flight; flip to
    // `parsing` to drive the spinner.
    const seeded: ResolveCandidate = {
      studyId: subject.studyId,
      studyName: subject.studyName,
      studyOid: '',
      studySubjectId: subject.studySubjectId,
      subjectLabel: subject.label,
      siteName: subject.siteName,
      matchingEvent: null,
    }
    rows.value[idx] = {
      ...current,
      state: 'parsing',
      candidates: [seeded],
      selectedCandidate: seeded,
      selectedEvent: null,
    }

    // Re-run /resolve so the backend recomputes the matching event
    // for the scan date against the picked subject.
    let response
    try {
      response = await resolveScans([{
        patientId: subject.label,
        scanDate: isoLocalDate(current.scan.scanDate),
        laterality: current.scan.laterality,
      }])
    } catch (e) {
      const msg =
        e instanceof Error && e.message
          ? `Studie/Visite-Lookup fehlgeschlagen: ${e.message}`
          : 'Studie/Visite-Lookup fehlgeschlagen'
      flipRowToError(rowId, msg)
      return
    }
    const result: ResolveScanResult | undefined = response.scans[0]
    if (!result) {
      flipRowToError(rowId, 'Keine Antwort vom Resolver')
      return
    }
    applyResolveResult(rowId, result)
  }

  /**
   * Wave 2C follow-up (2026-06-19) — operator picked a study in the
   * StudyPickerModal (the ambiguous-row disambiguation flow).
   *
   * <p>Two outcomes depending on the picked candidate:
   *  - candidate carries a {@code matchingEvent} → flip to {@code
   *    suggested} with the matching event pre-selected, so the
   *    operator's next "Bestätigen" sweep commits it.
   *  - candidate carries no event → flip to {@code novisit} with the
   *    candidate selected; the operator can next open the
   *    VisitPickerModal to pick from that cohort's events, or click
   *    "Später zuordnen" to park the bind.
   *
   * <p>This is a client-side state transition only — no /resolve
   * round-trip required, because {@code row.candidates} already
   * carries every option from the original /resolve response.
   * Defensive guards: only acts on {@code state === 'ambiguous'} rows
   * whose candidate set actually contains the picked study_subject_id.
   */
  function pickStudyCandidate(rowId: string, candidate: ResolveCandidate): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    const current = rows.value[idx]
    if (current.state !== 'ambiguous') return
    if (!current.candidates) return
    const match = current.candidates.find(
      (c) => c.studySubjectId === candidate.studySubjectId,
    )
    if (!match) return
    const nextState: RowState = match.matchingEvent ? 'suggested' : 'novisit'
    rows.value[idx] = {
      ...current,
      state: nextState,
      candidates: [match],
      selectedCandidate: match,
      selectedEvent: match.matchingEvent ?? null,
    }
  }

  /**
   * Wave 2B — operator picked a visit in the VisitPickerModal.
   *
   * Bypasses the auto-resolve path and binds the row to the chosen
   * event_crf. Transitions the row to {@code suggested} so the
   * operator's next confirm-all sweep includes it.
   *
   * <p>If the picker emitted {@code eventCrfId: -1} (no started CRF
   * for the picked event), surface an error on the row so the
   * operator sees why the bind didn't take.
   */
  function setManualVisit(
    rowId: string,
    eventCrfId: number,
    definitionLabel: string,
    dateStart: string,
  ): void {
    const idx = rows.value.findIndex((r) => r.rowId === rowId)
    if (idx === -1) return
    const current = rows.value[idx]
    if (eventCrfId <= 0) {
      rows.value[idx] = {
        ...current,
        error: 'Keine Eingabemaske für diese Visite — bitte zuerst Daten starten.',
      }
      return
    }
    if (!current.selectedCandidate) {
      // Defensive — should never fire from the UI path (the visit
      // picker is only mounted when a candidate exists), but surface
      // a clean error rather than crashing if it does.
      flipRowToError(rowId, 'Kein Studienteilnehmer ausgewählt')
      return
    }
    rows.value[idx] = {
      ...current,
      state: 'suggested',
      selectedEvent: {
        eventCrfId,
        definitionLabel,
        dateStart,
        matchPolicy: 'manual',
      },
    }
  }

  return {
    rows,
    uploadPct,
    isParsing,
    reviewReady,
    counts,
    addFiles,
    confirm,
    confirmAll,
    park,
    undo,
    dismiss,
    reset,
    assignFromSearch,
    setManualVisit,
    pickStudyCandidate,
  }
})

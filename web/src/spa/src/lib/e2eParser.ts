/**
 * Heidelberg Spectralis `.e2e` header parser — client-side metadata only.
 *
 * The OCT-Upload-Portal uses this to extract patient ID, scan date,
 * laterality, and B-scan count from a `.e2e` file BEFORE uploading the
 * multi-megabyte binary, so the operator can see per-scan match candidates.
 *
 * <p>Heidelberg does not publish the format. This parser reverse-engineers
 * the chunk layout based on
 * <a href="https://bitbucket.org/uocte/uocte/wiki/Heidelberg%20File%20Format">
 * the uocte project's wiki notes</a> as crystallised in
 * <a href="https://github.com/marksgraham/OCT-Converter">marksgraham/OCT-Converter</a>
 * (the de-facto Python reference). The Python sidecar in this repo uses
 * the same library server-side; this TypeScript reader mirrors its
 * directory-walking + chunk-dispatch algorithm but stops after the
 * metadata we care about (no image decode, no LUT, no segmentation).
 *
 * <p>Volume grouping: a single `.e2e` can carry multiple OCT volumes
 * (different lateralities, different acquisition runs). Each chunk header
 * carries `(patient_db_id, study_id, series_id)` identifiers. We group
 * by that triple and emit one {@link E2eScan} per group. The Python
 * sidecar today picks {@code max(volumes, key=num_slices)} and discards
 * the others; this parser deliberately surfaces ALL of them so the portal
 * can show the operator one row per scan.
 *
 * <p>Byte layout (little-endian throughout):
 * <pre>
 *   File header                36 bytes
 *   Main directory             52 bytes  (linked list via current/prev)
 *   Sub-directory entry        44 bytes  ×num_entries per directory
 *   Chunk header               60 bytes  + payload of `size` bytes
 *
 *   Chunk types we read:
 *     3      pre_data        (laterality char at internal offset 4)
 *     9      patient_data    (patient_id Latin-1 25B at internal offset 102)
 *     11     lat_structure   (laterality char at internal offset 14, fallback)
 *     10004  bscan_metadata  (acquisitionTime u64 ticks at offset 88,
 *                             numImages u32 at offset 64)
 * </pre>
 *
 * <p>If reading the format degrades on future Spectralis exports (e.g.
 * tighter anonymisation strips chunk 9), the upload portal falls back to
 * a server-side `/parse` endpoint — see the runbook for the wave-B status.
 */

export interface E2eScan {
  /** Patient ID extracted from chunk type 9. Latin-1, trimmed of NULs. */
  patientId: string;
  /** Acquisition timestamp from chunk type 10004 (Windows ticks → Date). */
  scanDate: Date;
  /** OD = right eye, OS = left eye. Defaults to OD when no laterality chunk parses. */
  laterality: 'OD' | 'OS';
  /** 0-based ordinal of this volume within the file. */
  scanIndex: number;
  /** Number of B-scans in the volume (best-effort from numImages or slice_id). */
  nBscans: number;
}

/* ────────────────────────────────────────────────────────────────────── */
/*  Layout constants                                                      */
/* ────────────────────────────────────────────────────────────────────── */

const MULTI_VOLUME_MAGIC = 'E2EMultipleVolumeFile';
const FILE_HEADER_BYTES = 36;
const MAIN_DIRECTORY_BYTES = 52;
const SUB_DIRECTORY_ENTRY_BYTES = 44;
const CHUNK_HEADER_BYTES = 60;

/** Field offsets within the 52-byte main directory header. */
const MAIN_DIR_NUM_ENTRIES_OFFSET = 36;
const MAIN_DIR_CURRENT_OFFSET = 40;
const MAIN_DIR_PREV_OFFSET = 44;

/** Field offsets within a 44-byte sub-directory entry.
 *
 * NB: only pos/start/size are read here because we re-derive every other
 * field from the chunk header itself (which is more reliable across
 * Spectralis versions). The other offsets are documented inline at the
 * `generate_fixtures.py` side for symmetry — keep both in sync if the
 * format ever changes. */
const SUB_ENTRY_POS_OFFSET = 0;
const SUB_ENTRY_START_OFFSET = 4;
const SUB_ENTRY_SIZE_OFFSET = 8;

/** Field offsets within a 60-byte chunk header. */
const CHUNK_PATIENT_DB_ID_OFFSET = 32;
const CHUNK_STUDY_ID_OFFSET = 36;
const CHUNK_SERIES_ID_OFFSET = 40;
const CHUNK_SLICE_ID_OFFSET = 44;
const CHUNK_TYPE_OFFSET = 52;

/** Chunk-type integer constants (see {@link e2eParser} module doc). */
const CHUNK_TYPE_PRE_DATA = 3;
const CHUNK_TYPE_PATIENT_DATA = 9;
const CHUNK_TYPE_LATERALITY = 11;
const CHUNK_TYPE_BSCAN_METADATA = 10004;

/** Field offsets WITHIN each chunk's payload (after the 60-byte header). */
const PATIENT_DATA_ID_OFFSET = 31 + 51 + 15 + 4 + 1; // 102 — first_name(31) + surname(51) + title(15) + birthdate(4) + sex(1)
const PATIENT_DATA_ID_LEN = 25;

const PRE_DATA_LATERALITY_OFFSET = 4; // 4-byte u32 unknown then 1-byte laterality char

const LAT_STRUCT_LATERALITY_OFFSET = 14; // 14 × u8 unknown then 1-byte laterality

const BSCAN_NUM_IMAGES_OFFSET = 64;
const BSCAN_ACQUISITION_TIME_OFFSET = 88;

/** Windows-tick → Unix epoch conversion: ticks of 100 ns from 1601-01-01 UTC. */
const WINDOWS_TICKS_PER_SECOND = 10_000_000n;
const WINDOWS_EPOCH_TO_UNIX_SECONDS = 11_644_473_600n;

/* ────────────────────────────────────────────────────────────────────── */
/*  Helpers                                                               */
/* ────────────────────────────────────────────────────────────────────── */

/** Build a `(patient_db_id, study_id, series_id)` grouping key. */
function volumeKey(patientDbId: number, studyId: number, seriesId: number): string {
  return `${patientDbId}_${studyId}_${seriesId}`;
}

/** Decode a Latin-1 byte run, stripping trailing NULs. */
function decodeLatin1(bytes: Uint8Array): string {
  let end = bytes.length;
  while (end > 0 && bytes[end - 1] === 0) end--;
  let s = '';
  for (let i = 0; i < end; i++) {
    s += String.fromCharCode(bytes[i]!);
  }
  return s;
}

/** Windows ticks (100-ns since 1601-01-01) → JS Date. */
function windowsTicksToDate(ticks: bigint): Date {
  // (ticks / 10_000_000) - 11_644_473_600 yields unix seconds.
  const unixSeconds = ticks / WINDOWS_TICKS_PER_SECOND - WINDOWS_EPOCH_TO_UNIX_SECONDS;
  return new Date(Number(unixSeconds) * 1000);
}

/** Convert "R"/"L" → OD/OS. Anything else returns null. */
function lateralityCharToCode(c: string): 'OD' | 'OS' | null {
  if (c === 'R' || c === 'r') return 'OD';
  if (c === 'L' || c === 'l') return 'OS';
  return null;
}

/** Per-volume accumulator while we walk the chunks. */
interface VolumeAccumulator {
  patientDbId: number;
  studyId: number;
  seriesId: number;
  /** First time we saw this volume in the chunk stream — preserves order. */
  firstSeenOrdinal: number;
  laterality: 'OD' | 'OS' | null;
  acquisitionTicks: bigint | null;
  /** Max numImages reported by any bscan_metadata chunk for this volume. */
  numImages: number;
  /** Max (slice_id / 2 + 1) seen for any bscan_metadata chunk — fallback B-scan count. */
  maxSliceCount: number;
}

/* ────────────────────────────────────────────────────────────────────── */
/*  Parser                                                                */
/* ────────────────────────────────────────────────────────────────────── */

/**
 * Parse a Heidelberg Spectralis `.e2e` file's HEADER and return one
 * {@link E2eScan} per OCT volume contained within.
 *
 * @throws Error if the file is too short or the header magic is missing.
 */
export async function parseE2e(file: File): Promise<E2eScan[]> {
  const buf = new Uint8Array(await file.arrayBuffer());
  if (buf.length < FILE_HEADER_BYTES + MAIN_DIRECTORY_BYTES) {
    throw new Error(
      `parseE2e: file too short (${buf.length} bytes) — missing E2E header magic`,
    );
  }
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);

  // ── 1. multi-volume sentinel + 12-byte ASCII magic check ───────────────
  const byteSkip = detectMultiVolumeSkip(buf);
  const magic = decodeLatin1(buf.subarray(byteSkip, byteSkip + 12));
  if (!isPlausibleHeaderMagic(magic)) {
    throw new Error(
      `parseE2e: unexpected header magic ${JSON.stringify(magic)} — not a Spectralis .e2e file`,
    );
  }

  // ── 2. walk the directory linked list ──────────────────────────────────
  //
  // The file header + first main_directory_chunk live back-to-back at
  // `byteSkip`. The first main directory's `current` pointer is the start
  // of the linked list. Subsequent main_directory chunks at `current`
  // carry a `prev` pointer; we follow prev → prev → … until prev == 0.
  const firstMainDirOffset = byteSkip + FILE_HEADER_BYTES;
  const firstMainDir = readMainDirectory(view, firstMainDirOffset);

  const directoryStack: number[] = [];
  let current = firstMainDir.current;
  while (current !== 0) {
    const absolute = current + byteSkip;
    if (absolute + MAIN_DIRECTORY_BYTES > buf.length) {
      // Defensive: malformed pointer would walk off the end of the buffer.
      break;
    }
    directoryStack.push(absolute);
    const next = readMainDirectory(view, absolute);
    if (next.prev === current) break; // self-cycle guard
    current = next.prev;
  }

  // ── 3. collect (start, size) of every chunk via sub-directory entries ──
  const chunkPositions: Array<{ start: number; size: number }> = [];
  for (const dirOffset of directoryStack) {
    const dir = readMainDirectory(view, dirOffset);
    let entryOffset = dirOffset + MAIN_DIRECTORY_BYTES;
    for (let i = 0; i < dir.numEntries; i++) {
      if (entryOffset + SUB_DIRECTORY_ENTRY_BYTES > buf.length) break;
      const pos = view.getUint32(entryOffset + SUB_ENTRY_POS_OFFSET, true);
      const start = view.getUint32(entryOffset + SUB_ENTRY_START_OFFSET, true);
      const size = view.getUint32(entryOffset + SUB_ENTRY_SIZE_OFFSET, true);
      // oct_converter skips entries where start <= pos (those are
      // directory back-pointers, not actual chunks).
      if (start > pos && start > 0) {
        chunkPositions.push({ start, size });
      }
      entryOffset += SUB_DIRECTORY_ENTRY_BYTES;
    }
  }

  // ── 4. iterate chunks, populate per-volume accumulators ────────────────
  const volumes = new Map<string, VolumeAccumulator>();
  /** patient_db_id → patientId (we expect one per file but key it just in case). */
  const patientIds = new Map<number, string>();
  let ordinalCounter = 0;

  for (const { start } of chunkPositions) {
    const chunkOffset = start + byteSkip;
    if (chunkOffset + CHUNK_HEADER_BYTES > buf.length) continue;

    const patientDbId = view.getUint32(chunkOffset + CHUNK_PATIENT_DB_ID_OFFSET, true);
    const studyId = view.getUint32(chunkOffset + CHUNK_STUDY_ID_OFFSET, true);
    const seriesId = view.getUint32(chunkOffset + CHUNK_SERIES_ID_OFFSET, true);
    const sliceId = view.getInt32(chunkOffset + CHUNK_SLICE_ID_OFFSET, true);
    const type = view.getUint32(chunkOffset + CHUNK_TYPE_OFFSET, true);
    const payloadOffset = chunkOffset + CHUNK_HEADER_BYTES;

    switch (type) {
      case CHUNK_TYPE_PATIENT_DATA: {
        if (payloadOffset + PATIENT_DATA_ID_OFFSET + PATIENT_DATA_ID_LEN > buf.length) break;
        const idBytes = buf.subarray(
          payloadOffset + PATIENT_DATA_ID_OFFSET,
          payloadOffset + PATIENT_DATA_ID_OFFSET + PATIENT_DATA_ID_LEN,
        );
        const patientId = decodeLatin1(idBytes).trim();
        if (patientId.length > 0) {
          patientIds.set(patientDbId, patientId);
        }
        break;
      }

      case CHUNK_TYPE_PRE_DATA: {
        if (payloadOffset + PRE_DATA_LATERALITY_OFFSET + 1 > buf.length) break;
        const c = String.fromCharCode(buf[payloadOffset + PRE_DATA_LATERALITY_OFFSET]!);
        const code = lateralityCharToCode(c);
        if (code !== null) {
          const v = ensureVolume(volumes, patientDbId, studyId, seriesId, ordinalCounter++);
          v.laterality = code;
        }
        break;
      }

      case CHUNK_TYPE_LATERALITY: {
        // Fallback laterality chunk; lower priority than pre_data so we
        // only set it when pre_data hasn't filled the slot.
        if (payloadOffset + LAT_STRUCT_LATERALITY_OFFSET + 1 > buf.length) break;
        const c = String.fromCharCode(buf[payloadOffset + LAT_STRUCT_LATERALITY_OFFSET]!);
        const code = lateralityCharToCode(c);
        if (code !== null) {
          const v = ensureVolume(volumes, patientDbId, studyId, seriesId, ordinalCounter++);
          if (v.laterality === null) v.laterality = code;
        }
        break;
      }

      case CHUNK_TYPE_BSCAN_METADATA: {
        if (payloadOffset + BSCAN_ACQUISITION_TIME_OFFSET + 8 > buf.length) break;
        const v = ensureVolume(volumes, patientDbId, studyId, seriesId, ordinalCounter++);
        const numImages = view.getUint32(payloadOffset + BSCAN_NUM_IMAGES_OFFSET, true);
        if (numImages > v.numImages) v.numImages = numImages;
        const ticks = view.getBigUint64(
          payloadOffset + BSCAN_ACQUISITION_TIME_OFFSET,
          true,
        );
        // We trust the first non-zero acquisitionTime for each volume —
        // later B-scans within the same volume share the same run start.
        if (v.acquisitionTicks === null && ticks > 0n) {
          v.acquisitionTicks = ticks;
        }
        // slice_id is doubled internally; +1 because indices are 0-based.
        if (sliceId >= 0) {
          const sliceCount = Math.floor(sliceId / 2) + 1;
          if (sliceCount > v.maxSliceCount) v.maxSliceCount = sliceCount;
        }
        break;
      }

      default:
        // Other chunk types (image data, eye_data, time_data, UIDs, …)
        // are not needed for the upload portal's match-candidate UI.
        break;
    }
  }

  // ── 5. flatten accumulators into the public E2eScan[] shape ────────────
  return [...volumes.values()]
    .sort((a, b) => a.firstSeenOrdinal - b.firstSeenOrdinal)
    .map((v, idx) => {
      const patientId = patientIds.get(v.patientDbId) ?? '';
      const nBscans =
        v.numImages > 0 ? v.numImages : v.maxSliceCount > 0 ? v.maxSliceCount : 0;
      const scanDate =
        v.acquisitionTicks !== null ? windowsTicksToDate(v.acquisitionTicks) : new Date(0);
      return {
        patientId,
        scanDate,
        laterality: v.laterality ?? 'OD',
        scanIndex: idx,
        nBscans,
      } satisfies E2eScan;
    });
}

/* ────────────────────────────────────────────────────────────────────── */
/*  Internal readers                                                      */
/* ────────────────────────────────────────────────────────────────────── */

/**
 * Detect the {@code E2EMultipleVolumeFile} sentinel and return the
 * appropriate byte_skip (64 if present, 0 otherwise) — mirrors
 * oct_converter's `read_oct_volume` initial probe.
 */
function detectMultiVolumeSkip(buf: Uint8Array): number {
  if (buf.length < MULTI_VOLUME_MAGIC.length) return 0;
  const probe = decodeLatin1(buf.subarray(0, MULTI_VOLUME_MAGIC.length));
  return probe === MULTI_VOLUME_MAGIC ? 64 : 0;
}

/**
 * Spectralis exports use a small set of 12-byte magic strings ("CMDb",
 * "MDbDir", and similar) — we accept anything that starts with an ASCII
 * printable character so a freshly-minted fixture passes. Hard cutoff:
 * the first byte must be a printable ASCII char.
 */
function isPlausibleHeaderMagic(s: string): boolean {
  if (s.length === 0) return false;
  const code = s.charCodeAt(0);
  return code >= 0x20 && code < 0x7f;
}

interface MainDirectoryView {
  numEntries: number;
  current: number;
  prev: number;
}

function readMainDirectory(view: DataView, offset: number): MainDirectoryView {
  return {
    numEntries: view.getUint32(offset + MAIN_DIR_NUM_ENTRIES_OFFSET, true),
    current: view.getUint32(offset + MAIN_DIR_CURRENT_OFFSET, true),
    prev: view.getUint32(offset + MAIN_DIR_PREV_OFFSET, true),
  };
}

function ensureVolume(
  volumes: Map<string, VolumeAccumulator>,
  patientDbId: number,
  studyId: number,
  seriesId: number,
  ordinal: number,
): VolumeAccumulator {
  const key = volumeKey(patientDbId, studyId, seriesId);
  let v = volumes.get(key);
  if (!v) {
    v = {
      patientDbId,
      studyId,
      seriesId,
      firstSeenOrdinal: ordinal,
      laterality: null,
      acquisitionTicks: null,
      numImages: 0,
      maxSliceCount: 0,
    };
    volumes.set(key, v);
  }
  return v;
}

/**
 * DR-029 — what a dropped file is, decided by its bytes.
 *
 * Mirrors {@code FileKindSniffer} on the server: PNG and JPEG by their
 * signatures, DICOM by the "DICM" marker after the 128-byte preamble, a
 * Spectralis export by one of the three header magics the vendor uses (or,
 * as the OCT page always allowed, an {@code .e2e}-named file whose header
 * starts with printable ASCII). The browser's {@code File.type} is not
 * consulted: it is a guess from the extension, and a Clarus export named
 * {@code .dcm} arrives with an empty one.
 *
 * The server sniffs again on commit. This copy exists so the page can route a
 * file — parse an OCT header, read DICOM tags, show an image thumbnail —
 * before a byte has travelled.
 */

export type UploadKind = 'e2e' | 'dicom' | 'image'
export type UploadFormat = 'e2e' | 'dicom' | 'jpeg' | 'png'

export interface SniffResult {
  kind: UploadKind
  format: UploadFormat
}

/** How many leading bytes {@link sniffBytes} needs: the DICOM marker sits at 128. */
export const HEAD_BYTES = 132

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
const JPEG = [0xff, 0xd8, 0xff]
const DICM = [0x44, 0x49, 0x43, 0x4d]
const DICOM_PREAMBLE = 128
const E2E_MAGICS = ['CMDb', 'MDbDir', 'E2EMultipleVolumeFile']

function startsWith(head: Uint8Array, magic: number[], offset = 0): boolean {
  if (head.length < offset + magic.length) return false
  for (let i = 0; i < magic.length; i++) {
    if (head[offset + i] !== magic[i]) return false
  }
  return true
}

function ascii(s: string): number[] {
  return Array.from(s, (c) => c.charCodeAt(0))
}

function printableAscii(head: Uint8Array, n: number): boolean {
  if (head.length < n) return false
  if (head[0] < 0x20 || head[0] >= 0x7f) return false
  for (let i = 0; i < n; i++) {
    const b = head[i]
    if (b !== 0 && (b < 0x20 || b >= 0x7f)) return false
  }
  return true
}

/**
 * @param head the file's leading bytes — at least {@link HEAD_BYTES} when the
 *             file is that long
 * @param filename only consulted for the {@code .e2e} concession
 */
export function sniffBytes(head: Uint8Array, filename?: string): SniffResult | null {
  if (!head || head.length === 0) return null
  if (startsWith(head, PNG)) return { kind: 'image', format: 'png' }
  if (startsWith(head, JPEG)) return { kind: 'image', format: 'jpeg' }
  if (startsWith(head, DICM, DICOM_PREAMBLE)) return { kind: 'dicom', format: 'dicom' }
  for (const magic of E2E_MAGICS) {
    if (startsWith(head, ascii(magic))) return { kind: 'e2e', format: 'e2e' }
  }
  if (filename && filename.toLowerCase().endsWith('.e2e') && printableAscii(head, 12)) {
    return { kind: 'e2e', format: 'e2e' }
  }
  return null
}

/**
 * Read the leading bytes of a file. Uses {@code Blob.arrayBuffer} where the
 * runtime has it and falls back to a {@code FileReader} where only
 * {@code File.prototype} was polyfilled (jsdom).
 */
export async function readHead(file: File, bytes = HEAD_BYTES): Promise<Uint8Array> {
  const slice = file.slice(0, bytes)
  const withBuffer = slice as Blob & { arrayBuffer?: () => Promise<ArrayBuffer> }
  if (typeof withBuffer.arrayBuffer === 'function') {
    return new Uint8Array(await withBuffer.arrayBuffer())
  }
  return new Promise<Uint8Array>((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error ?? new Error('could not read the file'))
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.readAsArrayBuffer(slice)
  })
}

/** {@link sniffBytes} over a dropped file. */
export async function sniffFile(file: File): Promise<SniffResult | null> {
  return sniffBytes(await readHead(file), file.name)
}

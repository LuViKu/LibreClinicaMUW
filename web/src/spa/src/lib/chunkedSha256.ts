/**
 * SHA-256 of a file read in slices, never as one buffer.
 *
 * A multimodal Spectralis export is 10-640 MB (up to 2 GiB is accepted), and
 * {@code crypto.subtle.digest} has no streaming form: hashing through it means
 * holding the whole file in memory. hash-wasm gives an incremental SHA-256 (a
 * small WebAssembly module, no network, no dependencies), so memory stays at
 * one slice.
 */
import { createSHA256 } from 'hash-wasm'

/** Slice size: large enough to keep the per-slice overhead small, small enough to be cheap to hold. */
export const HASH_CHUNK_BYTES = 8 * 1024 * 1024

async function readSlice(blob: Blob): Promise<Uint8Array> {
  const withBuffer = blob as Blob & { arrayBuffer?: () => Promise<ArrayBuffer> }
  if (typeof withBuffer.arrayBuffer === 'function') return new Uint8Array(await withBuffer.arrayBuffer())
  // jsdom only polyfills File.prototype partially.
  return new Promise<Uint8Array>((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error ?? new Error('could not read the file'))
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.readAsArrayBuffer(blob)
  })
}

/**
 * Lower-case hex SHA-256 of {@code file}, computed slice by slice.
 *
 * @param onProgress called after each slice with the fraction done (0..1)
 */
export async function sha256OfFileChunked(
  file: Blob,
  onProgress?: (fraction: number) => void,
  chunkBytes: number = HASH_CHUNK_BYTES,
): Promise<string> {
  const hasher = await createSHA256()
  hasher.init()
  for (let offset = 0; offset < file.size; offset += chunkBytes) {
    hasher.update(await readSlice(file.slice(offset, Math.min(offset + chunkBytes, file.size))))
    onProgress?.(Math.min(1, (offset + chunkBytes) / file.size))
  }
  return hasher.digest('hex')
}

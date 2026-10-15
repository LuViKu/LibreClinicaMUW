/**
 * DR-041 — SHA-256 in slices: a file larger than one slice hashes to the same
 * digest as a one-shot hash, and is never read whole.
 */
import { createHash } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'

import { HASH_CHUNK_BYTES, sha256OfFileChunked } from '@/lib/chunkedSha256'

function synthetic(bytes: number): Uint8Array {
  const b = new Uint8Array(bytes)
  for (let i = 0; i < bytes; i++) b[i] = (i * 31 + (i >>> 8)) & 0xff
  return b
}

function reference(b: Uint8Array): string {
  return createHash('sha256').update(b).digest('hex')
}

describe('sha256OfFileChunked', () => {
  it('matches the published digests of the empty input and "abc"', async () => {
    expect(await sha256OfFileChunked(new Blob([]))).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(await sha256OfFileChunked(new Blob(['abc']))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })

  it('hashes a blob larger than one slice to the same digest as a one-shot hash', async () => {
    const bytes = synthetic(2 * HASH_CHUNK_BYTES + 12345)
    const blob = new Blob([bytes])
    const slice = vi.spyOn(blob, 'slice')
    const progress: number[] = []
    const digest = await sha256OfFileChunked(blob, (f) => progress.push(f))
    expect(digest).toBe(reference(bytes))
    expect(slice).toHaveBeenCalledTimes(3) // 8 MiB + 8 MiB + the rest, never the whole blob at once
    expect(progress).toHaveLength(3)
    expect(progress[2]).toBe(1)
  })

  it('does not depend on the slice size', async () => {
    const bytes = synthetic(300_001)
    expect(await sha256OfFileChunked(new Blob([bytes]), undefined, 64 * 1024)).toBe(reference(bytes))
    expect(await sha256OfFileChunked(new Blob([bytes]), undefined, 7919)).toBe(reference(bytes))
  })
})

/**
 * Browser de-identification — the residual-identifier sweep.
 *
 * Before stripping, the pipeline collects the strings that identified the
 * patient (names, the hospital ID, a date of birth). After stripping, the
 * ENTIRE output is searched for each of them, in the encodings a vendor file
 * might hold them in: single-byte (ASCII / Latin-1), UTF-8 and UTF-16LE,
 * letters compared case-insensitively. Any hit means the identity survives
 * somewhere the field-level strip did not reach (a second copy in another
 * chunk, a free-text tag, a thumbnail's metadata), and the file is refused.
 *
 * Only a yes/no leaves this module — never the value or where it was found.
 */

/** Values shorter than this are not searched: they match binary noise, not people. */
export const MIN_SWEEP_LENGTH = 3

const FOLD = (() => {
  const t = new Uint8Array(256)
  for (let i = 0; i < 256; i++) {
    const asciiUpper = i >= 0x41 && i <= 0x5a
    // Latin-1 capitals (Ä Ö Ü …) and, for UTF-8, the continuation bytes that
    // differ between a capital and its small letter. This can only widen what
    // counts as a match, which errs towards refusing.
    const latinUpper = (i >= 0xc0 && i <= 0xde && i !== 0xd7) || (i >= 0x80 && i <= 0x9e)
    t[i] = asciiUpper || latinUpper ? i + 0x20 : i
  }
  return t
})()

/** Drop what is not worth searching: too short, equal to the study label, duplicates. */
export function sweepCandidates(values: Iterable<string>, label: string): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  const labelFolded = label.toLowerCase()
  for (const raw of values) {
    const v = raw.trim()
    if (v.length < MIN_SWEEP_LENGTH) continue
    const folded = v.toLowerCase()
    if (folded === labelFolded) continue
    if (seen.has(folded)) continue
    seen.add(folded)
    out.push(v)
  }
  return out
}

function latin1Bytes(s: string): Uint8Array | null {
  const out = new Uint8Array(s.length)
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c > 0xff) return null
    out[i] = c
  }
  return out
}

function utf16leBytes(s: string): Uint8Array {
  const out = new Uint8Array(s.length * 2)
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    out[i * 2] = c & 0xff
    out[i * 2 + 1] = c >> 8
  }
  return out
}

/** The byte patterns one value can appear as, case-folded for the comparison. */
export function patternsFor(value: string): Uint8Array[] {
  const raw: Uint8Array[] = []
  const l1 = latin1Bytes(value)
  if (l1) raw.push(l1)
  raw.push(new TextEncoder().encode(value))
  raw.push(utf16leBytes(value))
  const seen = new Set<string>()
  const out: Uint8Array[] = []
  for (const p of raw) {
    const folded = new Uint8Array(p.length)
    for (let i = 0; i < p.length; i++) folded[i] = FOLD[p[i]!]!
    const key = Array.from(folded).join(',')
    if (seen.has(key)) continue
    seen.add(key)
    out.push(folded)
  }
  return out
}

/**
 * Does {@code buf} contain {@code pat} (already folded), ignoring ASCII case?
 * The first byte is located with the native {@code indexOf} (both cases), only
 * the candidates are compared in script — a few hundred MB stay in the
 * seconds range.
 */
function containsFolded(buf: Uint8Array, pat: Uint8Array): boolean {
  const n = pat.length
  if (n === 0 || buf.length < n) return false
  const b0 = pat[0]!
  // The first byte as the file may hold it: folded (small) or its capital twin.
  const hasCapitalTwin = (b0 >= 0x61 && b0 <= 0x7a) || (b0 >= 0xe0 && b0 <= 0xfe && b0 !== 0xf7) || (b0 >= 0xa0 && b0 <= 0xbe)
  const firsts = hasCapitalTwin ? [b0, b0 - 0x20] : [b0]
  const last = buf.length - n
  for (const first of firsts) {
    let i = buf.indexOf(first)
    while (i !== -1 && i <= last) {
      let k = 1
      while (k < n && FOLD[buf[i + k]!] === pat[k]) k++
      if (k === n) return true
      i = buf.indexOf(first, i + 1)
    }
  }
  return false
}

/** True when any of {@code values} (as returned by {@link sweepCandidates}) is found in {@code buf}. */
export function sweepFindsResidual(buf: Uint8Array, values: string[]): boolean {
  for (const value of values) {
    for (const pat of patternsFor(value)) {
      if (containsFolded(buf, pat)) return true
    }
  }
  return false
}

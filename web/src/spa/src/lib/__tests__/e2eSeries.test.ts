/**
 * Parity and read-budget tests for the browser series parser (lib/e2eSeries.ts).
 *
 * The fixtures and their expected `describe_e2e` output are shared with the Python suite
 * (testdata/e2e-synthetic/, muw-e2e-converter/tests/test_parity_fixtures.py). Synthetic data only.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'

import {
  blobReader,
  decodeSeriesThumbnail,
  describeE2eFile,
  downscale,
  scanE2e,
  uFloat16,
  octIntensityToByte,
  type E2eDescription,
  type RangeReader,
} from '../e2eSeries'

const FIXTURES = resolve(process.cwd(), '../../../testdata/e2e-synthetic')
const names = readdirSync(FIXTURES)
  .filter((f) => f.endsWith('.e2e'))
  .map((f) => f.replace(/\.e2e$/, ''))
  .sort()

function bytesOf(name: string): Uint8Array {
  return new Uint8Array(readFileSync(resolve(FIXTURES, `${name}.e2e`)))
}
function fileOf(name: string, bytes: Uint8Array = bytesOf(name)): File {
  return new File([bytes], `${name}.e2e`, { type: 'application/octet-stream' })
}
function expectedOf(name: string): E2eDescription {
  return JSON.parse(readFileSync(resolve(FIXTURES, `${name}.describe.json`), 'utf-8'))
}

afterEach(() => vi.restoreAllMocks())

describe('describeE2eFile parity with describe_e2e', () => {
  it('has the shared fixtures', () => {
    expect(names.length).toBeGreaterThanOrEqual(6)
  })

  it.each(names)('%s: deep-equals the Python output', async (name) => {
    expect(await describeE2eFile(fileOf(name))).toEqual(expectedOf(name))
  })

  it('compares the surname slot with an expected label like headerPatient', async () => {
    const f = fileOf('oct-volume-localizer') // surname SYNTH001
    expect((await describeE2eFile(f, 'synth001')).headerPatient).toBe('matches')
    expect((await describeE2eFile(f, 'OTHER')).headerPatient).toBe('differs')
    expect((await describeE2eFile(f, '')).headerPatient).toBe('none')
    expect((await describeE2eFile(fileOf('no-oct'), 'X')).headerPatient).toBe('empty')
  })

  it('never exposes the first-name or patient-ID slots', async () => {
    const blob = JSON.stringify(await describeE2eFile(fileOf('oct-volume-localizer')))
    expect(blob).not.toContain('SYNTHFIRST')
    expect(blob).not.toContain('SYNTHPID')
  })

  it('vets the header label like the server', async () => {
    const label = async (n: string) => (await describeE2eFile(fileOf(n))).headerLabel
    expect(await label('oct-volume-localizer')).toBe('SYNTH001')
    expect(await label('oct-line-scan')).toBeNull() // no patient chunk
    expect(await label('enface-multi-image')).toBeNull() // not a token
    expect(await label('no-oct')).toBeNull() // empty
    expect(await label('time-sequence')).toBe('SYNTH004') // two agreeing chunks
  })
})

describe('read budget', () => {
  function spyReader(blob: Blob) {
    const inner = blobReader(blob)
    const calls: Array<[number, number]> = []
    const reader: RangeReader = {
      size: inner.size,
      read(start, end) {
        calls.push([start, Math.min(end, inner.size)])
        return inner.read(start, end)
      },
    }
    return { reader, calls }
  }

  it('reads only the directory and small metadata chunks, never the pixel payloads', async () => {
    const bytes = bytesOf('large-pixels')
    const { reader, calls } = spyReader(fileOf('large-pixels', bytes))
    expect(await describeE2eFile(reader)).toEqual(expectedOf('large-pixels'))
    const sizes = calls.map(([a, b]) => b - a)
    const total = sizes.reduce((x, y) => x + y, 0)
    // The largest fixed read is a 60-byte header + 127 bytes; a directory is 44 bytes an entry.
    expect(Math.max(...sizes)).toBeLessThanOrEqual(44 * 64 + 52)
    expect(bytes.length).toBeGreaterThan(150_000) // large pixel chunks
    expect(total).toBeLessThan(bytes.length / 4)
    // A 160x160 image payload is never read (a chunk probe reaches 47 bytes into it).
    const covered = new Uint8Array(bytes.length)
    for (const [a, b] of calls) covered.fill(1, a, b)
    let run = 0
    let longest = 0
    for (const c of covered) {
      run = c ? 0 : run + 1
      longest = Math.max(longest, run)
    }
    expect(longest).toBeGreaterThanOrEqual(160 * 160 - 128) // minus the probe overlap at the start
  })

  it('uses File.slice only and never reads the whole file', async () => {
    const file = fileOf('large-pixels')
    const slice = vi.spyOn(File.prototype, 'slice')
    const whole = vi.spyOn(File.prototype, 'arrayBuffer')
    await describeE2eFile(file)
    expect(slice).toHaveBeenCalled()
    const lens = slice.mock.calls.map(([a, b]) => (b ?? file.size) - (a ?? 0))
    expect(Math.max(...lens)).toBeLessThan(file.size / 4)
    expect(whole.mock.contexts.every((c) => c !== file)).toBe(true)
  })

  it('reads one image for a thumbnail, not the file', async () => {
    const bytes = bytesOf('large-pixels')
    const { reader, calls } = spyReader(fileOf('large-pixels', bytes))
    const index = await scanE2e(reader)
    const before = calls.length
    const baf = index.describe().series.find((s) => s.seriesModality === 'BAF')!
    await index.thumbnailPixels(baf.seriesKey)
    const extra = calls.slice(before)
    expect(extra).toHaveLength(1)
    expect(extra[0]![1] - extra[0]![0]).toBe(160 * 160)
  })
})

describe('thumbnails', () => {
  it('en-face: the raw 8-bit first image', async () => {
    const file = fileOf('enface-multi-image')
    const d = await describeE2eFile(file)
    const baf = d.series.find((s) => s.seriesModality === 'BAF')!
    const t = await decodeSeriesThumbnail(file, baf)
    expect(t).toMatchObject({ width: 8, height: 8 })
    expect([...new Set(t!.data)]).toEqual([40]) // first of 40, 41, 42
  })

  it('OCT volume: its IR localizer', async () => {
    const file = fileOf('oct-volume-localizer')
    const d = await describeE2eFile(file)
    const t = await decodeSeriesThumbnail(file, d.series[0]!)
    expect(d.series[0]!.seriesModality).toBe('OCT')
    expect(t).toMatchObject({ width: 8, height: 8 })
    expect([...new Set(t!.data)]).toEqual([120])
  })

  it('OCT line scan: the B-scan decoded from UFloat16 with the log curve', async () => {
    const bytes = bytesOf('oct-line-scan')
    const d = await describeE2eFile(fileOf('oct-line-scan', bytes))
    const od = d.series.find((s) => s.laterality === 'OD')!
    expect(od.seriesModality).toBe('OCT_LINE')
    expect([od.width, od.height]).toEqual([10, 6])

    // The OD B-scan holds 0x3C03 (fill 3) in 60 pixels: overwrite them with known UFloat16 values.
    let at = -1
    for (let i = 0; i + 120 <= bytes.length && at < 0; i++) {
      let ok = true
      for (let j = 0; j < 120 && ok; j += 2) ok = bytes[i + j] === 0x03 && bytes[i + j + 1] === 0x3c
      if (ok) at = i
    }
    expect(at).toBeGreaterThan(0)
    // values, and the bytes oct-converter's LUT + vol_intensity_transform give for them
    const values = [64512, 63488, 61440, 63489, 64511, 59733, 0, 65535, 56320]
    const expected = [255, 234, 192, 247, 255, 165, 0, 255, 87]
    const patched = bytes.slice()
    const dv = new DataView(patched.buffer)
    for (let i = 0; i < 60; i++) dv.setUint16(at + 2 * i, values[i % 9]!, true)

    const t = await decodeSeriesThumbnail(fileOf('oct-line-scan', patched), od)
    expect(t).toMatchObject({ width: 10, height: 6 })
    expect([...t!.data]).toEqual(Array.from({ length: 60 }, (_, i) => expected[i % 9]))
  })

  it('UFloat16 and the intensity curve match oct-converter', () => {
    expect(octIntensityToByte(uFloat16(63 << 10))).toBe(255)
    expect(octIntensityToByte(uFloat16(0x3c03))).toBe(0)
    expect(uFloat16(63 << 10)).toBe(1)
    expect(uFloat16(62 << 10)).toBe(0.5)
  })

  it('downscales to about 256 px', async () => {
    const index = await scanE2e(fileOf('large-pixels'))
    const baf = index.describe().series.find((s) => s.seriesModality === 'BAF')!
    const small = await index.thumbnailPixels(baf.seriesKey, 64)
    expect(small).toMatchObject({ width: 64, height: 64 })
    expect([...new Set(small!.data)]).toEqual([50])
    const full = await index.thumbnailPixels(baf.seriesKey)
    expect(full).toMatchObject({ width: 160, height: 160 }) // never upscaled
    expect(await index.thumbnailPixels('s99')).toBeNull()

    const src = Uint8Array.from({ length: 512 * 256 }, (_, i) => (i % 512 < 256 ? 0 : 200))
    const g = downscale(src, 512, 256, 256)
    expect([g.width, g.height]).toEqual([256, 128])
    expect(g.data[0]).toBe(0)
    expect(g.data[255]).toBe(200)
  })
})

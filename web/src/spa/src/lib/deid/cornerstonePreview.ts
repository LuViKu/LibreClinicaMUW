/**
 * Browser de-identification — first-frame preview for the transfer syntaxes a
 * browser cannot decode itself (JPEG 2000, JPEG-LS, JPEG lossless, HTJ2K, RLE:
 * typical for OCT exports). Uses the codecs of the already-present
 * {@code @cornerstonejs/dicom-image-loader} (the no-web-worker bundle the
 * B-scan viewer uses), lazy-loaded, on the MAIN thread — Cornerstone expects a
 * window, so this is not run in the de-identification worker.
 *
 * Preview only: the file that is uploaded is never touched by this path.
 * Returns null when decoding genuinely fails; the row then shows the
 * "no preview" notice.
 */
import type { RawPreview } from './e2eDeidentify'

interface CsImage {
  rows: number
  columns: number
  color?: boolean
  minPixelValue?: number
  maxPixelValue?: number
  invert?: boolean
  getPixelData(): ArrayLike<number>
}

/**
 * Cornerstone pixel data → RGBA. Colour images arrive as RGBA (4 values per
 * pixel) or RGB (3); anything else is treated as one grey value per pixel,
 * stretched between the image's min and max (inverted for MONOCHROME1).
 * Returns null when the array does not match the geometry.
 */
export function pixelsToRaw(
  px: ArrayLike<number>,
  rows: number,
  cols: number,
  opts: { color?: boolean; min?: number; max?: number; invert?: boolean } = {},
): RawPreview | null {
  const n = rows * cols
  if (n <= 0) return null
  const rgba = new Uint8ClampedArray(n * 4)
  if (px.length === n * 4 || (opts.color && px.length === n * 3)) {
    const stride = px.length / n
    for (let i = 0; i < n; i++) {
      rgba[i * 4] = px[i * stride]!
      rgba[i * 4 + 1] = px[i * stride + 1]!
      rgba[i * 4 + 2] = px[i * stride + 2]!
      rgba[i * 4 + 3] = 255
    }
    return { width: cols, height: rows, rgba }
  }
  if (px.length !== n) return null
  let lo = opts.min
  let hi = opts.max
  if (lo === undefined || hi === undefined || !(hi > lo)) {
    lo = Infinity
    hi = -Infinity
    for (let i = 0; i < n; i++) {
      const v = px[i]!
      if (v < lo) lo = v
      if (v > hi) hi = v
    }
  }
  const range = hi > lo ? hi - lo : 1
  for (let i = 0; i < n; i++) {
    let g = Math.round((255 * (px[i]! - lo)) / range)
    if (opts.invert) g = 255 - g
    rgba[i * 4] = g
    rgba[i * 4 + 1] = g
    rgba[i * 4 + 2] = g
    rgba[i * 4 + 3] = 255
  }
  return { width: cols, height: rows, rgba }
}

/** Decode the first frame of {@code file} with Cornerstone's codecs; null on any failure. */
export async function cornerstonePreview(file: File): Promise<RawPreview | null> {
  let url: string | null = null
  try {
    const [coreMod, loaderMod, parserMod] = await Promise.all([
      import('@cornerstonejs/core'),
      // @ts-expect-error — sub-path import without .d.ts (same bundle as BscanViewer).
      import('@cornerstonejs/dicom-image-loader/dist/cornerstoneDICOMImageLoaderNoWebWorkers.bundle.min.js'),
      import('dicom-parser'),
    ])
    const core = ((coreMod as { default?: unknown }).default ?? coreMod) as {
      init: () => Promise<void>
      imageLoader: { loadImage: (id: string) => Promise<CsImage> }
    }
    const loader = ((loaderMod as { default?: unknown }).default ?? loaderMod) as {
      external: { cornerstone: unknown; dicomParser: unknown }
    }
    loader.external.cornerstone = core
    loader.external.dicomParser = (parserMod as { default?: unknown }).default ?? parserMod
    await core.init()

    url = URL.createObjectURL(file)
    const loaded = (await core.imageLoader.loadImage(`wadouri:${url}`)) as CsImage & { promise?: Promise<CsImage> }
    // Some loader versions hand back a {promise} wrapper rather than the image.
    const image = loaded.promise ? await loaded.promise : loaded
    return pixelsToRaw(image.getPixelData(), image.rows, image.columns, {
      color: image.color,
      min: image.minPixelValue,
      max: image.maxPixelValue,
      invert: image.invert,
    })
  } catch {
    return null
  } finally {
    if (url) URL.revokeObjectURL(url)
  }
}

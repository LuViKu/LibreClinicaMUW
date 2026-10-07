/**
 * Browser de-identification — the preview the operator confirms against.
 *
 * Pixels are never altered by the strip, so what the operator sees is what is
 * sent. The picture exists so a person can look for BURNED-IN text (a name
 * typed into a fundus image, a hospital ID in an OCT print-out) — the one kind
 * of identity no header rewrite reaches.
 */
import type { RawPreview } from './e2eDeidentify'

/** Longest side of the preview: large enough to read an overlay's text. */
export const PREVIEW_MAX_SIDE = 1024

/** Box-free nearest-neighbour downscale; returns the input when it already fits. */
export function downscale(src: RawPreview, maxSide = PREVIEW_MAX_SIDE): RawPreview {
  const longest = Math.max(src.width, src.height)
  if (longest <= maxSide) return src
  const k = maxSide / longest
  const w = Math.max(1, Math.round(src.width * k))
  const h = Math.max(1, Math.round(src.height * k))
  const out = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) {
    const sy = Math.min(src.height - 1, Math.floor(y / k))
    for (let x = 0; x < w; x++) {
      const sx = Math.min(src.width - 1, Math.floor(x / k))
      const si = (sy * src.width + sx) * 4
      const di = (y * w + x) * 4
      out[di] = src.rgba[si]!
      out[di + 1] = src.rgba[si + 1]!
      out[di + 2] = src.rgba[si + 2]!
      out[di + 3] = 255
    }
  }
  return { width: w, height: h, rgba: out }
}

/**
 * Turn a preview into an object URL for an {@code <img>}. Null where the
 * runtime has no canvas (the caller then shows the "no preview" notice).
 */
export async function previewToUrl(p: RawPreview): Promise<string | null> {
  try {
    if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') return null
    const canvas = document.createElement('canvas')
    canvas.width = p.width
    canvas.height = p.height
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    ctx.putImageData(new ImageData(new Uint8ClampedArray(p.rgba), p.width, p.height), 0, 0)
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
    return blob ? URL.createObjectURL(blob) : null
  } catch {
    return null
  }
}

/**
 * nAMD workspace — icon dictionary.
 *
 * Mirrors the {@code I.*} object in {@code namd-data.jsx} from the
 * claude.ai/design project. Twelve hand-drawn inline SVGs sized 16px
 * (header brand wordmark + tab strip icons + decision-panel affordances).
 * Each entry returns a raw SVG markup string so consumers can mount via
 * {@code v-html} without spinning up a per-icon SFC for what is a static
 * single-path drawing.
 *
 * <p>Stroke / fill match the design's monoline aesthetic: 1.5 px stroke,
 * {@code currentColor} so callers can colour-shift via Tailwind's text-*
 * utilities, no internal hardcoded colours.
 */

/** One inline SVG markup string — paste directly into {@code v-html}. */
export type NamdIcon = string

const sw = (path: string, opts: { size?: number; viewBox?: string } = {}): NamdIcon => {
  const size = opts.size ?? 16
  const viewBox = opts.viewBox ?? '0 0 24 24'
  return `<svg width="${size}" height="${size}" viewBox="${viewBox}" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`
}

const fill = (path: string, opts: { size?: number; viewBox?: string } = {}): NamdIcon => {
  const size = opts.size ?? 16
  const viewBox = opts.viewBox ?? '0 0 24 24'
  return `<svg width="${size}" height="${size}" viewBox="${viewBox}" fill="currentColor">${path}</svg>`
}

export const I: Record<string, NamdIcon> = {
  /** Logo mark — siegel-style circle echoing the MUW brand. */
  brand: sw('<circle cx="12" cy="12" r="9" /><path d="M7 13 L12 8 L17 13 M10 13 V17 H14 V13" />'),
  chevron: sw('<path d="M9 6 L15 12 L9 18" />'),
  /** Eye — patient banner adornment. */
  eye: sw('<path d="M2 12 C5 6 10 4 12 4 C14 4 19 6 22 12 C19 18 14 20 12 20 C10 20 5 18 2 12 Z" /><circle cx="12" cy="12" r="3" />'),
  chart: sw('<path d="M4 20 V10 M10 20 V4 M16 20 V14 M22 20 H2" />'),
  /** Stacked layers — OCT Viewer tab. */
  layers: sw('<path d="M12 3 L21 8 L12 13 L3 8 Z M3 13 L12 18 L21 13 M3 18 L12 23 L21 18" />'),
  /** Two-column split — Compare tab. */
  compare: sw('<rect x="3" y="4" width="8" height="16" rx="1" /><rect x="13" y="4" width="8" height="16" rx="1" /><path d="M12 4 V20" />'),
  report: sw('<path d="M6 3 H15 L19 7 V21 H6 Z M15 3 V7 H19 M9 12 H16 M9 16 H16" />'),
  /** Sparkle — AI affordance, segmentation pill. */
  spark: sw('<path d="M12 3 L13 9 L19 10 L13 11 L12 17 L11 11 L5 10 L11 9 Z" />'),
  /** Triangle alert — info banner. */
  alert: sw('<path d="M12 4 L22 20 H2 Z M12 10 V14 M12 17 V17.5" />'),
  /** Check — confirmation affordance. */
  check: sw('<path d="M4 12 L10 18 L20 6" />'),
  /** Right arrow — visit-to-visit cue. */
  arrowRight: sw('<path d="M5 12 H19 M13 6 L19 12 L13 18" />'),
  /** Printer — Report tab download / print action. */
  printer: sw('<path d="M7 3 H17 V8 H7 Z M5 8 H19 V18 H17 V21 H7 V18 H5 Z M7 14 H17" />'),
  /** Maximize — open the OCT scan in fullscreen. */
  maximize: sw('<path d="M4 10 V4 H10 M14 4 H20 V10 M20 14 V20 H14 M10 20 H4 V14" />'),
  /** Minimize — close the OCT scan fullscreen back to inline. */
  minimize: sw('<path d="M10 4 V10 H4 M14 10 H20 V4 M4 14 H10 V20 M14 20 V14 H20" />'),
  /** Close (×) — fullscreen header dismiss button. */
  close: sw('<path d="M6 6 L18 18 M18 6 L6 18" />'),
}

/** Inline SVG used as the fluid-trend "no-data" decoration. */
export const noDataDecor: NamdIcon = fill(
  '<path d="M12 2 L13.5 9 L21 10 L13.5 11.5 L12 19 L10.5 11.5 L3 10 L10.5 9 Z" opacity="0.18" />',
)

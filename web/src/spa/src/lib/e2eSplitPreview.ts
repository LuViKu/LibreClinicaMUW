/**
 * The browser preview of a split E2E row: what the file's own series list says, how its label
 * compares with the folder it was found in, and which series the person left out.
 *
 * Pure helpers over the output of `e2eSeries.ts` (which mirrors the server's `describe_e2e`), so
 * the store and the row component share one set of rules.
 */
import type { E2eDescription, E2eSeriesInfo } from '@/lib/e2eSeries'

/**
 * How the label read from the file compares with the folder:
 *  - `matches`: both there, equal (case-insensitive);
 *  - `differs`: both there, not equal: the server parks every series;
 *  - `folder-only`: no readable label in the file, the folder is used;
 *  - `file-only`: no folder, the label from the file is used;
 *  - `none`: neither: the server parks every series.
 */
export type LabelCheck = 'matches' | 'differs' | 'folder-only' | 'file-only' | 'none'

/** The visit the existing /resolve call suggests for one series (subject + that series' date). */
export interface SeriesVisit {
  state: 'suggested' | 'novisit' | 'nopatient' | 'ambiguous'
  subjectLabel: string | null
  visitLabel: string | null
  visitDate: string | null
}

export interface SeriesPreview {
  /** `failed`: the list could not be read in the browser; the file is sent whole, as before. */
  status: 'ready' | 'failed'
  headerLabel: string | null
  labelCheck: LabelCheck
  series: E2eSeriesInfo[]
  visits: Record<string, SeriesVisit>
}

export function labelCheckOf(folder: string | null | undefined, headerLabel: string | null): LabelCheck {
  const f = (folder ?? '').trim()
  if (f && headerLabel) return f.toLowerCase() === headerLabel.toLowerCase() ? 'matches' : 'differs'
  if (f) return 'folder-only'
  return headerLabel ? 'file-only' : 'none'
}

export function previewOf(description: E2eDescription, folder: string | null | undefined): SeriesPreview {
  if (description.series.length === 0) return failedPreview()
  return {
    status: 'ready',
    headerLabel: description.headerLabel,
    labelCheck: labelCheckOf(folder, description.headerLabel),
    series: description.series,
    visits: {},
  }
}

export function failedPreview(): SeriesPreview {
  return { status: 'failed', headerLabel: null, labelCheck: 'none', series: [], visits: {} }
}

/** The subject label /resolve is asked about: the folder, else the file's own label; none when they differ. */
export function resolveLabelOf(folder: string | null | undefined, preview: SeriesPreview): string | null {
  if (preview.status !== 'ready' || preview.labelCheck === 'differs') return null
  return (folder ?? '').trim() || preview.headerLabel
}

/** The series keys to leave out, in the file's order. */
export function excludedKeysOf(preview: SeriesPreview | undefined, unticked: readonly string[] | undefined): string[] {
  if (!preview || preview.status !== 'ready' || !unticked || unticked.length === 0) return []
  const off = new Set(unticked)
  return preview.series.filter((s) => off.has(s.seriesKey)).map((s) => s.seriesKey)
}

/** Every series is unticked: nothing would be uploaded, so the row cannot be confirmed. */
export function allSeriesExcluded(preview: SeriesPreview | undefined, unticked: readonly string[] | undefined): boolean {
  if (!preview || preview.status !== 'ready' || preview.series.length === 0) return false
  return excludedKeysOf(preview, unticked).length === preview.series.length
}

export type EyeGroup = 'OD' | 'OS' | 'none'

export function eyeOf(s: Pick<E2eSeriesInfo, 'laterality'>): EyeGroup {
  return s.laterality ?? 'none'
}

/** Series grouped by eye (OD, OS, unknown), keeping the file's order inside a group. */
export function groupByEye(series: readonly E2eSeriesInfo[]): Array<{ eye: EyeGroup; series: E2eSeriesInfo[] }> {
  return (['OD', 'OS', 'none'] as const)
    .map((eye) => ({ eye, series: series.filter((s) => eyeOf(s) === eye) }))
    .filter((g) => g.series.length > 0)
}

/** Normalise the server's `excluded` list (keys or objects carrying one). */
export function excludedFromResponse(raw: ReadonlyArray<string | { seriesKey: string }> | undefined): string[] | null {
  if (!raw) return null
  return raw.map((e) => (typeof e === 'string' ? e : e.seriesKey))
}

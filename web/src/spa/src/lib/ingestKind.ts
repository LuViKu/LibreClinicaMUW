/**
 * DR-039 — how a filed file names what it is.
 *
 * An OCT volume is an OCT scan whatever format it came in: an `.e2e` reads
 * "OCT-Scan", a DICOM OCT volume "OCT-Scan · DICOM" (its kind stays `dicom`,
 * which is what the file is; `octVolume` says what it shows). Every other
 * file is named by its kind.
 */
import type { IngestItem } from '@/api/ingest'

type Translate = (key: string) => string

export function isDicomOctVolume(item: Pick<IngestItem, 'kind' | 'octVolume'>): boolean {
  return item.kind === 'dicom' && item.octVolume === true
}

export function ingestKindLabel(t: Translate, item: Pick<IngestItem, 'kind' | 'octVolume'>): string {
  if (isDicomOctVolume(item)) {
    return `${t('ingestInbox.kind.e2e')} · ${t('ingestInbox.sourceFormat.dicom')}`
  }
  return t(`ingestInbox.kind.${item.kind}`)
}

import { apiDownload } from './download'

/** Downloads a study's metadata: its design as CDISC ODM 1.3 XML. */
export function downloadStudyMetadata(oid: string): Promise<{ filename: string; bytes: number }> {
  return apiDownload(
    `/pages/api/v1/studies/${encodeURIComponent(oid)}/metadata`,
    `${oid}_metadata.xml`,
  )
}

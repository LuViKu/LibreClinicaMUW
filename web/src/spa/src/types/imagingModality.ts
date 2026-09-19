/**
 * P3.4 — a study's imaging catalogue.
 *
 * What the study photographs, which device does each acquisition, and which
 * CRF box each one ticks when a file is filed against a visit. Per study,
 * unlike `types/modality.ts`, which is the platform-wide catalogue of
 * measurements (BCVA, IOP, refraction) and a different thing entirely.
 */

/** What a file may be. An empty `kindsAccepted` means no file ever arrives. */
export type IngestKindCode = 'e2e' | 'dicom' | 'image' | 'other'

/** Which item on the checklist row a binding fills. */
export type BindingRole = 'performed' | 'not_performed_reason' | 'initials'

export type BindingLaterality = 'OU' | 'OD' | 'OS'

export interface ImagingModalityBinding {
  id: number
  role: BindingRole
  laterality: BindingLaterality
  itemOid: string
  /** The coded value meaning "yes" in this CRF's response set. */
  performedValue: string
}

export interface ImagingModality {
  id: number
  code: string
  labelDe: string
  labelEn: string
  device: string | null
  /** Comma-separated; empty means checklist-only, e.g. BCVA. */
  kindsAccepted: string
  lateralityRequired: boolean
  /** A DICOM calling AE title that classifies this modality on arrival. */
  autoMatchAeTitle: string | null
  ordinal: number
  /** 1 = active, 5 = retired. A retired modality ticks nothing. */
  statusId: number
  bindings: ImagingModalityBinding[]
}

export interface ImagingModalityWriteRequest {
  code: string
  labelDe: string
  labelEn: string
  device?: string | null
  kindsAccepted?: string
  lateralityRequired?: boolean
  autoMatchAeTitle?: string | null
  ordinal?: number
}

export interface BindingWriteRequest {
  role: BindingRole
  laterality?: BindingLaterality
  itemOid: string
  performedValue?: string
}

/**
 * Phase E.6 — modality admin types.
 *
 * A "modality" in MUW's ophthalmology eCRF model is one measurement
 * channel (visual acuity, intraocular pressure, OCT thickness, …) —
 * each backed by an OD/OS item OID pair in the legacy CRF model.
 *
 * Hand-typed here rather than re-exported from the auto-generated
 * `types/api.ts`: the harmonization step regenerates `api.ts`
 * post-merge once the backend worktree lands, but the SPA shouldn't
 * depend on that regeneration to be useful in isolation.
 *
 * Wire contract — keep in lockstep with `ModalityResource`
 * (backend's `ModalityApiController` DTOs):
 *
 *   GET    /pages/api/v1/modalities           → Modality[]
 *   POST   /pages/api/v1/modalities           → 201 Modality
 *   PUT    /pages/api/v1/modalities/{id}      → 200 Modality
 *   DELETE /pages/api/v1/modalities/{id}      → 204
 */

export interface Modality {
  modalityId: number
  code: string
  labelEn: string
  labelDe: string
  ordinal: number
  itemOidOd: string | null
  itemOidOs: string | null
  dataType: 'numeric' | 'categorical'
  unit: string | null
}

export interface CreateModalityRequest {
  code: string
  labelEn: string
  labelDe: string
  ordinal: number
  itemOidOd?: string
  itemOidOs?: string
  dataType: 'numeric' | 'categorical'
  unit?: string
}

export type UpdateModalityRequest = Omit<CreateModalityRequest, 'code'>

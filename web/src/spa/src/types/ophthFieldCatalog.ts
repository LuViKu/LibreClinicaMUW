/**
 * Phase E.6 ophth-field-catalog (2026-06-11) — wire shape for the
 * standardized ophthalmology field types the CRF Library wizard offers
 * as drop-in pre-built items.
 *
 * <p>Hand-typed (not re-exported from {@code types/api.ts}) so the SPA
 * stays usable when the catalog endpoint is offline OR when the auto-
 * generated types haven't been regenerated after a backend evolution.
 *
 * <p>Wire contract — keep in lockstep with backend's
 * {@code OphthFieldCatalogDto} (web/src/main/java/.../api/OphthFieldCatalogDto.java):
 *
 *   GET /pages/api/v1/ophth-field-catalog → OphthFieldCatalogEntry[]
 *
 * <p>Field semantics:
 *
 * <ul>
 *   <li>{@code code} — catalog identifier, e.g. {@code "BCVA_LETTERS"}.
 *       Drives the canonical item OID: bilateral entries produce
 *       {@code I_<oidPrefix>_OD_<code>} + {@code I_<oidPrefix>_OS_<code>}.</li>
 *   <li>{@code widget} — render hint. {@code CrfItemWidget} keys its
 *       data-type branches off this field when a catalog entry matches
 *       the item's OID; falls back to the OID heuristic otherwise.</li>
 *   <li>{@code unit} — short inline suffix ({@code "mmHg"} etc.). Null
 *       for categorical widgets.</li>
 *   <li>{@code minValue / maxValue / stepValue} — numeric validation +
 *       stepper increments. Null for non-numeric widgets.</li>
 *   <li>{@code conditionalOnCode / conditionalShowWhenValue} —
 *       show-when wiring for the "DONE? → REASON-if-no" pattern.</li>
 *   <li>{@code modalityCode} — link to the modality registry, where
 *       one exists. Future modality baselines join will read through
 *       this code instead of the per-eye OID column.</li>
 * </ul>
 */

export type OphthFieldCatalogWidget =
  | 'number-stepper'
  | 'snellen'
  | 'refraction'
  | 'yesno'
  | 'text'
  | 'select-one'

export type OphthFieldCatalogDataType = 'integer' | 'real' | 'string' | 'select-one'

export interface OphthFieldCatalogResponseOption {
  /** Persisted wire value. */
  value: string
  /** Display label (German). */
  label: string
}

export interface OphthFieldCatalogEntry {
  code: string
  labelDe: string
  labelEn: string
  hintDe: string | null
  hintEn: string | null
  bilateral: boolean
  dataType: OphthFieldCatalogDataType
  widget: OphthFieldCatalogWidget
  unit: string | null
  minValue: number | null
  maxValue: number | null
  stepValue: number | null
  placeholderText: string | null
  conditionalOnCode: string | null
  conditionalShowWhenValue: string | null
  responseOptions: OphthFieldCatalogResponseOption[]
  modalityCode: string | null
  oidPrefix: string
  ordinal: number
}

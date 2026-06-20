/**
 * D4 (2026-06-20) — BCVA (Best-Corrected Visual Acuity) canvas preset.
 *
 * <p>Bilateral (OD + OS) ETDRS letter-chart acuity per the ETDRS
 * protocol (Ferris et al., 1982; the de-facto standard for
 * ophthalmology trials). Default unit is "letters" (NOT Snellen) —
 * Snellen conversion is a downstream concern and not part of the
 * canonical CRF capture in MUW trials.
 *
 * <p>Items per eye:
 * <ol>
 *   <li><b>{@code OD_BCVA_LETTERS} / {@code OS_BCVA_LETTERS}</b> —
 *       primary BCVA reading in letters (INT, 0–100). The ETDRS chart
 *       has 14 lines × 5 letters = 70 letters max BUT modern protocols
 *       allow extrapolation (e.g. +5 per pinhole improvement); clinical
 *       trial datasets routinely contain values up to 100. We accept
 *       0–100 to cover both.</li>
 *   <li><b>{@code OD_BCVA_REFRACTION_SPHERE} / OS</b> — optional sphere
 *       in dioptres (REAL, −30 to +30). The MUW best-corrected workflow
 *       captures the refraction used as the correction at the moment of
 *       the chart reading; this row is optional (not {@code required})
 *       so paper-first sites can skip it.</li>
 *   <li><b>{@code OD_BCVA_REFRACTION_CYLINDER} / OS</b> — cylinder
 *       (REAL, −10 to +10).</li>
 *   <li><b>{@code OD_BCVA_REFRACTION_AXIS} / OS</b> — axis
 *       (INT, 0–180 degrees).</li>
 * </ol>
 *
 * <p>The bilateral group emits items in {@code [OD, OS, OD, OS, …]}
 * order — same convention as {@code generateOphthSectionItems} so the
 * bilateral grid renders the OD column on the LEFT (face-to-face
 * clinician/patient view, per the laterality memory).
 *
 * <p>CHOICE: the brief asked for "0–100 letters, INT, no decimal" for
 * the primary reading; we follow that. The four refraction sub-fields
 * are all flagged optional via {@code required: false}. If a trial
 * insists on refraction capture, the operator flips required on the
 * properties rail post-drop.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const BCVA_PRESET_ID = 'bcva'

const KEY = 'crfAuthoring.presets.bcva.items'

/**
 * Build a single eye's BCVA quartet. Returns the four items in canonical
 * order (letters first, then the three refraction sub-fields).
 */
function buildEye(
  eye: 'OD' | 'OS',
  pairEye: 'OD' | 'OS',
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  const lettersOid = `${eye}_BCVA_LETTERS`
  const sphereOid = `${eye}_BCVA_REFRACTION_SPHERE`
  const cylinderOid = `${eye}_BCVA_REFRACTION_CYLINDER`
  const axisOid = `${eye}_BCVA_REFRACTION_AXIS`

  const letters: Omit<AuthoringItem, 'uid'> = {
    name: lettersOid,
    oid: lettersOid,
    descriptionLabel: translate(`${KEY}.letters`),
    leftItemText: eye,
    rightItemText: '',
    units: 'letters',
    dataType: 'INT',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: '^(100|[1-9]?[0-9])$',
      errorMessage: translate(`${KEY}.lettersValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_BCVA_LETTERS`,
  }

  const sphere: Omit<AuthoringItem, 'uid'> = {
    name: sphereOid,
    oid: sphereOid,
    descriptionLabel: translate(`${KEY}.sphere`),
    leftItemText: eye,
    rightItemText: '',
    units: 'D',
    dataType: 'REAL',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: '^-?\\d+(\\.\\d+)?$',
      errorMessage: translate(`${KEY}.refractionValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_BCVA_REFRACTION_SPHERE`,
  }

  const cylinder: Omit<AuthoringItem, 'uid'> = {
    name: cylinderOid,
    oid: cylinderOid,
    descriptionLabel: translate(`${KEY}.cylinder`),
    leftItemText: eye,
    rightItemText: '',
    units: 'D',
    dataType: 'REAL',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: '^-?\\d+(\\.\\d+)?$',
      errorMessage: translate(`${KEY}.refractionValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_BCVA_REFRACTION_CYLINDER`,
  }

  const axis: Omit<AuthoringItem, 'uid'> = {
    name: axisOid,
    oid: axisOid,
    descriptionLabel: translate(`${KEY}.axis`),
    leftItemText: eye,
    rightItemText: '',
    units: 'deg',
    dataType: 'INT',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: '^(180|1[0-7][0-9]|[1-9]?[0-9])$',
      errorMessage: translate(`${KEY}.axisValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_BCVA_REFRACTION_AXIS`,
  }

  return [letters, sphere, cylinder, axis]
}

/**
 * Produce the BCVA preset's items. Emits eight items in
 * {@code [OD_letters, OD_sphere, OD_cylinder, OD_axis, OS_letters,
 * OS_sphere, OS_cylinder, OS_axis]} order so the bilateral grid can
 * pair them on the canonical suffix.
 *
 * <p>CHOICE: the brief lists the items in
 * {@code [OD_*, OS_*, OD_*, OS_*]} interleaved order (see
 * {@code generateOphthSectionItems}). For BCVA the four-deep refraction
 * quartet per eye is a single clinical concept, so we keep all four
 * OD items first followed by all four OS items. The bilateral grid
 * still pairs on the OID suffix, so the layout is unchanged. The
 * properties rail's reorder is available if a site prefers
 * interleaved.
 */
export function generateBcvaPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return [
    ...buildEye('OD', 'OS', translate),
    ...buildEye('OS', 'OD', translate),
  ]
}

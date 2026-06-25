/**
 * 2026-06-24 user-feedback round — BCVA-Decimal canvas preset.
 *
 * <p>Counterpart to {@link bcvaPreset} (which captures ETDRS
 * letters). The deployed MUW clinical routine uses an
 * autorefractometer that emits Snellen-equivalent decimal acuity
 * plus refraction; the study nurse enters the decimal value with an
 * optional signed partial-line marker (`1,0p-2` / `0,8+2` — German
 * clinical convention). This preset materialises the matching ten
 * items per visit so the public BCVA-entry portal has a CRF to
 * write into.
 *
 * <p>Items per eye (5 × 2 eyes = 10 total):
 *
 * <ol>
 *   <li>{@code OD_BCVA_DECIMAL} / {@code OS_BCVA_DECIMAL} — REAL,
 *       0.01–2.0. The raw chart-line decimal.</li>
 *   <li>{@code OD_BCVA_PARTIAL} / {@code OS_BCVA_PARTIAL} — INT,
 *       signed, −4…+4. The optotype offset from the decimal line:
 *       negative = missed N optotypes on that line; positive =
 *       read N additional optotypes from the next better line. Zero
 *       (or absent) is the common case (full line read).</li>
 *   <li>{@code OD_BCVA_REFRACTION_SPHERE} / {@code OS_BCVA_REFRACTION_SPHERE}
 *       — REAL, dioptres. Reuses the OID + shape from
 *       {@link bcvaPreset} so a study using both presets writes
 *       refraction into the same column.</li>
 *   <li>{@code OD_BCVA_REFRACTION_CYLINDER} / {@code OS_BCVA_REFRACTION_CYLINDER}
 *       — REAL, dioptres.</li>
 *   <li>{@code OD_BCVA_REFRACTION_AXIS} / {@code OS_BCVA_REFRACTION_AXIS}
 *       — INT, 0–180°.</li>
 * </ol>
 *
 * <p>Bilateral OD/OS pair emitted in the same order as
 * {@link bcvaPreset} (all OD items first, then all OS) so the
 * SectionCanvas bilateral grid pairs by suffix.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const BCVA_DECIMAL_PRESET_ID = 'bcvaDecimal'

const KEY = 'crfAuthoring.presets.bcvaDecimal.items'

function buildEye(
  eye: 'OD' | 'OS',
  pairEye: 'OD' | 'OS',
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  const decimalOid = `${eye}_BCVA_DECIMAL`
  const partialOid = `${eye}_BCVA_PARTIAL`
  const sphereOid = `${eye}_BCVA_REFRACTION_SPHERE`
  const cylinderOid = `${eye}_BCVA_REFRACTION_CYLINDER`
  const axisOid = `${eye}_BCVA_REFRACTION_AXIS`

  const decimal: Omit<AuthoringItem, 'uid'> = {
    name: decimalOid,
    oid: decimalOid,
    descriptionLabel: translate(`${KEY}.decimal`),
    leftItemText: eye,
    rightItemText: '',
    units: '',
    dataType: 'REAL',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      // 0.01..2.0 inclusive. Accepts both `.` and `,` decimal separator
      // because German operators frequently type the comma; the SPA
      // upstream normalises to `.` before validation.
      regexp: '^(0\\.\\d{1,3}|1(\\.\\d{1,3})?|2(\\.0+)?)$',
      errorMessage: translate(`${KEY}.decimalValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_BCVA_DECIMAL`,
  }

  const partial: Omit<AuthoringItem, 'uid'> = {
    name: partialOid,
    oid: partialOid,
    descriptionLabel: translate(`${KEY}.partial`),
    leftItemText: eye,
    rightItemText: '',
    units: '',
    dataType: 'INT',
    responseType: 'text',
    defaultValue: '0',
    required: false,
    responseSet: null,
    validation: {
      // Signed integer in [-4, +4]. Empty / "0" is the common case.
      regexp: '^-?[0-4]$',
      errorMessage: translate(`${KEY}.partialValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_BCVA_PARTIAL`,
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

  return [decimal, partial, sphere, cylinder, axis]
}

/**
 * Produce the ten BCVA-Decimal items in the canonical
 * `[...OD, ...OS]` order so the bilateral grid pairs by suffix.
 */
export function generateBcvaDecimalPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return [
    ...buildEye('OD', 'OS', translate),
    ...buildEye('OS', 'OD', translate),
  ]
}

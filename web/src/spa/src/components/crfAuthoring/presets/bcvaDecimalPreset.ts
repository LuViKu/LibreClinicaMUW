/**
 * BCVA-Decimal preset — per eye: Snellen-equivalent decimal (REAL 0.01–2.0)
 * + signed partial-line marker (INT −4…+4, optotype offset from the line;
 * German convention e.g. `1,0p-2`) + refraction (sphere/cylinder in D, axis
 * 0–180°; reuses bcvaPreset's OIDs so both presets share columns). All-OD
 * first then all-OS; bilateral grid pairs by suffix.
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

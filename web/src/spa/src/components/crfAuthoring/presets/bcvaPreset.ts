/**
 * BCVA preset — bilateral ETDRS letters (INT 0–100) + optional refraction
 * (sphere/cylinder in D, axis 0–180°). Bilateral grid renders OD on the LEFT.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const BCVA_PRESET_ID = 'bcva'

const KEY = 'crfAuthoring.presets.bcva.items'

/** Build a single eye's BCVA quartet: letters, then sphere/cylinder/axis. */
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

/** Eight items, all OD first then all OS; bilateral grid pairs by OID suffix. */
export function generateBcvaPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return [
    ...buildEye('OD', 'OS', translate),
    ...buildEye('OS', 'OD', translate),
  ]
}

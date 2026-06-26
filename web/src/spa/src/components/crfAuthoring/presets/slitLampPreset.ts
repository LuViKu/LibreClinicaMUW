/**
 * Slit-lamp preset — 5 per-eye textareas (cornea / AC / iris / lens / vitreous)
 * + 1 shared OU "Bemerkung" remark (shipped OU, not per-eye). All-OD-first,
 * then all-OS, then the OU remark.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const SLIT_LAMP_PRESET_ID = 'slitLamp'

const KEY = 'crfAuthoring.presets.slitLamp.items'

const SECTIONS: ReadonlyArray<{ suffix: string; labelKey: string }> = [
  { suffix: 'CORNEA', labelKey: 'cornea' },
  { suffix: 'AC', labelKey: 'ac' },
  { suffix: 'IRIS', labelKey: 'iris' },
  { suffix: 'LENS', labelKey: 'lens' },
  { suffix: 'VITREOUS', labelKey: 'vitreous' },
]

function buildEye(
  eye: 'OD' | 'OS',
  pairEye: 'OD' | 'OS',
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return SECTIONS.map((s) => {
    const oid = `${eye}_SLIT_LAMP_${s.suffix}`
    return {
      name: oid,
      oid,
      descriptionLabel: translate(`${KEY}.${s.labelKey}`),
      leftItemText: eye,
      rightItemText: '',
      units: '',
      dataType: 'ST' as const,
      responseType: 'textarea' as const,
      defaultValue: '',
      required: false,
      responseSet: null,
      validation: { regexp: '', errorMessage: '' },
      laterality: eye,
      bilateralPair: `${pairEye}_SLIT_LAMP_${s.suffix}`,
    }
  })
}

export function generateSlitLampPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  const remarkOid = 'OU_SLIT_LAMP_REMARK'
  const remark: Omit<AuthoringItem, 'uid'> = {
    name: remarkOid,
    oid: remarkOid,
    descriptionLabel: translate(`${KEY}.remark`),
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'textarea',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: { regexp: '', errorMessage: '' },
    laterality: 'OU',
  }
  return [
    ...buildEye('OD', 'OS', translate),
    ...buildEye('OS', 'OD', translate),
    remark,
  ]
}

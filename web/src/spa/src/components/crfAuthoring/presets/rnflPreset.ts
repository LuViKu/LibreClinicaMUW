/**
 * RNFL preset — per eye: global mean + 4 quadrant means (S/T/I/N) in µm (INT)
 * + signal-quality score (INT 0–10; MUW reporting scale, not Spectralis' 0–40 dB).
 * All-OD-first then all-OS; bilateral grid pairs on suffix.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const RNFL_PRESET_ID = 'rnfl'

const KEY = 'crfAuthoring.presets.rnfl.items'

const QUADRANT_SUFFIXES: ReadonlyArray<{ suffix: string; labelKey: string }> = [
  { suffix: 'S', labelKey: 'superior' },
  { suffix: 'T', labelKey: 'temporal' },
  { suffix: 'I', labelKey: 'inferior' },
  { suffix: 'N', labelKey: 'nasal' },
]

const UM_VALIDATION_RE = '^([0-9]{1,3})$'
const SIGNAL_VALIDATION_RE = '^(10|[0-9])$'

function buildEye(
  eye: 'OD' | 'OS',
  pairEye: 'OD' | 'OS',
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  const out: Array<Omit<AuthoringItem, 'uid'>> = []

  const globalOid = `${eye}_RNFL_GLOBAL`
  out.push({
    name: globalOid,
    oid: globalOid,
    descriptionLabel: translate(`${KEY}.global`),
    leftItemText: eye,
    rightItemText: '',
    units: 'µm',
    dataType: 'INT',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: UM_VALIDATION_RE,
      errorMessage: translate(`${KEY}.umValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_RNFL_GLOBAL`,
  })

  for (const q of QUADRANT_SUFFIXES) {
    const oid = `${eye}_RNFL_${q.suffix}`
    out.push({
      name: oid,
      oid,
      descriptionLabel: translate(`${KEY}.${q.labelKey}`),
      leftItemText: eye,
      rightItemText: '',
      units: 'µm',
      dataType: 'INT',
      responseType: 'text',
      defaultValue: '',
      required: false,
      responseSet: null,
      validation: {
        regexp: UM_VALIDATION_RE,
        errorMessage: translate(`${KEY}.umValidation`),
      },
      laterality: eye,
      bilateralPair: `${pairEye}_RNFL_${q.suffix}`,
    })
  }

  const signalOid = `${eye}_RNFL_SIGNAL_QUALITY`
  out.push({
    name: signalOid,
    oid: signalOid,
    descriptionLabel: translate(`${KEY}.signalQuality`),
    leftItemText: eye,
    rightItemText: '',
    units: '',
    dataType: 'INT',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: SIGNAL_VALIDATION_RE,
      errorMessage: translate(`${KEY}.signalValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_RNFL_SIGNAL_QUALITY`,
  })

  return out
}

export function generateRnflPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return [
    ...buildEye('OD', 'OS', translate),
    ...buildEye('OS', 'OD', translate),
  ]
}

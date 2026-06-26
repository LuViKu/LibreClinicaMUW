/**
 * BCVA variant presets — Pinhole ({@code PL_BCVA_*}) + Low-Luminance
 * ({@code LL_BCVA_*}). Same shape as bcvaPreset but distinct OID prefix (so
 * variants coexist on one visit) and no refraction fields. Bilateral OD/OS,
 * paired by suffix.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const PL_BCVA_PRESET_ID = 'plBcva'
export const LL_BCVA_PRESET_ID = 'llBcva'

interface BuildArgs {
  translate: Translator
  /** OID prefix (e.g. {@code PL_BCVA} or {@code LL_BCVA}). */
  oidPrefix: string
  /** i18n key prefix (e.g. {@code crfAuthoring.presets.plBcva}). */
  labelKey: string
}

function buildOneEye(args: BuildArgs, eye: 'OD' | 'OS'): Array<Omit<AuthoringItem, 'uid'>> {
  const { translate, oidPrefix, labelKey } = args
  const pairEye: 'OD' | 'OS' = eye === 'OD' ? 'OS' : 'OD'
  const lettersOid = `${eye}_${oidPrefix}_LETTERS`
  const notesOid = `${eye}_${oidPrefix}_NOTES`

  const letters: Omit<AuthoringItem, 'uid'> = {
    name: lettersOid,
    oid: lettersOid,
    descriptionLabel: translate(`${labelKey}.letters.label`),
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
      errorMessage: translate(`${labelKey}.letters.validation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_${oidPrefix}_LETTERS`,
  }

  const notes: Omit<AuthoringItem, 'uid'> = {
    name: notesOid,
    oid: notesOid,
    descriptionLabel: translate(`${labelKey}.notes.label`),
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'textarea',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: { regexp: '', errorMessage: '' },
  }

  return [letters, notes]
}

function generate(args: BuildArgs): Array<Omit<AuthoringItem, 'uid'>> {
  return [
    ...buildOneEye(args, 'OD'),
    ...buildOneEye(args, 'OS'),
  ]
}

export function generatePlBcvaPresetItems(translate: Translator): Array<Omit<AuthoringItem, 'uid'>> {
  return generate({
    translate,
    oidPrefix: 'PL_BCVA',
    labelKey: 'crfAuthoring.presets.plBcva',
  })
}

export function generateLlBcvaPresetItems(translate: Translator): Array<Omit<AuthoringItem, 'uid'>> {
  return generate({
    translate,
    oidPrefix: 'LL_BCVA',
    labelKey: 'crfAuthoring.presets.llBcva',
  })
}

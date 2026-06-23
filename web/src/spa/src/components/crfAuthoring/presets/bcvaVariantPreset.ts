/**
 * 2026-06-23 user-feedback round — BCVA variant presets (Pinhole +
 * Low-Luminance).
 *
 * <p>Both clinical BCVA variants share the same item shape as the
 * canonical {@link bcvaPreset} but with a distinct OID prefix so a
 * single visit can carry uncorrected, pinhole, and low-luminance
 * readings side-by-side without name collisions:
 *
 * <ul>
 *   <li><b>PL-BCVA</b> ({@code PL_BCVA_*}) — Pinhole BCVA. The
 *       refraction-at-test fields are dropped (the pinhole removes
 *       most refractive error by design); only the letter score
 *       remains.</li>
 *   <li><b>LL-BCVA</b> ({@code LL_BCVA_*}) — Low-luminance BCVA,
 *       typically measured with a 2.0 ND filter. Captures the letter
 *       score under photopic-to-mesopic adaptation; refraction-at-test
 *       again dropped per the clinical protocol.</li>
 * </ul>
 *
 * <p>Bilateral OD/OS pair emitted in the same order as
 * {@code generateOphthSectionItems} so the bilateral grid pairs by
 * suffix.
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

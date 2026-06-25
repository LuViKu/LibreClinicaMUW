/**
 * D4 (2026-06-20) — Slit-lamp anterior segment examination preset.
 *
 * <p>Bilateral free-text capture of the anterior segment exam sections
 * (cornea / anterior chamber / iris / lens / vitreous) plus a single
 * overall remark item ("Bemerkung"). Each per-eye field is a textarea
 * so the clinician can copy the canonical paper-form prose verbatim;
 * standardisation can happen downstream via an annotated CRF.
 *
 * <p>Items per eye:
 * <ol>
 *   <li>{@code OD_SLIT_LAMP_CORNEA} / OS — Cornea (ST textarea).</li>
 *   <li>{@code OD_SLIT_LAMP_AC} / OS — Anterior chamber.</li>
 *   <li>{@code OD_SLIT_LAMP_IRIS} / OS — Iris.</li>
 *   <li>{@code OD_SLIT_LAMP_LENS} / OS — Lens (crystalline / IOL).</li>
 *   <li>{@code OD_SLIT_LAMP_VITREOUS} / OS — Anterior vitreous.</li>
 * </ol>
 *
 * <p>Plus a single OU (both-eyes) remark item:
 * <ol>
 *   <li>{@code OU_SLIT_LAMP_REMARK} — overall examination notes
 *       (ST textarea, no laterality metadata so the bilateral grid
 *       renders it spanning both columns).</li>
 * </ol>
 *
 * <p>Order: {@code [OD_cornea, OD_AC, OD_iris, OD_lens, OD_vitreous,
 * OS_cornea, OS_AC, OS_iris, OS_lens, OS_vitreous, OU_remark]} —
 * mirrors the BCVA / RNFL / THICKNESS_MAP D4 batch.
 *
 * <p>CHOICE: the brief asked for "Plus an overall 'Bemerkung'
 * free-text" — we ship it as OU (single shared remark) rather than
 * per-eye. Per-eye remarks are commonly redundant; sites that need
 * them can duplicate the remark on the properties rail.
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

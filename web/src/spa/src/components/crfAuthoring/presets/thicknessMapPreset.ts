/**
 * D4 (2026-06-20) — ETDRS 9-zone macular thickness map preset.
 *
 * <p>Bilateral capture of the nine ETDRS subfields per the standard
 * ETDRS macular grid: one central circle + four inner ring subfields
 * (S/T/I/N) + four outer ring subfields (S/T/I/N "outer"). Source is a
 * Spectralis macular cube; a single "modality code" item is included
 * per eye so the trial reviewer can pin which device captured the map.
 *
 * <p>ETDRS subfields per eye (per Early Treatment Diabetic Retinopathy
 * Study, 1991):
 * <ol>
 *   <li>{@code OD_THICKNESS_CENTRAL} / OS — central 1mm subfield.</li>
 *   <li>{@code OD_THICKNESS_INNER_S} / OS — inner superior (1–3mm).</li>
 *   <li>{@code OD_THICKNESS_INNER_T} / OS — inner temporal.</li>
 *   <li>{@code OD_THICKNESS_INNER_I} / OS — inner inferior.</li>
 *   <li>{@code OD_THICKNESS_INNER_N} / OS — inner nasal.</li>
 *   <li>{@code OD_THICKNESS_OUTER_S} / OS — outer superior (3–6mm).</li>
 *   <li>{@code OD_THICKNESS_OUTER_T} / OS — outer temporal.</li>
 *   <li>{@code OD_THICKNESS_OUTER_I} / OS — outer inferior.</li>
 *   <li>{@code OD_THICKNESS_OUTER_N} / OS — outer nasal.</li>
 *   <li>{@code OD_THICKNESS_MODALITY} / OS — Spectralis modality code
 *       single-select (SPECTRALIS / CIRRUS / TOPCON / OTHER).</li>
 * </ol>
 *
 * <p>Order: all-OD-first then all-OS, same as the other D4 presets.
 *
 * <p>CHOICE: brief said "Spectralis modality code" — we expose a small
 * single-select so multi-device sites (e.g. MUW satellite clinics
 * borrow Cirrus or Topcon) don't have to drop the preset and
 * re-author; the operator can rename / restrict on the properties rail.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const THICKNESS_MAP_PRESET_ID = 'thicknessMap'

const KEY = 'crfAuthoring.presets.thicknessMap.items'

/**
 * The ETDRS subfield grid in canonical authoring order. Tuples are
 * (oidSuffix, labelKey) — the OID is built as
 * {@code ${eye}_THICKNESS_${oidSuffix}}.
 */
const ETDRS_ZONES: ReadonlyArray<{ suffix: string; labelKey: string }> = [
  { suffix: 'CENTRAL', labelKey: 'central' },
  { suffix: 'INNER_S', labelKey: 'innerSuperior' },
  { suffix: 'INNER_T', labelKey: 'innerTemporal' },
  { suffix: 'INNER_I', labelKey: 'innerInferior' },
  { suffix: 'INNER_N', labelKey: 'innerNasal' },
  { suffix: 'OUTER_S', labelKey: 'outerSuperior' },
  { suffix: 'OUTER_T', labelKey: 'outerTemporal' },
  { suffix: 'OUTER_I', labelKey: 'outerInferior' },
  { suffix: 'OUTER_N', labelKey: 'outerNasal' },
]

const UM_VALIDATION_RE = '^([0-9]{1,3})$'

export const THICKNESS_MAP_MODALITY_OPTIONS = {
  SPECTRALIS: 'SPECTRALIS',
  CIRRUS: 'CIRRUS',
  TOPCON: 'TOPCON',
  OTHER: 'OTHER',
} as const

function buildEye(
  eye: 'OD' | 'OS',
  pairEye: 'OD' | 'OS',
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  const out: Array<Omit<AuthoringItem, 'uid'>> = []

  for (const z of ETDRS_ZONES) {
    const oid = `${eye}_THICKNESS_${z.suffix}`
    out.push({
      name: oid,
      oid,
      descriptionLabel: translate(`${KEY}.${z.labelKey}`),
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
      bilateralPair: `${pairEye}_THICKNESS_${z.suffix}`,
    })
  }

  const modalityOid = `${eye}_THICKNESS_MODALITY`
  out.push({
    name: modalityOid,
    oid: modalityOid,
    descriptionLabel: translate(`${KEY}.modality`),
    leftItemText: eye,
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'single-select',
    defaultValue: '',
    required: false,
    responseSet: {
      type: 'single-select',
      label: 'thickness_map_modality',
      options: [
        { text: translate(`${KEY}.modalitySpectralis`), value: THICKNESS_MAP_MODALITY_OPTIONS.SPECTRALIS },
        { text: translate(`${KEY}.modalityCirrus`), value: THICKNESS_MAP_MODALITY_OPTIONS.CIRRUS },
        { text: translate(`${KEY}.modalityTopcon`), value: THICKNESS_MAP_MODALITY_OPTIONS.TOPCON },
        { text: translate(`${KEY}.modalityOther`), value: THICKNESS_MAP_MODALITY_OPTIONS.OTHER },
      ],
    },
    validation: { regexp: '', errorMessage: '' },
    laterality: eye,
    bilateralPair: `${pairEye}_THICKNESS_MODALITY`,
  })

  return out
}

export function generateThicknessMapPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return [
    ...buildEye('OD', 'OS', translate),
    ...buildEye('OS', 'OD', translate),
  ]
}

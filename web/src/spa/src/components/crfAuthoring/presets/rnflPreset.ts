/**
 * D4 (2026-06-20) — RNFL (Retinal Nerve Fiber Layer) thickness preset.
 *
 * <p>Bilateral grouped block from a Heidelberg HRA / Spectralis ONH
 * (Optic Nerve Head) module run. Captures the global mean RNFL
 * thickness plus the four quadrant means (Superior / Temporal /
 * Inferior / Nasal) plus a signal-quality score per eye.
 *
 * <p>Items per eye:
 * <ol>
 *   <li><b>{@code OD_RNFL_GLOBAL} / {@code OS_RNFL_GLOBAL}</b> — global
 *       mean RNFL thickness in µm (INT, 0–999). The "global" reading
 *       is the average around the peripapillary circle.</li>
 *   <li><b>{@code OD_RNFL_S} / OS</b> — Superior quadrant (INT µm).</li>
 *   <li><b>{@code OD_RNFL_T} / OS</b> — Temporal quadrant (INT µm).</li>
 *   <li><b>{@code OD_RNFL_I} / OS</b> — Inferior quadrant (INT µm).</li>
 *   <li><b>{@code OD_RNFL_N} / OS</b> — Nasal quadrant (INT µm).</li>
 *   <li><b>{@code OD_RNFL_SIGNAL_QUALITY} / OS</b> — Heidelberg signal
 *       quality score (INT, 0–10). Spectralis exports an integer Q
 *       score in 0–40 dB, but the MUW reporting convention is the
 *       0–10 abbreviation used by clinicians on paper review; the
 *       wider range can be authored post-drop on the properties rail.
 *       CHOICE.</li>
 * </ol>
 *
 * <p>Order: {@code [OD_global, OD_S, OD_T, OD_I, OD_N, OD_quality,
 * OS_global, OS_S, OS_T, OS_I, OS_N, OS_quality]} — all-OD-first then
 * all-OS, same as {@code bcvaPreset}. Bilateral grid pairs on suffix.
 *
 * <p>CHOICE: brief says "Heidelberg HRA or Spectralis ONH module" —
 * the items themselves don't encode the modality (modality is captured
 * upstream on the visit), so the preset is one shape that fits both
 * sources.
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

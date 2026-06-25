/**
 * 2026-06-24 — CRT (Central Retinal Thickness, central 1 mm)
 * authoring preset for the CRF canvas.
 *
 * <p>Materialises the two per-eye items
 * ({@code OD_CRT_CENTRAL_1MM_UM} / {@code OS_CRT_CENTRAL_1MM_UM})
 * that {@link RetinalResultItemDataPopulator} auto-populates after
 * pairing a GA + BM done job pair via {@link CrtComputeService}.
 *
 * <p>Both items are READ-only-by-convention from the operator's
 * perspective — the values land via the auto-populate path. The
 * preset still emits standard editable fields so a physician can
 * override (operator overrides win; the audit trail records the
 * source job pair).
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const CRT_PRESET_ID = 'crt'

const KEY = 'crfAuthoring.presets.crt.items'

function buildEye(
  eye: 'OD' | 'OS',
  pairEye: 'OD' | 'OS',
  translate: Translator,
): Omit<AuthoringItem, 'uid'> {
  const oid = `${eye}_CRT_CENTRAL_1MM_UM`
  return {
    name: oid,
    oid,
    descriptionLabel: translate(`${KEY}.crtMicrons`),
    leftItemText: eye,
    rightItemText: 'µm',
    units: 'µm',
    dataType: 'REAL',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      // 0..1500 µm covers every clinical scenario (normal ≈ 250,
      // severe oedema can reach 800-1000, anything above ≈ 1500
      // is almost certainly a measurement artifact).
      regexp: '^(0|[1-9][0-9]{0,3}|1[0-4][0-9]{2}|1500)(\\.[0-9]+)?$',
      errorMessage: translate(`${KEY}.crtValidation`),
    },
    laterality: eye,
    bilateralPair: `${pairEye}_CRT_CENTRAL_1MM_UM`,
  }
}

/** Two-item preset, OD first then OS (bilateral grid pairs by suffix). */
export function generateCrtPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return [
    buildEye('OD', 'OS', translate),
    buildEye('OS', 'OD', translate),
  ]
}

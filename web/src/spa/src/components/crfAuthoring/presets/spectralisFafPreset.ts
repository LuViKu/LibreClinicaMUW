/**
 * 2026-06-23 user-feedback round — Spectralis FAF acquisition preset.
 *
 * Heidelberg Spectralis fundus autofluorescence (488 nm + Bluepeak).
 * Same scaffold as Spectralis OCT; the per-device-OID prefix
 * disambiguates the two on the wire.
 */
import type { AuthoringItem } from '@/stores/crfAuthoring'
import { generateImagingAcquisitionItems, type Translator } from './imagingAcquisitionPreset'

export const SPECTRALIS_FAF_PRESET_ID = 'spectralisFaf'

export function generateSpectralisFafPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return generateImagingAcquisitionItems({
    translate,
    oidPrefix: 'SPEC_FAF',
    labelKey: 'crfAuthoring.presets.spectralisFaf',
  })
}

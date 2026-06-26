/** Spectralis FAF (fundus autofluorescence) acquisition preset (shared scaffold, OID prefix 'SPEC_FAF'). */
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

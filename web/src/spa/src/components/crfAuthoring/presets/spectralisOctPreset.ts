/** Spectralis SD-OCT acquisition preset (shared scaffold, OID prefix 'SPEC_OCT'). */
import type { AuthoringItem } from '@/stores/crfAuthoring'
import { generateImagingAcquisitionItems, type Translator } from './imagingAcquisitionPreset'

export const SPECTRALIS_OCT_PRESET_ID = 'spectralisOct'

export function generateSpectralisOctPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return generateImagingAcquisitionItems({
    translate,
    oidPrefix: 'SPEC_OCT',
    labelKey: 'crfAuthoring.presets.spectralisOct',
  })
}

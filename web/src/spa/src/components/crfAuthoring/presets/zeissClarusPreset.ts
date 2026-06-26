/** ZEISS Clarus 700 ultra-widefield fundus preset (shared scaffold, OID prefix 'CLARUS'). */
import type { AuthoringItem } from '@/stores/crfAuthoring'
import { generateImagingAcquisitionItems, type Translator } from './imagingAcquisitionPreset'

export const ZEISS_CLARUS_PRESET_ID = 'zeissClarus'

export function generateZeissClarusPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return generateImagingAcquisitionItems({
    translate,
    oidPrefix: 'CLARUS',
    labelKey: 'crfAuthoring.presets.zeissClarus',
  })
}

/**
 * 2026-06-23 user-feedback round — ZEISS Clarus 700 wide-field
 * fundus preset.
 *
 * Ultra-widefield true-color fundus photography. Uses the shared
 * acquisition scaffold; lesion description / peripheral findings go
 * in downstream interpretation eCRFs.
 */
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

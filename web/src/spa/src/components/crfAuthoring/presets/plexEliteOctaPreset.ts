/**
 * 2026-06-23 user-feedback round — ZEISS PLEX Elite 9000 OCT-A
 * acquisition preset.
 *
 * Swept-source OCT-angiography. Same scaffold as the other imaging
 * presets; clinical interpretation (vessel density, FAZ metrics)
 * lives in downstream eCRFs and isn't part of the acquisition
 * capture.
 */
import type { AuthoringItem } from '@/stores/crfAuthoring'
import { generateImagingAcquisitionItems, type Translator } from './imagingAcquisitionPreset'

export const PLEX_ELITE_OCTA_PRESET_ID = 'plexEliteOcta'

export function generatePlexEliteOctaPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return generateImagingAcquisitionItems({
    translate,
    oidPrefix: 'PLEX_OCTA',
    labelKey: 'crfAuthoring.presets.plexEliteOcta',
  })
}

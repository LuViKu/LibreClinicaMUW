/** ZEISS PLEX Elite 9000 OCT-A acquisition preset (shared scaffold, OID prefix 'PLEX_OCTA'). */
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

/** Topcon Maestro2 SD-OCT acquisition preset (shared scaffold, OID prefix 'TOPCON_OCT'). */
import type { AuthoringItem } from '@/stores/crfAuthoring'
import { generateImagingAcquisitionItems, type Translator } from './imagingAcquisitionPreset'

export const TOPCON_MAESTRO2_OCT_PRESET_ID = 'topconMaestro2Oct'

export function generateTopconMaestro2OctPresetItems(
  translate: Translator,
): Array<Omit<AuthoringItem, 'uid'>> {
  return generateImagingAcquisitionItems({
    translate,
    oidPrefix: 'TOPCON_OCT',
    labelKey: 'crfAuthoring.presets.topconMaestro2Oct',
  })
}

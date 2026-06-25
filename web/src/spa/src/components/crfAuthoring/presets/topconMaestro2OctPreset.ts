/**
 * 2026-06-23 user-feedback round — Topcon Maestro2 SD-OCT
 * acquisition preset.
 *
 * Tabletop SD-OCT used as the screening-line scanner in the MUW
 * clinic. Uses the shared acquisition scaffold; SmartCapture-driven
 * quality metrics map onto the same 1-5 quality scale.
 */
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

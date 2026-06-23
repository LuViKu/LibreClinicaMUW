/**
 * 2026-06-23 user-feedback round — Spectralis OCT acquisition preset.
 *
 * Heidelberg Spectralis SD-OCT — the workhorse posterior segment
 * scanner in the MUW eye clinic. Wraps the shared imaging-acquisition
 * scaffold with the Spectralis OID prefix + label key.
 */
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

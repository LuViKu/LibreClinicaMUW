/**
 * 2026-06-23 user-feedback round — shared scaffold for device-specific
 * imaging acquisition presets (Spectralis OCT/FAF, Plex ELITE OCT-A,
 * ZEISS Clarus, Topcon Maestro2 OCT).
 *
 * <p>Minimum capture: per the operator's 2026-06-23 follow-up the
 * scaffold is intentionally lean — a single Yes/No/Unknown tristate
 * plus a single conditional reason textarea that surfaces only when
 * the answer is "No". No quality / notes fields are emitted by default;
 * downstream studies that need richer capture can extend the section
 * after dropping the preset.
 *
 * <p>The device-specific wrappers re-use this shape with a per-device
 * OID prefix + label so the catalog surfaces them as distinct presets
 * but the underlying clinical capture stays consistent.
 *
 * <p>Bilateral by default — paired imaging is the norm for clinical
 * studies. Operators flip to unilateral via the section toggle when
 * only one eye is imaged at a given visit.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

export type Translator = (key: string) => string

export const ACQUIRED_OPTIONS = {
  JA: 'JA',
  NEIN: 'NEIN',
  UNBEKANNT: 'UNBEKANNT',
} as const

interface BuildArgs {
  translate: Translator
  oidPrefix: string
  /** Localisation key prefix (e.g. {@code crfAuthoring.presets.spectralisOct}). */
  labelKey: string
}

function buildOneEye(args: BuildArgs, eye: 'OD' | 'OS'): Array<Omit<AuthoringItem, 'uid'>> {
  const { translate, oidPrefix, labelKey } = args
  const eyeSuffix = ` (${translate(`common.eye.${eye === 'OD' ? 'od' : 'os'}`)})`
  const acquiredOid = `${eye}_${oidPrefix}_ACQUIRED`
  const reasonOid = `${eye}_${oidPrefix}_REASON`

  const acquired: Omit<AuthoringItem, 'uid'> = {
    name: acquiredOid,
    oid: acquiredOid,
    descriptionLabel: translate(`${labelKey}.acquired.label`) + eyeSuffix,
    leftItemText: eye,
    rightItemText: '',
    units: '',
    dataType: 'TRISTATE_REASON',
    responseType: 'single-select',
    defaultValue: '',
    required: false,
    responseSet: {
      type: 'single-select',
      label: `${oidPrefix.toLowerCase()}_acquired_${eye.toLowerCase()}`,
      options: [
        { text: translate('crfAuthoring.presets.imaging.option.ja'), value: ACQUIRED_OPTIONS.JA },
        { text: translate('crfAuthoring.presets.imaging.option.nein'), value: ACQUIRED_OPTIONS.NEIN },
        { text: translate('crfAuthoring.presets.imaging.option.unbekannt'), value: ACQUIRED_OPTIONS.UNBEKANNT },
      ],
    },
    validation: { regexp: '', errorMessage: '' },
  }

  const reason: Omit<AuthoringItem, 'uid'> = {
    name: reasonOid,
    oid: reasonOid,
    descriptionLabel: translate('crfAuthoring.presets.imaging.reason.label') + eyeSuffix,
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'textarea',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: { regexp: '', errorMessage: '' },
    showWhen: {
      sourceItemOid: acquiredOid,
      comparator: '==',
      literal: ACQUIRED_OPTIONS.NEIN,
    },
  }

  return [acquired, reason]
}

/**
 * Build OD + OS interleaved so the bilateral grid in SectionCanvas
 * pairs items by suffix the same way IOP / EXAM / BCVA do.
 */
export function generateImagingAcquisitionItems(
  args: BuildArgs,
): Array<Omit<AuthoringItem, 'uid'>> {
  const od = buildOneEye(args, 'OD')
  const os = buildOneEye(args, 'OS')
  // Interleave per-row so OD/OS pair: acquired, reason.
  return [
    od[0]!, os[0]!,
    od[1]!, os[1]!,
  ]
}

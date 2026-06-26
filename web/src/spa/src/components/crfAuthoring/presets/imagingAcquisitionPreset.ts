/**
 * Shared imaging-acquisition scaffold for device-specific presets. Per eye:
 * a Yes/No/Unknown tristate (TRISTATE_REASON) + a conditional reason textarea
 * shown only when "Nein". Device wrappers reuse this shape with a per-device
 * OID prefix + label key. Bilateral by default.
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

/**
 * 2026-06-23 user-feedback round — shared scaffold for device-specific
 * imaging acquisition presets (Spectralis OCT/FAF, Plex ELITE OCT-A,
 * ZEISS Clarus, Topcon Maestro2 OCT).
 *
 * <p>Every imaging acquisition follows the same minimum capture
 * pattern in the deployed MUW workflow:
 *
 * <ol>
 *   <li><b>Acquired?</b> — {@code TRISTATE_REASON} (Erfolgreich / Nicht möglich
 *       / Nicht versucht). Drives the conditional-show on the failure-reason
 *       child so the operator only sees the textarea when the answer is
 *       "Nicht möglich".</li>
 *   <li><b>Image quality</b> — {@code INT}, 1..5 scale (1 = unverwertbar,
 *       5 = sehr gut). Operator-discretion shorthand; the device-specific
 *       protocol can map onto it (Spectralis Q-score → 5pt, Topcon
 *       autoclassifier → 5pt, etc.).</li>
 *   <li><b>Failure reason</b> — {@code ST} textarea, shown only when the
 *       parent is "Nicht möglich".</li>
 *   <li><b>Notes</b> — free-form acquisition notes, optional.</li>
 * </ol>
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
  ERFOLGREICH: 'ERFOLGREICH',
  NICHT_MOEGLICH: 'NICHT_MOEGLICH',
  NICHT_VERSUCHT: 'NICHT_VERSUCHT',
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
  const qualityOid = `${eye}_${oidPrefix}_QUALITY`
  const reasonOid = `${eye}_${oidPrefix}_FAIL_REASON`
  const notesOid = `${eye}_${oidPrefix}_NOTES`

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
        { text: translate('crfAuthoring.presets.imaging.option.erfolgreich'), value: ACQUIRED_OPTIONS.ERFOLGREICH },
        { text: translate('crfAuthoring.presets.imaging.option.nichtMoeglich'), value: ACQUIRED_OPTIONS.NICHT_MOEGLICH },
        { text: translate('crfAuthoring.presets.imaging.option.nichtVersucht'), value: ACQUIRED_OPTIONS.NICHT_VERSUCHT },
      ],
    },
    validation: { regexp: '', errorMessage: '' },
  }

  const quality: Omit<AuthoringItem, 'uid'> = {
    name: qualityOid,
    oid: qualityOid,
    descriptionLabel: translate(`${labelKey}.quality.label`) + eyeSuffix,
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'INT',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: '^[1-5]$',
      errorMessage: translate('crfAuthoring.presets.imaging.quality.validation'),
    },
    showWhen: {
      sourceItemOid: acquiredOid,
      comparator: '==',
      literal: ACQUIRED_OPTIONS.ERFOLGREICH,
    },
  }

  const reason: Omit<AuthoringItem, 'uid'> = {
    name: reasonOid,
    oid: reasonOid,
    descriptionLabel: translate('crfAuthoring.presets.imaging.failReason.label') + eyeSuffix,
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
      literal: ACQUIRED_OPTIONS.NICHT_MOEGLICH,
    },
  }

  const notes: Omit<AuthoringItem, 'uid'> = {
    name: notesOid,
    oid: notesOid,
    descriptionLabel: translate('crfAuthoring.presets.imaging.notes.label') + eyeSuffix,
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'textarea',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: { regexp: '', errorMessage: '' },
  }

  return [acquired, quality, reason, notes]
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
  // Interleave per-row so OD/OS pair: acquired, quality, reason, notes.
  return [
    od[0]!, os[0]!,
    od[1]!, os[1]!,
    od[2]!, os[2]!,
    od[3]!, os[3]!,
  ]
}

/**
 * IOP preset — tristate parent (TRISTATE_REASON; wire single-select JA / NEIN /
 * UNBEKANNT) + conditional children: value (REAL mmHg, shown when JA) and reason
 * (ST textarea, shown when NEIN). Hidden values kept client-side, not persisted
 * (show-when spec). Emits OD + OS bilateral pairs.
 */

import type { AuthoringItem } from '@/stores/crfAuthoring'

/**
 * The three canonical parent options. Locked here so the conditional
 * children's show-when literals stay in sync with the rendered widget.
 */
export const IOP_PARENT_OPTIONS = {
  JA: 'JA',
  NEIN: 'NEIN',
  UNBEKANNT: 'UNBEKANNT',
} as const

/**
 * Produce the IOP preset's items as {@code Omit<AuthoringItem, 'uid'>}.
 * The caller (the store's {@code applyPreset}) injects stable uids via
 * the shared {@code nextUid()} helper so vuedraggable's item-key stays
 * unique.
 *
 * <p>The translator parameter mirrors the {@link Translator} signature
 * used by the OPHTH_EXAM preset generator — tests can pass an identity
 * stub; the canvas view passes {@code t()} from {@code useI18n()}.
 */
export type Translator = (key: string) => string

export interface IopPresetOptions {
  /**
   * OID prefix for the materialised items. Defaults to {@code 'IOP'}.
   * Operators can rename via the properties rail.
   */
  oidPrefix?: string
}

/**
 * Build the three IOP items (parent + value + reason) for a single
 * eye. Pulled out so we can call it twice when generating the OD/OS
 * bilateral pair without duplicating the structure.
 */
function buildIopTriple(
  translate: Translator,
  eyePrefix: string,
  prefix: string,
): Array<Omit<AuthoringItem, 'uid'>> {
  const tag = eyePrefix ? `${eyePrefix}_` : ''
  const parentOid = `${tag}${prefix}_GEMESSEN`
  const valueOid = `${tag}${prefix}_VALUE`
  const reasonOid = `${tag}${prefix}_REASON`

  const eyeSuffix = eyePrefix === 'OD'
    ? ` (${translate('common.eye.od')})`
    : eyePrefix === 'OS'
      ? ` (${translate('common.eye.os')})`
      : ''

  const parent: Omit<AuthoringItem, 'uid'> = {
    name: parentOid,
    oid: parentOid,
    descriptionLabel: translate('crfAuthoring.presets.iop.parent.label') + eyeSuffix,
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'TRISTATE_REASON',
    responseType: 'single-select',
    defaultValue: '',
    required: false,
    responseSet: {
      type: 'single-select',
      label: `iop_tristate${eyePrefix ? '_' + eyePrefix.toLowerCase() : ''}`,
      options: [
        { text: translate('crfAuthoring.presets.iop.option.ja'), value: IOP_PARENT_OPTIONS.JA },
        { text: translate('crfAuthoring.presets.iop.option.nein'), value: IOP_PARENT_OPTIONS.NEIN },
        { text: translate('crfAuthoring.presets.iop.option.unbekannt'), value: IOP_PARENT_OPTIONS.UNBEKANNT },
      ],
    },
    validation: { regexp: '', errorMessage: '' },
  }

  const value: Omit<AuthoringItem, 'uid'> = {
    name: valueOid,
    oid: valueOid,
    descriptionLabel: translate('crfAuthoring.presets.iop.value.label') + eyeSuffix,
    leftItemText: '',
    rightItemText: '',
    units: 'mmHg',
    dataType: 'REAL',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: {
      regexp: '^-?\\d+(\\.\\d+)?$',
      errorMessage: translate('crfAuthoring.presets.iop.value.validation'),
    },
    showWhen: {
      sourceItemOid: parentOid,
      comparator: '==',
      literal: IOP_PARENT_OPTIONS.JA,
    },
  }

  const reason: Omit<AuthoringItem, 'uid'> = {
    name: reasonOid,
    oid: reasonOid,
    descriptionLabel: translate('crfAuthoring.presets.iop.reason.label') + eyeSuffix,
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
      sourceItemOid: parentOid,
      comparator: '==',
      literal: IOP_PARENT_OPTIONS.NEIN,
    },
  }

  return [parent, value, reason]
}

/** Emits OD + OS pairs (labelled OD_/OS_); bilateral grid pairs by suffix. */
export function generateIopPresetItems(
  translate: Translator,
  opts: IopPresetOptions = {},
): Array<Omit<AuthoringItem, 'uid'>> {
  const prefix = (opts.oidPrefix ?? 'IOP')
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '') || 'IOP'

  // Interleave OD/OS so the per-eye parent + its show-when children
  // stay adjacent in the items list — important for the bilateral
  // grid pairing AND for the unilateral fallback flat list.
  const od = buildIopTriple(translate, 'OD', prefix)
  const os = buildIopTriple(translate, 'OS', prefix)
  return [od[0]!, os[0]!, od[1]!, os[1]!, od[2]!, os[2]!]
}

export const IOP_PRESET_ID = 'iop'

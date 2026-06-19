/**
 * App-feedback Wave 2 (2026-06-19) — IOP measurement preset.
 *
 * <p>The IOP preset materialises the canonical "IOP gemessen?
 * Ja / Nein / Unbekannt" clinical pattern as two paired authoring items:
 *
 * <ol>
 *   <li><b>Parent</b> — {@code IOP_GEMESSEN} ({@code TRISTATE_REASON}):
 *       Ja / Nein / Unbekannt segmented control. On the wire the parent
 *       materialises to a single-select with three canonical option codes
 *       (JA / NEIN / UNBEKANNT) so the show-when machinery can reference
 *       the parent value verbatim.</li>
 *   <li><b>Child A</b> — {@code IOP_VALUE} ({@code REAL}, mmHg): an IOP
 *       numeric input that is shown only when the parent equals "JA"
 *       (operator was able to measure IOP, the actual value goes here).</li>
 *   <li><b>Child B</b> — {@code IOP_REASON} ({@code ST} textarea): a free-
 *       text reason that is shown only when the parent equals "NEIN"
 *       (clinician explains why IOP could not be measured).</li>
 * </ol>
 *
 * <p>Hidden values are preserved client-side but not persisted, per the
 * show-when spec.
 *
 * <p>The preset is intentionally OU (both eyes) — IOP measurements in
 * the deployed MUW workflow are paired per visit and the bilateral
 * grouping happens at the section level rather than the preset level.
 * A future bilateral variant can be added without renaming this one.
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
   * OID prefix for the materialised items. Defaults to {@code 'IOP'}
   * so a single drop produces {@code IOP_GEMESSEN} + {@code IOP_VALUE}
   * + {@code IOP_REASON}. Operators can rename via the properties rail.
   */
  oidPrefix?: string
}

export function generateIopPresetItems(
  translate: Translator,
  opts: IopPresetOptions = {},
): Array<Omit<AuthoringItem, 'uid'>> {
  const prefix = (opts.oidPrefix ?? 'IOP').trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'IOP'
  const parentOid = `${prefix}_GEMESSEN`
  const valueOid = `${prefix}_VALUE`
  const reasonOid = `${prefix}_REASON`

  const parent: Omit<AuthoringItem, 'uid'> = {
    name: parentOid,
    oid: parentOid,
    descriptionLabel: translate('crfAuthoring.presets.iop.parent.label'),
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'TRISTATE_REASON',
    responseType: 'single-select',
    defaultValue: '',
    required: false,
    responseSet: {
      type: 'single-select',
      label: 'iop_tristate',
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
    descriptionLabel: translate('crfAuthoring.presets.iop.value.label'),
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
    descriptionLabel: translate('crfAuthoring.presets.iop.reason.label'),
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

export const IOP_PRESET_ID = 'iop'

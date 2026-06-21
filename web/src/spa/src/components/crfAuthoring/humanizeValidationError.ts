/**
 * 2026-06-21 user-feedback batch — backend → SPA error message localiser.
 *
 * <p>The CRF JSON validator (Java) emits stable English strings like
 * "Item name is required" or "Data type must be one of ...". The user
 * wanted these surfaced in German in the canvas's error summary
 * (everything else in the surface is German).
 *
 * <p>The pragmatic path — without rewriting the validator to emit
 * codes + parameters — is a lookup table on the SPA side. This module
 * exposes {@link humanizeValidationError}: pass the raw English
 * message + a vue-i18n {@code t()} reference; get back a localised
 * string or the original message verbatim when no mapping exists.
 *
 * <p>Strategy:
 *
 * <ol>
 *   <li>Try an exact-match lookup against the known message set
 *       (covers the common "X is required" / "X must contain only Y"
 *       cases).</li>
 *   <li>Try a prefix-match against parameterised messages — e.g. the
 *       "Data type must be one of …" string changes whenever the
 *       allowed-types list grows, so we match on the prefix
 *       "Data type must be one of".</li>
 *   <li>Fall back to the raw message verbatim — the operator still
 *       sees something actionable; future i18n additions just lift
 *       more mappings into the table.</li>
 * </ol>
 *
 * <p>Translator keys live under {@code crfAuthoring.validation.*} in
 * the locale files. Adding a new mapping is two lines: a regex/
 * literal here + the matching key on both DE and EN bundles.
 */

export type ValidationTranslator = (key: string) => string

interface ExactRule {
  source: string
  key: string
}

interface PrefixRule {
  prefix: string
  key: string
}

const EXACT_RULES: ExactRule[] = [
  { source: 'Item name is required',                key: 'crfAuthoring.validation.itemNameRequired' },
  { source: 'Description label is required',        key: 'crfAuthoring.validation.descriptionRequired' },
  { source: 'Data type is required',                key: 'crfAuthoring.validation.dataTypeRequired' },
  { source: 'Response type is required',            key: 'crfAuthoring.validation.responseTypeRequired' },
  { source: 'Section title is required',            key: 'crfAuthoring.validation.sectionTitleRequired' },
  { source: 'Section label is required',            key: 'crfAuthoring.validation.sectionLabelRequired' },
  { source: 'At least one section is required',     key: 'crfAuthoring.validation.atLeastOneSection' },
  { source: 'Section must contain at least one item', key: 'crfAuthoring.validation.atLeastOneItem' },
  { source: 'Option text is required',              key: 'crfAuthoring.validation.optionTextRequired' },
  { source: 'Option value is required',             key: 'crfAuthoring.validation.optionValueRequired' },
  { source: 'Calculation formula must not be empty', key: 'crfAuthoring.validation.calculationFormulaRequired' },
  { source: 'Expression must not be empty',         key: 'crfAuthoring.validation.expressionRequired' },
  { source: 'versionName is required',              key: 'crfAuthoring.validation.versionNameRequired' },
  { source: 'versionDescription is required',       key: 'crfAuthoring.validation.versionDescriptionRequired' },
  { source: "Items with data type FILE must declare a 'file' response set",
    key: 'crfAuthoring.validation.fileResponseSetRequired' },
  { source: 'Item name must contain only letters, digits and underscores',
    key: 'crfAuthoring.validation.itemNameInvalidChars' },
]

const PREFIX_RULES: PrefixRule[] = [
  { prefix: 'Data type must be one of',     key: 'crfAuthoring.validation.dataTypeUnsupported' },
  { prefix: 'Response type must be one of', key: 'crfAuthoring.validation.responseTypeUnsupported' },
  { prefix: 'Duplicate item name',          key: 'crfAuthoring.validation.duplicateItemName' },
]

/**
 * Localise a single validator message. Returns the input string when
 * no mapping exists so the operator still sees an actionable line.
 */
export function humanizeValidationError(
  raw: string | null | undefined,
  t: ValidationTranslator,
): string {
  if (!raw) return ''
  const trimmed = raw.trim()
  if (!trimmed) return ''

  for (const rule of EXACT_RULES) {
    if (rule.source === trimmed) return t(rule.key)
  }
  for (const rule of PREFIX_RULES) {
    if (trimmed.startsWith(rule.prefix)) return t(rule.key)
  }
  return trimmed
}

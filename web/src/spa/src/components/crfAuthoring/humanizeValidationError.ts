/**
 * Localize backend validation errors via lookup table. The Java CRF
 * validator emits stable English strings; this maps them to German keys
 * under {@code crfAuthoring.validation.*} (exact-match, then prefix-match
 * for parameterised messages), falling back to the raw message verbatim.
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
  { source: 'Item name is required',                key: 'crfAuthoring.canvas.validation.itemNameRequired' },
  { source: 'Description label is required',        key: 'crfAuthoring.canvas.validation.descriptionRequired' },
  { source: 'Data type is required',                key: 'crfAuthoring.canvas.validation.dataTypeRequired' },
  { source: 'Response type is required',            key: 'crfAuthoring.canvas.validation.responseTypeRequired' },
  { source: 'Section title is required',            key: 'crfAuthoring.canvas.validation.sectionTitleRequired' },
  { source: 'Section label is required',            key: 'crfAuthoring.canvas.validation.sectionLabelRequired' },
  { source: 'At least one section is required',     key: 'crfAuthoring.canvas.validation.atLeastOneSection' },
  { source: 'Section must contain at least one item', key: 'crfAuthoring.canvas.validation.atLeastOneItem' },
  { source: 'Option text is required',              key: 'crfAuthoring.canvas.validation.optionTextRequired' },
  { source: 'Option value is required',             key: 'crfAuthoring.canvas.validation.optionValueRequired' },
  { source: 'Calculation formula must not be empty', key: 'crfAuthoring.canvas.validation.calculationFormulaRequired' },
  { source: 'Expression must not be empty',         key: 'crfAuthoring.canvas.validation.expressionRequired' },
  { source: 'versionName is required',              key: 'crfAuthoring.canvas.validation.versionNameRequired' },
  { source: 'versionName must be 255 characters or fewer',
    key: 'crfAuthoring.canvas.validation.versionNameTooLong' },
  { source: 'versionDescription is required',       key: 'crfAuthoring.canvas.validation.versionDescriptionRequired' },
  { source: "Items with data type FILE must declare a 'file' response set",
    key: 'crfAuthoring.canvas.validation.fileResponseSetRequired' },
  { source: 'Item name must contain only letters, digits and underscores',
    key: 'crfAuthoring.canvas.validation.itemNameInvalidChars' },
]

const PREFIX_RULES: PrefixRule[] = [
  { prefix: 'Data type must be one of',     key: 'crfAuthoring.canvas.validation.dataTypeUnsupported' },
  { prefix: 'Response type must be one of', key: 'crfAuthoring.canvas.validation.responseTypeUnsupported' },
  { prefix: 'Duplicate item name',          key: 'crfAuthoring.canvas.validation.duplicateItemName' },
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

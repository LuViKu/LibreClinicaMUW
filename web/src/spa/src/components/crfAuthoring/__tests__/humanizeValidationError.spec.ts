/**
 * humanizeValidationError spec.
 *
 * The rule tiers are order-sensitive: the options message is
 * parameterised on the response type, so it can only be matched by
 * suffix — but a naive {@code "Response type '"} prefix rule would
 * swallow the FILE pairing messages, which share that prefix and mean
 * something entirely different. These cases pin that ordering.
 */
import { describe, it, expect } from 'vitest'

import { humanizeValidationError } from '@/components/crfAuthoring/humanizeValidationError'

/** Identity translator — asserts on the KEY the lookup resolved to. */
const t = (key: string): string => key

describe('humanizeValidationError', () => {
  it('maps the parameterised options message by suffix', () => {
    for (const type of ['single-select', 'multi-select', 'radio', 'checkbox']) {
      expect(
        humanizeValidationError(`Response type '${type}' requires at least one option`, t),
      ).toBe('crfAuthoring.canvas.validation.optionsRequired')
    }
  })

  it('does not let the suffix rule swallow the FILE pairing messages', () => {
    expect(humanizeValidationError("Response type 'file' requires data type FILE", t)).toBe(
      'crfAuthoring.canvas.validation.fileResponseTypeRequired',
    )
    expect(humanizeValidationError("Data type FILE requires response type 'file'", t)).toBe(
      'crfAuthoring.canvas.validation.fileDataTypeRequired',
    )
  })

  it('still resolves the pre-existing exact and prefix rules', () => {
    expect(humanizeValidationError('Item name is required', t)).toBe(
      'crfAuthoring.canvas.validation.itemNameRequired',
    )
    expect(humanizeValidationError('Duplicate item name: AGE', t)).toBe(
      'crfAuthoring.canvas.validation.duplicateItemName',
    )
  })

  it('returns an unmapped message verbatim so it stays actionable', () => {
    expect(humanizeValidationError('Some brand new parser complaint', t)).toBe(
      'Some brand new parser complaint',
    )
  })

  it('returns an empty string for null/blank input', () => {
    expect(humanizeValidationError(null, t)).toBe('')
    expect(humanizeValidationError('   ', t)).toBe('')
  })
})

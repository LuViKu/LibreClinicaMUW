import { describe, expect, it, vi } from 'vitest'
import {
  buildItemIndex,
  evaluateShowWhen,
  isItemHiddenByRule,
  parseShowWhen,
  type ParsedShowWhen,
} from '../showWhen'
import type { CrfItem, CrfSchema, CrfValues } from '@/types/crf'

/**
 * Phase E.6 polish-runtime — show-when evaluator unit tests.
 *
 * <p>Covers every comparator × every data-type slice the spec calls out
 * plus the robustness rules (missing source value, dangling source
 * item, unparseable rule string).
 */

const STRING_SRC: CrfItem = { oid: 'I_GROUP', label: 'Group', dataType: 'string', required: false }
const INT_SRC: CrfItem = { oid: 'I_AGE', label: 'Age', dataType: 'integer', required: false }
const REAL_SRC: CrfItem = { oid: 'I_BCVA', label: 'BCVA', dataType: 'real', required: false }
const DATE_SRC: CrfItem = { oid: 'I_DOB', label: 'DOB', dataType: 'date', required: false }
const BL_SRC: CrfItem = { oid: 'I_CONSENT', label: 'Consent', dataType: 'boolean', required: false }
const SEL_SRC: CrfItem = {
  oid: 'I_SEX',
  label: 'Sex',
  dataType: 'select-one',
  required: false,
  options: [{ code: 'F', label: 'F' }, { code: 'M', label: 'M' }],
}
const MULTI_SRC: CrfItem = {
  oid: 'I_DX',
  label: 'Dx',
  dataType: 'select-multi',
  required: false,
  options: [{ code: 'A', label: 'A' }, { code: 'B', label: 'B' }, { code: 'C', label: 'C' }],
}

function rule(src: string, cmp: ParsedShowWhen['comparator'], lit: string): ParsedShowWhen {
  return { sourceItemOid: src, comparator: cmp, literal: lit }
}

function indexOf(...items: CrfItem[]): Map<string, CrfItem> {
  return new Map(items.map((it) => [it.oid, it]))
}

describe('parseShowWhen', () => {
  it('returns null for blank / null', () => {
    expect(parseShowWhen(null)).toBeNull()
    expect(parseShowWhen(undefined)).toBeNull()
    expect(parseShowWhen('')).toBeNull()
    expect(parseShowWhen('   ')).toBeNull()
  })

  it('parses the SPA JSON shape', () => {
    const out = parseShowWhen('{"sourceItemOid":"I_AGE","comparator":"==","literal":"42"}')
    expect(out).toEqual({ sourceItemOid: 'I_AGE', comparator: '==', literal: '42' })
  })

  it('normalises a legacy comparator on the JSON shape', () => {
    const out = parseShowWhen('{"sourceItemOid":"I_AGE","comparator":"gte","literal":"50"}')
    expect(out?.comparator).toBe('>=')
  })

  it('parses the legacy OpenClinica "item_X eq Y" string', () => {
    const out = parseShowWhen('item_I_SEX eq F')
    expect(out).toEqual({ sourceItemOid: 'I_SEX', comparator: '==', literal: 'F' })
  })

  it('accepts a bare OID without the legacy item_ prefix', () => {
    const out = parseShowWhen('I_SEX eq F')
    expect(out).toEqual({ sourceItemOid: 'I_SEX', comparator: '==', literal: 'F' })
  })

  it('handles each legacy comparator', () => {
    expect(parseShowWhen('I_AGE eq 1')?.comparator).toBe('==')
    expect(parseShowWhen('I_AGE ne 1')?.comparator).toBe('!=')
    expect(parseShowWhen('I_AGE gt 1')?.comparator).toBe('>')
    expect(parseShowWhen('I_AGE lt 1')?.comparator).toBe('<')
    expect(parseShowWhen('I_AGE gte 1')?.comparator).toBe('>=')
    expect(parseShowWhen('I_AGE lte 1')?.comparator).toBe('<=')
  })

  it('returns null for an unparseable string (fail open)', () => {
    expect(parseShowWhen('total nonsense here')).toBeNull()
    expect(parseShowWhen('{ malformed json')).toBeNull()
  })

  it('preserves spaces in legacy literals', () => {
    const out = parseShowWhen('I_NOTE eq Hello world')
    expect(out?.literal).toBe('Hello world')
  })
})

describe('evaluateShowWhen — equality comparators', () => {
  const idx = indexOf(STRING_SRC, INT_SRC, REAL_SRC, DATE_SRC, BL_SRC, SEL_SRC, MULTI_SRC)

  it('string == matches by string equality', () => {
    expect(evaluateShowWhen(rule('I_GROUP', '==', 'cohort-A'), { I_GROUP: 'cohort-A' }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_GROUP', '==', 'cohort-B'), { I_GROUP: 'cohort-A' }, idx)).toBe(false)
  })

  it('integer == coerces to number', () => {
    expect(evaluateShowWhen(rule('I_AGE', '==', '42'), { I_AGE: 42 }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_AGE', '==', '42'), { I_AGE: '42' }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_AGE', '==', '43'), { I_AGE: 42 }, idx)).toBe(false)
  })

  it('real == coerces to number', () => {
    expect(evaluateShowWhen(rule('I_BCVA', '==', '0.5'), { I_BCVA: 0.5 }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_BCVA', '==', '0.5'), { I_BCVA: 0.6 }, idx)).toBe(false)
  })

  it('date == compares ISO strings', () => {
    expect(evaluateShowWhen(rule('I_DOB', '==', '2020-01-15'), { I_DOB: '2020-01-15' }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_DOB', '==', '2020-01-15'), { I_DOB: '2020-01-16' }, idx)).toBe(false)
  })

  it('boolean == coerces both sides', () => {
    expect(evaluateShowWhen(rule('I_CONSENT', '==', 'true'), { I_CONSENT: true }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_CONSENT', '==', 'true'), { I_CONSENT: 'yes' }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_CONSENT', '==', 'false'), { I_CONSENT: 0 }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_CONSENT', '==', 'true'), { I_CONSENT: false }, idx)).toBe(false)
  })

  it('select-one == matches by code', () => {
    expect(evaluateShowWhen(rule('I_SEX', '==', 'F'), { I_SEX: 'F' }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_SEX', '==', 'M'), { I_SEX: 'F' }, idx)).toBe(false)
  })

  it('select-multi == matches if literal is in the array', () => {
    expect(evaluateShowWhen(rule('I_DX', '==', 'A'), { I_DX: ['A', 'B'] }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_DX', '==', 'C'), { I_DX: ['A', 'B'] }, idx)).toBe(false)
    // Comma-joined literal matches a full-set equality
    expect(evaluateShowWhen(rule('I_DX', '==', 'A,B'), { I_DX: ['B', 'A'] }, idx)).toBe(true)
  })

  it('!= is the inverse of ==', () => {
    expect(evaluateShowWhen(rule('I_GROUP', '!=', 'cohort-A'), { I_GROUP: 'cohort-A' }, idx)).toBe(false)
    expect(evaluateShowWhen(rule('I_GROUP', '!=', 'cohort-B'), { I_GROUP: 'cohort-A' }, idx)).toBe(true)
  })
})

describe('evaluateShowWhen — ordered comparators', () => {
  const idx = indexOf(INT_SRC, REAL_SRC)

  it('integer > / < / >= / <=', () => {
    const values: CrfValues = { I_AGE: 50 }
    expect(evaluateShowWhen(rule('I_AGE', '>', '40'), values, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_AGE', '>', '60'), values, idx)).toBe(false)
    expect(evaluateShowWhen(rule('I_AGE', '<', '60'), values, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_AGE', '<', '40'), values, idx)).toBe(false)
    expect(evaluateShowWhen(rule('I_AGE', '>=', '50'), values, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_AGE', '<=', '50'), values, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_AGE', '>=', '51'), values, idx)).toBe(false)
  })

  it('real > compares as numeric', () => {
    expect(evaluateShowWhen(rule('I_BCVA', '>', '0.4'), { I_BCVA: 0.5 }, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_BCVA', '>', '0.6'), { I_BCVA: 0.5 }, idx)).toBe(false)
  })

  it('returns false when either side is non-numeric', () => {
    expect(evaluateShowWhen(rule('I_AGE', '>', 'abc'), { I_AGE: 50 }, idx)).toBe(false)
  })
})

describe('evaluateShowWhen — robustness', () => {
  it('missing source value → equality matches an empty literal', () => {
    const idx = indexOf(STRING_SRC)
    expect(evaluateShowWhen(rule('I_GROUP', '==', ''), {}, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_GROUP', '==', 'A'), {}, idx)).toBe(false)
  })

  it('missing source value → inequality matches a non-empty literal', () => {
    const idx = indexOf(STRING_SRC)
    expect(evaluateShowWhen(rule('I_GROUP', '!=', 'A'), {}, idx)).toBe(true)
    expect(evaluateShowWhen(rule('I_GROUP', '!=', ''), {}, idx)).toBe(false)
  })

  it('missing source value → ordered comparators hide (resolve false)', () => {
    const idx = indexOf(INT_SRC)
    expect(evaluateShowWhen(rule('I_AGE', '>', '40'), {}, idx)).toBe(false)
    expect(evaluateShowWhen(rule('I_AGE', '<=', '40'), {}, idx)).toBe(false)
  })

  it('dangling source item → fail open (always-true) + warn once', () => {
    const idx = indexOf(STRING_SRC)
    const warned = new Set<string>()
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(evaluateShowWhen(rule('I_GONE', '==', 'X'), {}, idx, warned)).toBe(true)
    expect(warned.has('I_GONE')).toBe(true)
    // Second call: still true, no extra warn
    expect(evaluateShowWhen(rule('I_GONE', '==', 'X'), {}, idx, warned)).toBe(true)
    expect(consoleSpy).toHaveBeenCalledTimes(1)
    consoleSpy.mockRestore()
  })
})

describe('isItemHiddenByRule', () => {
  it('returns false when the item has no showWhen', () => {
    const idx = indexOf(STRING_SRC)
    const dep: CrfItem = { oid: 'I_DEP', label: 'Dep', dataType: 'string', required: false }
    expect(isItemHiddenByRule(dep, {}, idx)).toBe(false)
  })

  it('returns true when the rule resolves false', () => {
    const idx = indexOf(STRING_SRC)
    const dep: CrfItem = {
      oid: 'I_DEP',
      label: 'Dep',
      dataType: 'string',
      required: false,
      showWhen: '{"sourceItemOid":"I_GROUP","comparator":"==","literal":"cohort-A"}',
    }
    expect(isItemHiddenByRule(dep, { I_GROUP: 'cohort-B' }, idx)).toBe(true)
    expect(isItemHiddenByRule(dep, { I_GROUP: 'cohort-A' }, idx)).toBe(false)
  })

  it('accepts the legacy "item_X eq Y" rule string', () => {
    const idx = indexOf(SEL_SRC)
    const dep: CrfItem = {
      oid: 'I_DEP',
      label: 'Dep',
      dataType: 'string',
      required: false,
      showWhen: 'item_I_SEX eq F',
    }
    expect(isItemHiddenByRule(dep, { I_SEX: 'F' }, idx)).toBe(false)
    expect(isItemHiddenByRule(dep, { I_SEX: 'M' }, idx)).toBe(true)
  })

  it('returns false (always show) when showWhen is unparseable — fail open', () => {
    const idx = indexOf(STRING_SRC)
    const dep: CrfItem = {
      oid: 'I_DEP',
      label: 'Dep',
      dataType: 'string',
      required: false,
      showWhen: 'banana sundae',
    }
    expect(isItemHiddenByRule(dep, {}, idx)).toBe(false)
  })
})

describe('buildItemIndex', () => {
  it('returns a flat map keyed by OID across every section', () => {
    const schema: CrfSchema = {
      oid: 'F',
      name: 'F',
      version: 'v1',
      sections: [
        { oid: 'S1', title: 'S1', items: [STRING_SRC, INT_SRC] },
        { oid: 'S2', title: 'S2', items: [SEL_SRC] },
      ],
    }
    const idx = buildItemIndex(schema)
    expect(idx.size).toBe(3)
    expect(idx.get('I_GROUP')?.dataType).toBe('string')
    expect(idx.get('I_AGE')?.dataType).toBe('integer')
    expect(idx.get('I_SEX')?.dataType).toBe('select-one')
  })

  it('returns an empty index for a null schema', () => {
    expect(buildItemIndex(null).size).toBe(0)
  })
})


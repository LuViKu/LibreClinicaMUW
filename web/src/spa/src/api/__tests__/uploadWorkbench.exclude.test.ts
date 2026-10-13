/** DR-041 amendment (b): the commit carries `excludeSeries` as one comma-separated field. */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { commitFile } from '../uploadWorkbench'

class FakeXhr {
  static last: FakeXhr | null = null
  body: FormData | null = null
  status = 201
  responseText = JSON.stringify({ items: [], excluded: ['s2'] })
  upload: Record<string, unknown> = {}
  withCredentials = false
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor() { FakeXhr.last = this }
  open() { /* not needed */ }
  setRequestHeader() { /* not needed */ }
  send(body: FormData) {
    this.body = body
    queueMicrotask(() => this.onload?.())
  }
}

beforeEach(() => vi.stubGlobal('XMLHttpRequest', FakeXhr))
afterEach(() => vi.unstubAllGlobals())

const file = new File(['x'], 'a.E2E')

describe('commitFile excludeSeries', () => {
  it('sends the keys comma-separated', async () => {
    const res = await commitFile('staff', { file, split: true, excludeSeries: ['s2', 's5', 's11'] })
    expect(FakeXhr.last!.body!.get('excludeSeries')).toBe('s2,s5,s11')
    expect(FakeXhr.last!.body!.get('split')).toBe('true')
    expect(res.excluded).toEqual(['s2'])
  })

  it('sends no field for none, and never outside split mode', async () => {
    await commitFile('staff', { file, split: true, excludeSeries: [] })
    expect(FakeXhr.last!.body!.has('excludeSeries')).toBe(false)
    await commitFile('staff', { file, excludeSeries: ['s1'] })
    expect(FakeXhr.last!.body!.has('excludeSeries')).toBe(false)
  })
})

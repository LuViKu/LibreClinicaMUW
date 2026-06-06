import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * Phase E.6 restore-quickwins — coverage for the two new rules
 * store actions: {@code dryRunRuleSet} (POST /dry-run) and
 * {@code exportRulesXml} (anchor-click download trigger).
 *
 * The dry-run path mirrors the discriminated-union shape the
 * RulesView branches on; export verifies the URL the browser is
 * pointed at, including comma-joined ruleSetRuleIds.
 *
 * REVIEWER: net-new file (playbook reviewer flag — mark NEW).
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
  }
})

// eslint-disable-next-line import/first
import { apiPost, ApiError, ApiNetworkError } from '@/api/client'
// eslint-disable-next-line import/first
import { useRulesStore } from '../rules'

const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>

describe('useRulesStore.dryRunRuleSet', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiPostMock.mockReset()
  })

  it('returns ok:true with the response body on 200', async () => {
    const fixture = {
      ruleSetId: 42,
      target: 'I_LBTEST',
      results: [
        {
          ruleName: 'value-in-range',
          ruleOid: 'OC_R_001',
          expression: 'I_LBTEST > 0',
          executeOn: 'data-entry',
          actionType: 'SHOW',
          actionSummary: 'show I_LBTEST_2',
          subjects: ['M-001', 'M-002'],
        },
      ],
    }
    apiPostMock.mockResolvedValueOnce(fixture)

    const store = useRulesStore()
    const result = await store.dryRunRuleSet(42)

    expect(apiPostMock).toHaveBeenCalledWith('/pages/api/v1/rule-sets/42/dry-run', {})
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.response.ruleSetId).toBe(42)
      expect(result.response.results).toHaveLength(1)
      expect(result.response.results[0]?.subjects).toEqual(['M-001', 'M-002'])
    }
  })

  it('returns ok:true with empty results when the set would not fire', async () => {
    apiPostMock.mockResolvedValueOnce({ ruleSetId: 7, target: null, results: [] })

    const store = useRulesStore()
    const result = await store.dryRunRuleSet(7)

    expect(result.ok).toBe(true)
    if (result.ok) expect(result.response.results).toEqual([])
  })

  it('returns ok:false with the backend message on 4xx', async () => {
    apiPostMock.mockRejectedValueOnce(
      new ApiError(400, 'HTTP 400', { message: 'rule expression invalid' }),
    )

    const store = useRulesStore()
    const result = await store.dryRunRuleSet(99)

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.message).toBe('rule expression invalid')
  })

  it('returns ok:false with translated message on network error', async () => {
    apiPostMock.mockRejectedValueOnce(new ApiNetworkError('connection refused', null))

    const store = useRulesStore()
    const result = await store.dryRunRuleSet(99)

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.message).toMatch(/Backend nicht erreichbar/)
    }
  })

  it('throws on 401/403 so the router auth guard can pick it up', async () => {
    apiPostMock.mockRejectedValueOnce(new ApiError(403, 'HTTP 403', { message: 'forbidden' }))

    const store = useRulesStore()
    await expect(store.dryRunRuleSet(99)).rejects.toThrow('forbidden')
  })
})

describe('useRulesStore.exportRulesXml', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('returns false when the id list is empty', () => {
    const store = useRulesStore()
    expect(store.exportRulesXml([])).toBe(false)
  })

  it('clicks an anchor pointed at the export URL with comma-joined ids', () => {
    const store = useRulesStore()
    const createSpy = vi.spyOn(document, 'createElement')
    const appendSpy = vi.spyOn(document.body, 'appendChild')

    const triggered = store.exportRulesXml([42, 17, 9])

    expect(triggered).toBe(true)
    expect(createSpy).toHaveBeenCalledWith('a')
    expect(appendSpy).toHaveBeenCalled()
    const anchor = appendSpy.mock.calls[0]?.[0] as HTMLAnchorElement
    expect(anchor.href).toContain('/pages/api/v1/rule-sets/export')
    // encodeURIComponent escapes the commas — verify we get the
    // canonical %2C-joined form that survives proxy/CDN rewrites.
    expect(anchor.href).toContain('ruleSetRuleIds=42%2C17%2C9')
    expect(anchor.rel).toBe('noopener')
    // `download=""` hints the browser to honour the
    // Content-Disposition filename instead of navigating away.
    expect(anchor.download).toBe('')
  })

  it('encodes commas as %2C so the URL survives CDN rewrites', () => {
    const store = useRulesStore()
    const appendSpy = vi.spyOn(document.body, 'appendChild')

    store.exportRulesXml([1, 2])

    const anchor = appendSpy.mock.calls[0]?.[0] as HTMLAnchorElement
    expect(anchor.href).toMatch(/ruleSetRuleIds=1%2C2$/)
  })
})

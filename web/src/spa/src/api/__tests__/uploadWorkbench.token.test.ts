/**
 * The public upload client and the uploader token.
 *
 * In multi-location mode the server wants every request of the public
 * upload page to name its location with `X-MUW-Uploader-Token`. Pinned here:
 * the header is on EVERY request of the public mode and on none without a
 * token; the staff mode and every other API client never carry it, and the
 * source says so (one file builds it); a 401 about the token becomes an error
 * of its own, reported to the page, while any other error stays what it was;
 * and nothing the client throws holds the token.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { i18n } from '@/i18n'
import { stubWebStorage, type MemoryStorage } from '@/test/memoryStorage'
import { apiGet, apiPost } from '@/api/client'
import { apiUpload } from '@/api/upload'
import {
  UploadApiError,
  commitFile,
  listPatientEventsPublic,
  listVisitsForDay,
  preflight,
  resolveRows,
  searchPatientsPublic,
  undoItem,
  undoJob,
  type UploadMode,
} from '../uploadWorkbench'
import {
  UPLOADER_TOKEN_HEADER,
  UploadTokenError,
  clearUploaderToken,
  reportTokenProblem,
  setUploaderToken,
  tokenProblem,
} from '@/lib/uploaderToken'

const BODY = 'abcXYZ019-_'.repeat(5).slice(0, 43)
const TOKEN = `mwu_${BODY}`

/* ------------------------------ the network ------------------------------ */

type Reply = { status: number; body: unknown }
let reply: Reply
const fetchMock = vi.fn()

function jsonResponse(r: Reply): Response {
  return new Response(r.status === 204 ? null : JSON.stringify(r.body), {
    status: r.status,
    headers: { 'content-type': 'application/json' },
  })
}

class FakeXhr {
  static instances: FakeXhr[] = []
  method = ''
  url = ''
  headers: Record<string, string> = {}
  withCredentials = false
  status = 0
  responseText = ''
  upload: { onprogress?: unknown; onloadend?: unknown } = {}
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor() { FakeXhr.instances.push(this) }
  open(method: string, url: string) { this.method = method; this.url = url }
  setRequestHeader(name: string, value: string) { this.headers[name] = value }
  send() {
    queueMicrotask(() => {
      this.status = reply.status
      this.responseText = JSON.stringify(reply.body)
      this.onload?.()
    })
  }
}

const file = () => new File([new Uint8Array([1, 2, 3])], 'fundus.png', { type: 'image/png' })

/** Every public-mode call, with what it needs to answer successfully. */
const PUBLIC_CALLS: Array<[string, () => Promise<unknown>]> = [
  ['resolveRows', () => resolveRows('public', [{ patientId: 'M-001', scanDate: null, laterality: null }])],
  ['preflight', () => preflight('public', 'a'.repeat(64), 1)],
  ['searchPatientsPublic', () => searchPatientsPublic('M-0')],
  ['listPatientEventsPublic', () => listPatientEventsPublic(7)],
  ['listVisitsForDay', () => listVisitsForDay('public', '2026-10-12')],
  ['undoItem', () => undoItem('public', 11)],
  ['undoJob', () => undoJob('public', 12)],
  ['commitFile', () => commitFile('public', { file: file(), patientId: 'M-001' })],
]

/** The same calls in the staff mode. */
const STAFF_CALLS: Array<[string, () => Promise<unknown>]> = [
  ['resolveRows', () => resolveRows('staff', [{ patientId: 'M-001', scanDate: null, laterality: null }])],
  ['preflight', () => preflight('staff', 'a'.repeat(64), null)],
  ['listVisitsForDay', () => listVisitsForDay('staff', '2026-10-12')],
  ['undoItem', () => undoItem('staff', 11)],
  ['undoJob', () => undoJob('staff', 12)],
  ['commitFile', () => commitFile('staff', { file: file(), patientId: 'M-001' })],
]

/** The token header of every request made since the mocks were reset, fetch and XHR alike. */
function sentTokenHeaders(): Array<string | undefined> {
  const fromFetch = fetchMock.mock.calls.map(([, init]) => (init?.headers ?? {})[UPLOADER_TOKEN_HEADER] as string | undefined)
  const fromXhr = FakeXhr.instances.map((x) => x.headers[UPLOADER_TOKEN_HEADER])
  return [...fromFetch, ...fromXhr]
}

function requestCount(): number {
  return fetchMock.mock.calls.length + FakeXhr.instances.length
}

let session: MemoryStorage

beforeEach(() => {
  ;({ session } = stubWebStorage())
  clearUploaderToken()
  reportTokenProblem(null)
  i18n.global.locale.value = 'de-AT'
  fetchMock.mockReset()
  FakeXhr.instances = []
  // Every call below succeeds by default: an empty answer of the shape each one reads.
  reply = { status: 200, body: { scans: [], exists: false, ingestItemId: null, jobId: null, subjects: [], visits: [], kind: 'image' } }
  fetchMock.mockImplementation(async () => jsonResponse(reply))
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/* ------------------------------ the header ------------------------------ */

describe('with a token', () => {
  beforeEach(() => { setUploaderToken(TOKEN) })

  it.each(PUBLIC_CALLS)('%s carries it on its request', async (_name, call) => {
    await call()
    expect(requestCount()).toBe(1)
    expect(sentTokenHeaders()).toEqual([TOKEN])
  })

  it('carries it on every request, whatever the number of calls', async () => {
    for (const [, call] of PUBLIC_CALLS) await call()
    expect(requestCount()).toBe(PUBLIC_CALLS.length)
    expect(sentTokenHeaders()).toEqual(Array(PUBLIC_CALLS.length).fill(TOKEN))
  })

  it('sends the other headers a call has as before, and never credentials', async () => {
    await resolveRows('public', [])
    const [, init] = fetchMock.mock.calls[0]
    expect(init.headers).toMatchObject({ Accept: 'application/json', 'Content-Type': 'application/json' })
    expect(init.credentials).toBe('omit')
  })

  it('sets it before the upload is sent', async () => {
    const p = commitFile('public', { file: file() })
    expect(FakeXhr.instances[0].headers[UPLOADER_TOKEN_HEADER]).toBe(TOKEN)
    expect(FakeXhr.instances[0].headers.Accept).toBe('application/json')
    expect(FakeXhr.instances[0].withCredentials).toBe(false)
    await p
  })

  it('does not put it in a URL, a query string or a body', async () => {
    await preflight('public', 'a'.repeat(64), 1)
    await searchPatientsPublic('M-0')
    await resolveRows('public', [{ patientId: 'M-001', scanDate: null, laterality: null }])
    await commitFile('public', { file: file(), patientId: 'M-001' })
    for (const [url, init] of fetchMock.mock.calls) {
      expect(String(url)).not.toContain(BODY)
      expect(String(init?.body ?? '')).not.toContain(BODY)
    }
    for (const x of FakeXhr.instances) expect(x.url).not.toContain(BODY)
  })
})

describe('without a token', () => {
  it.each(PUBLIC_CALLS)('%s sends the header on none of its requests', async (_name, call) => {
    await call()
    expect(requestCount()).toBe(1)
    expect(sentTokenHeaders()).toEqual([undefined])
  })

  it('does not send an empty header either', async () => {
    await resolveRows('public', [])
    expect(Object.keys(fetchMock.mock.calls[0][1].headers).map((h) => h.toLowerCase())).not.toContain('x-muw-uploader-token')
  })

  it('does not take anything for a token that is not one', async () => {
    session.setItem('muw.uploaderToken', 'not-a-token')
    await resolveRows('public', [])
    expect(sentTokenHeaders()).toEqual([undefined])
  })
})

describe('the staff mode, with a token in the session', () => {
  beforeEach(() => { setUploaderToken(TOKEN) })

  it.each(STAFF_CALLS)('%s never carries it', async (_name, call) => {
    await call()
    expect(requestCount()).toBeGreaterThan(0)
    expect(sentTokenHeaders().every((h) => h === undefined)).toBe(true)
  })

  it('asks with the session, as before', async () => {
    await resolveRows('staff', [])
    expect(fetchMock.mock.calls[0][1].credentials).toBe('include')
    expect(fetchMock.mock.calls[0][0]).toBe('/LibreClinica/pages/api/v1/ingest/upload/resolve')
  })
})

describe('no other API client sends it', () => {
  beforeEach(() => { setUploaderToken(TOKEN) })

  it('not the JSON client, whichever way it is called', async () => {
    await apiGet('/pages/api/v1/uploaders')
    await apiPost('/pages/api/v1/uploaders', {})
    expect(sentTokenHeaders()).toEqual([undefined, undefined])
  })

  it('not the staff multipart upload', async () => {
    await apiUpload('/pages/api/v1/crf/upload', file())
    expect(sentTokenHeaders()).toEqual([undefined])
  })

  it('and nothing in the source but the token module and the one client names the header', () => {
    const sources = import.meta.glob('/src/**/*.{ts,vue}', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
    const names = Object.entries(sources)
      .filter(([path]) => !path.includes('/__tests__/') && !path.endsWith('.test.ts') && !path.endsWith('.spec.ts'))
      .filter(([, text]) => /x-muw-uploader-token|UPLOADER_TOKEN_HEADER/i.test(text))
      .map(([path]) => path)
      .sort()
    expect(names).toEqual(['/src/api/uploadWorkbench.ts', '/src/lib/uploaderToken.ts'])
  })

  it('and the one client builds it in one place for the fetches and the upload', () => {
    const text = (import.meta.glob('/src/api/uploadWorkbench.ts', { query: '?raw', import: 'default', eager: true }) as Record<string, string>)['/src/api/uploadWorkbench.ts']
    // One fetch, one XHR, and the header decided by one function.
    expect(text.match(/\bfetch\(/g)).toHaveLength(1)
    expect(text.match(/new XMLHttpRequest\(/g)).toHaveLength(1)
    expect(text.match(/UPLOADER_TOKEN_HEADER\]/g)).toHaveLength(1)
  })
})

/* ------------------------------ the 401 ------------------------------ */

const REQUIRED: Reply = { status: 401, body: { message: 'uploader token required' } }
const REJECTED: Reply = { status: 401, body: { message: 'uploader token rejected' } }

describe('a 401 about the token', () => {
  it.each(PUBLIC_CALLS)('is told apart from other errors by %s', async (_name, call) => {
    reply = REQUIRED
    await expect(call()).rejects.toBeInstanceOf(UploadTokenError)
  })

  it('says "required" for a request that had none, in German', async () => {
    reply = REQUIRED
    const err = await resolveRows('public', []).catch((e: unknown) => e) as UploadTokenError
    expect(err).toBeInstanceOf(UploadTokenError)
    expect(err.reason).toBe('required')
    expect(err.status).toBe(401)
    expect(err.message).toBe('Diese Seite braucht einen Uploader-Token. Bitte den Link verwenden, den Ihr Standort-Admin Ihnen gegeben hat.')
    expect(tokenProblem.value).toBe('required')
  })

  it('says "rejected" for a token the server refuses, in German', async () => {
    setUploaderToken(TOKEN)
    reply = REJECTED
    const err = await resolveRows('public', []).catch((e: unknown) => e) as UploadTokenError
    expect(err.reason).toBe('rejected')
    expect(err.message).toBe('Der Token wurde abgelehnt oder gesperrt. Bitte beim Standort-Admin einen neuen Link anfordern.')
    expect(tokenProblem.value).toBe('rejected')
  })

  it('speaks English where the page does', async () => {
    i18n.global.locale.value = 'en'
    reply = REJECTED
    const err = await preflight('public', 'a'.repeat(64)).catch((e: unknown) => e) as UploadTokenError
    expect(err.message).toBe('The token was rejected or revoked. Please ask your location admin for a new link.')
  })

  it('is reported to the page by the upload as well as by the fetches', async () => {
    reply = REJECTED
    await expect(commitFile('public', { file: file() })).rejects.toBeInstanceOf(UploadTokenError)
    expect(tokenProblem.value).toBe('rejected')
  })

  it('never makes a second request on its own', async () => {
    reply = REQUIRED
    await resolveRows('public', []).catch(() => {})
    await commitFile('public', { file: file() }).catch(() => {})
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(FakeXhr.instances).toHaveLength(1)
  })

  it('does not hold the token, in the error or anywhere it can be read from it', async () => {
    setUploaderToken(TOKEN)
    reply = REJECTED
    const err = await resolveRows('public', []).catch((e: unknown) => e) as UploadTokenError
    expect(`${err.message} ${err.name} ${JSON.stringify(err.body)} ${err.stack ?? ''} ${String(err)}`).not.toContain(BODY)
    expect(JSON.stringify(err)).not.toContain(BODY)
  })

  it('is forgotten when a later answer is no complaint', async () => {
    reply = REQUIRED
    await resolveRows('public', []).catch(() => {})
    expect(tokenProblem.value).toBe('required')
    reply = { status: 200, body: { scans: [] } }
    await resolveRows('public', [])
    expect(tokenProblem.value).toBeNull()
  })

  it('is forgotten when an upload goes through', async () => {
    reply = REQUIRED
    await commitFile('public', { file: file() }).catch(() => {})
    reply = { status: 200, body: { ingestItemId: 1, jobId: null, kind: 'image', format: 'png', status: 'UNBOUND' } }
    await commitFile('public', { file: file() })
    expect(tokenProblem.value).toBeNull()
  })
})

describe('any other error', () => {
  it('stays an error of the upload client with the server’s words, and says nothing about the token', async () => {
    reply = { status: 401, body: { message: 'Unauthorized' } }
    const err = await resolveRows('public', []).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(UploadApiError)
    expect(err).not.toBeInstanceOf(UploadTokenError)
    expect((err as UploadApiError).status).toBe(401)
    expect((err as UploadApiError).message).toBe('Unauthorized')
    expect(tokenProblem.value).toBeNull()
  })

  it.each([
    [403, 'uploader token required'],
    [409, 'uploader token required'],
    [429, 'Too many requests'],
    [500, 'Server Error'],
  ])('a %i is not a complaint about the token', async (status, message) => {
    reply = { status, body: { message } }
    const err = await commitFile('public', { file: file() }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(UploadApiError)
    expect((err as UploadApiError).status).toBe(status)
    expect(tokenProblem.value).toBeNull()
  })

  it('a 404 of the visit list still means "not available", not an error', async () => {
    reply = { status: 404, body: { message: 'not found' } }
    await expect(listVisitsForDay('public')).resolves.toBeNull()
    expect(tokenProblem.value).toBeNull()
  })

  it('in the staff mode, even the token’s words are nothing about a token', async () => {
    for (const mode of ['staff'] as UploadMode[]) {
      reply = REQUIRED
      const err = await resolveRows(mode, []).catch((e: unknown) => e)
      expect(err).toBeInstanceOf(UploadApiError)
      expect(err).not.toBeInstanceOf(UploadTokenError)
    }
    expect(tokenProblem.value).toBeNull()
  })
})

/**
 * The uploader token of the public upload page.
 *
 * Once a second location exists the server answers the account-less upload
 * page's requests (`/pages/api/v1/public/upload/**`) with 401 unless they
 * carry an uploader token, which identifies the location the files belong to.
 * The Standort-Admin hands the person at the acquisition PC a link,
 * `/upload#token=mwu_…`, and this module is everything the page knows about
 * it. The rules, each one a leak closed:
 *
 *  - The token travels in the URL **fragment** only. A fragment is never
 *    sent to a server, so it is in no nginx or Tomcat access log and in no
 *    Referer. A `?token=` query parameter is not read, ever: the query is
 *    logged on its way in.
 *  - It is accepted only if it has the shape of one (`mwu_` and 43 base64url
 *    characters), whatever else the fragment says.
 *  - It is kept in `sessionStorage` and nowhere else: not `localStorage`, not
 *    a Pinia store (those are serialised by devtools and reporters), not a
 *    reactive ref. The reactive parts of this module say *that* there is a
 *    token or a problem with it, never *what* it is. If the browser denies
 *    `sessionStorage`, the token lives in a module variable for the life of
 *    the page.
 *  - It is sent as `X-MUW-Uploader-Token` by the public upload client
 *    (`api/uploadWorkbench`), in the one place that client builds requests,
 *    and by nothing else.
 *  - It is never logged and never part of an error: the error classes here
 *    carry the server's answer, which does not contain it.
 *
 * The page then removes the fragment from the address bar and from the
 * history entry (`composables/useUploaderTokenIntake`).
 */
import { ref } from 'vue'

import { i18n } from '@/i18n'

export const UPLOADER_TOKEN_HEADER = 'X-MUW-Uploader-Token'

const STORAGE_KEY = 'muw.uploaderToken'
const TOKEN_PATTERN = /^mwu_[A-Za-z0-9_-]{43}$/

/** Has the shape of an uploader token: `mwu_` and 43 base64url characters. */
export function isUploaderToken(value: unknown): value is string {
  return typeof value === 'string' && TOKEN_PATTERN.test(value)
}

/* ------------------------------ The fragment ------------------------------ */

/** What a URL fragment says about a token. */
export interface HashToken {
  /** The fragment names a `token` at all, valid or not: it is to be removed from the address bar. */
  present: boolean
  /** The token, when it has the right shape. */
  token: string | null
}

/**
 * Reads `#token=mwu_…` (alone or among other `&`-separated parts). The query
 * string is never an input here: only the fragment is passed in.
 */
export function tokenFromHash(hash: string): HashToken {
  const raw = hash.startsWith('#') ? hash.slice(1) : hash
  for (const part of raw.split('&')) {
    const eq = part.indexOf('=')
    if ((eq < 0 ? part : part.slice(0, eq)) !== 'token') continue
    const value = eq < 0 ? '' : part.slice(eq + 1)
    return { present: true, token: isUploaderToken(value) ? value : null }
  }
  return { present: false, token: null }
}

/** A fragment that carries a token (valid or not), for the places that must not treat it as an anchor. */
export function isTokenHash(hash: string | null | undefined): boolean {
  return typeof hash === 'string' && tokenFromHash(hash).present
}

/* ------------------------------ The token ------------------------------ */

/** Only when the browser denies sessionStorage. Never persisted, never reactive. */
let memoryToken: string | null = null

/** Counts accepted tokens, so that the page can react to a new one without ever holding it. */
export const tokenVersion = ref(0)

/** The token of this browser session, or null. */
export function getUploaderToken(): string | null {
  try {
    const stored = window.sessionStorage.getItem(STORAGE_KEY)
    if (isUploaderToken(stored)) return stored
  } catch {
    /* sessionStorage denied: fall back to the page's memory */
  }
  return isUploaderToken(memoryToken) ? memoryToken : null
}

/** Keeps a valid token for this browser session (sessionStorage only) and forgets any problem reported for the old one. */
export function setUploaderToken(token: string): boolean {
  if (!isUploaderToken(token)) return false
  memoryToken = null
  try {
    window.sessionStorage.setItem(STORAGE_KEY, token)
  } catch {
    memoryToken = token
  }
  tokenProblem.value = null
  tokenVersion.value++
  return true
}

export function clearUploaderToken(): void {
  memoryToken = null
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    /* nothing stored that could be removed */
  }
}

/* ------------------------------ What went wrong ------------------------------ */

/**
 * `required`: the page has no token and the server wants one. `rejected`: the
 * server does not accept the one it has (unknown, revoked, location removed).
 * `invalid-link`: the link carried something that is not a token at all.
 */
export type TokenProblem = 'required' | 'rejected' | 'invalid-link'

/** The page-level problem with the token, if any: what the banner shows. Carries a kind, never a token. */
export const tokenProblem = ref<TokenProblem | null>(null)

export function reportTokenProblem(problem: TokenProblem | null): void {
  tokenProblem.value = problem
}

/** What the server's 401 says about the token: its two fixed answers, nothing else. */
export function tokenReasonOf(status: number, body: unknown): 'required' | 'rejected' | null {
  if (status !== 401) return null
  const message = body && typeof body === 'object' ? (body as { message?: unknown }).message : null
  if (typeof message !== 'string') return null
  switch (message.trim().toLowerCase()) {
    case 'uploader token required': return 'required'
    case 'uploader token rejected': return 'rejected'
    default: return null
  }
}

/** The page's own words for a server refusal of the token. German is the working language. */
export function tokenMessage(problem: TokenProblem): string {
  const key = problem === 'invalid-link' ? 'invalidLink' : problem
  return i18n.global.t(`uploadPortal.token.${key}`)
}

/**
 * The upload client's error for a 401 about the token. It says what the page
 * would say (so any caller that shows `message` shows a sentence a person can
 * act on) and is told apart from other errors by its type: the workbench keeps
 * the file, stops the attempt and leaves the retrying to a person with a new
 * link. `body` is the server's answer, which does not contain the token.
 */
export class UploadTokenError extends Error {
  readonly status = 401
  readonly reason: 'required' | 'rejected'
  readonly body: unknown

  constructor(reason: 'required' | 'rejected', body: unknown = null) {
    super(tokenMessage(reason))
    this.name = 'UploadTokenError'
    this.reason = reason
    this.body = body
  }
}

/* ------------------------------ The link ------------------------------ */

/**
 * The link to hand to the person at the acquisition PC:
 * `<origin><base>upload#token=<token>`. `base` is the app's base path
 * (`import.meta.env.BASE_URL`), with or without its trailing slash.
 */
export function uploadPageLink(origin: string, base: string, token: string): string {
  const root = base.endsWith('/') ? base : `${base}/`
  return `${origin.replace(/\/+$/, '')}${root}upload#token=${token}`
}

/**
 * Phase E.4 — SPA HTTP client.
 *
 * Thin fetch wrapper for the SPA-to-backend channel. The SPA calls
 * `/pages/api/v1/**` adapters (B-category per
 * [api-surface.md](../../../../../docs/development/modernization/phase-e/api-surface.md)).
 * In dev the Vite proxy forwards those paths to the running Spring
 * Boot WAR at http://127.0.0.1:8080; in prod the SPA is co-served
 * from the same origin so the proxy is a no-op.
 *
 * Auth is the legacy `JSESSIONID` cookie produced by the existing
 * Spring Security filter chain. `credentials: 'include'` plus the
 * Vite `changeOrigin: true` proxy is enough; CSRF is disabled in
 * `SecurityConfig` so no token plumbing is required (DR forthcoming).
 *
 * Error model — every non-2xx response throws `ApiError`. The SPA
 * catches it at the store layer and either:
 *   - 401 → auth store sets `state = 'anonymous'`, router pushes /login
 *   - 403 → role-mismatch toast
 *   - 4xx → field/validation toast with `body.message`
 *   - 5xx → generic "Backend nicht erreichbar" toast
 *
 * The wrapper deliberately does NOT depend on the auth store /
 * router (would create a circular import); stores wire the side
 * effects themselves.
 */

export class ApiError extends Error {
  readonly status: number
  readonly body: unknown
  /**
   * Phase E hardening A4 — request-correlation id.
   *
   * Populated from the server's echoed `X-Request-Id` response header
   * when present, or the client-generated UUIDv4 used for the request
   * when the server omitted the echo. A5's `GlobalErrorToast` surfaces
   * this as "Fehler-ID: 7f3e..." so operators can quote it in bug
   * reports — the same id appears in server logs (via logback's
   * `[%X{reqId:-}]` MDC field) and the A1 failure-audit row.
   */
  readonly reqId: string

  constructor(status: number, message: string, body: unknown = null, reqId: string = '') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.reqId = reqId
  }

  get isUnauthorized(): boolean { return this.status === 401 }
  get isForbidden(): boolean { return this.status === 403 }
  get isNotFound(): boolean { return this.status === 404 }
  get isClientError(): boolean { return this.status >= 400 && this.status < 500 }
  get isServerError(): boolean { return this.status >= 500 }
}

/**
 * Network-failure error — DNS, connection refused, CORS preflight,
 * abort. Distinct from `ApiError` because the response never arrived;
 * the SPA may want to fall back to cached / mock data instead of
 * surfacing a toast.
 */
export class ApiNetworkError extends Error {
  readonly cause: unknown
  /**
   * Phase E hardening A4 — client-generated UUIDv4 used for the failed
   * request. The server never returned a response so we cannot read its
   * echoed header; the id we sent is still useful for cross-referencing
   * Tomcat access logs (if the request reached the container at all) and
   * for the operator's bug report.
   */
  readonly reqId: string
  constructor(message: string, cause: unknown, reqId: string = '') {
    super(message)
    this.name = 'ApiNetworkError'
    this.cause = cause
    this.reqId = reqId
  }
}

interface RequestOptions {
  /** AbortSignal — typically `useAbortableRequest()` from the store. */
  signal?: AbortSignal
  /** Extra headers to merge (rarely needed). */
  headers?: Record<string, string>
}

/**
 * Tomcat context path the WAR is deployed at. Callers pass logical
 * paths starting with `/pages/...` or `/MainMenu`; this prefix is
 * applied transparently so call sites don't repeat it. In dev the
 * Vite proxy forwards `^/LibreClinica/(pages|MainMenu|...)` to
 * `http://127.0.0.1:8080`; in prod the SPA is co-served from the
 * same origin so the prefix is a same-origin path.
 *
 * If the WAR is ever renamed (e.g. to `/`), change this single
 * constant + the Vite proxy regex.
 */
const CONTEXT_PATH = '/LibreClinica'

/**
 * Phase E hardening A4 — request-correlation header name. Backend's
 * `RequestIdFilter` reads + echoes the same name. Mirrored in the
 * Vitest spec so a rename only needs to touch one constant.
 */
const REQUEST_ID_HEADER = 'X-Request-Id'

/**
 * Generate a UUIDv4 for the outgoing request. `crypto.randomUUID()` is
 * the canonical Vite+modern-browser path; the explicit fallback covers
 * the jsdom environment that Vitest uses, which historically lacks
 * `crypto.randomUUID` on Node < 19 and on older jsdom builds. The
 * fallback is RFC4122-shaped UUIDv4 (15 random bytes + a fixed version
 * nibble) — good enough for trace ids, not for crypto secrets.
 */
function generateRequestId(): string {
  const c: { randomUUID?: () => string } | undefined =
    typeof globalThis !== 'undefined'
      ? (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
      : undefined
  if (c && typeof c.randomUUID === 'function') {
    return c.randomUUID()
  }
  // Fallback — Math.random is fine for correlation ids, never for auth.
  const bytes = new Array(16)
  for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256)
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10xx
  const hex = bytes.map((b: number) => b.toString(16).padStart(2, '0')).join('')
  return (
    hex.substring(0, 8) + '-' +
    hex.substring(8, 12) + '-' +
    hex.substring(12, 16) + '-' +
    hex.substring(16, 20) + '-' +
    hex.substring(20, 32)
  )
}

async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH',
  path: string,
  body: unknown,
  opts: RequestOptions = {},
): Promise<T> {
  const reqId = generateRequestId()
  const headers: Record<string, string> = {
    Accept: 'application/json',
    [REQUEST_ID_HEADER]: reqId,
    ...opts.headers,
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const url = path.startsWith(CONTEXT_PATH) ? path : `${CONTEXT_PATH}${path}`

  const fetchInit: RequestInit = {
    method,
    credentials: 'include',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: opts.signal,
  }

  let response: Response
  try {
    response = await fetch(url, fetchInit)
  } catch (cause) {
    // Phase E hardening B3 — single retry on transient network failure
    // for idempotent GETs only. Mutations (POST/PUT/DELETE/PATCH) must
    // NEVER retry: a flaky network could otherwise duplicate a clinical
    // save or leave the server in a partially-written state. ApiError
    // (server reachable, error response) deliberately stays outside the
    // retry path — only `fetch` itself throwing triggers the backoff.
    if (method === 'GET') {
      await new Promise((r) => setTimeout(r, 500))
      try {
        response = await fetch(url, fetchInit)
      } catch (retryCause) {
        throw new ApiNetworkError(`Network failure calling ${method} ${url}`, retryCause, reqId)
      }
    } else {
      // No response arrived — surface the client-generated id so the
      // operator can still cross-reference Tomcat access logs.
      throw new ApiNetworkError(`Network failure calling ${method} ${url}`, cause, reqId)
    }
  }

  // Prefer the server's echoed id; fall back to the one we sent if the
  // backend filter ever fails to populate the response header (e.g. an
  // error response from a layer that bypasses RequestIdFilter — A2 is
  // the explicit fix for that case but we want belt+suspenders here).
  const echoedReqId = response.headers.get(REQUEST_ID_HEADER) ?? reqId

  if (response.status === 204) return undefined as T

  const contentType = response.headers.get('content-type') ?? ''
  const isJson = contentType.includes('application/json')
  const parsed = isJson ? await response.json().catch(() => null) : await response.text().catch(() => null)

  if (!response.ok) {
    const message =
      (isJson && parsed && typeof parsed === 'object' && 'message' in parsed
        ? String((parsed as { message: unknown }).message)
        : undefined) ?? `${method} ${url} → ${response.status}`
    throw new ApiError(response.status, message, parsed, echoedReqId)
  }

  // Spring's LoginUrlAuthenticationEntryPoint 302-redirects unauthenticated
  // calls to /pages/login/login, which returns 200 HTML. From fetch's
  // point of view that's a successful response — we have to spot the
  // content-type mismatch ourselves and translate it into a 401 so the
  // store-level fallback / router guard can react.
  if (!isJson) {
    throw new ApiError(
      401,
      `${method} ${url}: expected application/json but got ${contentType || '<unset>'} — likely auth redirect`,
      parsed,
      echoedReqId,
    )
  }

  return parsed as T
}

export function apiGet<T>(path: string, opts?: RequestOptions): Promise<T> {
  return request<T>('GET', path, undefined, opts)
}

export function apiPost<T>(path: string, body: unknown, opts?: RequestOptions): Promise<T> {
  return request<T>('POST', path, body, opts)
}

export function apiPut<T>(path: string, body: unknown, opts?: RequestOptions): Promise<T> {
  return request<T>('PUT', path, body, opts)
}

export function apiDelete<T>(path: string, opts?: RequestOptions): Promise<T> {
  return request<T>('DELETE', path, undefined, opts)
}

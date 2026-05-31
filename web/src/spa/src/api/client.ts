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

  constructor(status: number, message: string, body: unknown = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
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
  constructor(message: string, cause: unknown) {
    super(message)
    this.name = 'ApiNetworkError'
    this.cause = cause
  }
}

interface RequestOptions {
  /** AbortSignal — typically `useAbortableRequest()` from the store. */
  signal?: AbortSignal
  /** Extra headers to merge (rarely needed). */
  headers?: Record<string, string>
}

async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH',
  path: string,
  body: unknown,
  opts: RequestOptions = {},
): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...opts.headers,
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(path, {
      method,
      credentials: 'include',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: opts.signal,
    })
  } catch (cause) {
    throw new ApiNetworkError(`Network failure calling ${method} ${path}`, cause)
  }

  if (response.status === 204) return undefined as T

  const contentType = response.headers.get('content-type') ?? ''
  const isJson = contentType.includes('application/json')
  const parsed = isJson ? await response.json().catch(() => null) : await response.text().catch(() => null)

  if (!response.ok) {
    const message =
      (isJson && parsed && typeof parsed === 'object' && 'message' in parsed
        ? String((parsed as { message: unknown }).message)
        : undefined) ?? `${method} ${path} → ${response.status}`
    throw new ApiError(response.status, message, parsed)
  }

  // Spring's LoginUrlAuthenticationEntryPoint 302-redirects unauthenticated
  // calls to /pages/login/login, which returns 200 HTML. From fetch's
  // point of view that's a successful response — we have to spot the
  // content-type mismatch ourselves and translate it into a 401 so the
  // store-level fallback / router guard can react.
  if (!isJson) {
    throw new ApiError(
      401,
      `${method} ${path}: expected application/json but got ${contentType || '<unset>'} — likely auth redirect`,
      parsed,
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

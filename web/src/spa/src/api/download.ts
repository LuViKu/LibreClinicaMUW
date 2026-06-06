/**
 * Phase E.6 — binary-download helper for SPA export buttons.
 *
 * The audit + discrepancy export endpoints return binary payloads
 * (XLSX / CSV) with `Content-Disposition: attachment` headers, so
 * the normal `apiGet<T>()` JSON path doesn't apply. This helper:
 *
 *  - Adds the `/LibreClinica` context prefix transparently (matches
 *    `apiGet()` so call sites use the same logical `/pages/...` form).
 *  - Sends `credentials: 'include'` so the legacy `JSESSIONID`
 *    cookie travels with the request.
 *  - Surfaces the server's filename via `Content-Disposition` when
 *    present so the operator sees `audit_S_DEMO_20260606.xlsx`
 *    instead of the URL slug.
 *  - Throws `ApiError(401)` for auth-redirect HTML responses so the
 *    store-layer error handler can react the same way it does for
 *    JSON endpoints.
 *
 *  Usage:
 * ```ts
 *   await apiDownload('/pages/api/v1/audit/export.xlsx?variant=admin', 'audit.xlsx')
 * ```
 *
 * Tests stub `globalThis.fetch` + the browser DOM (`URL.createObjectURL`,
 * `document.createElement('a').click()`) so this helper can be exercised
 * without a live backend.
 */
import { ApiError, ApiNetworkError } from './client'

const CONTEXT_PATH = '/LibreClinica'

/**
 * Trigger a browser download of a binary response body. Returns the
 * resolved filename for callers that want to surface it in a toast.
 */
export async function apiDownload(
  path: string,
  fallbackFilename: string,
  opts: { signal?: AbortSignal } = {},
): Promise<{ filename: string; bytes: number }> {
  const url = path.startsWith(CONTEXT_PATH) ? path : `${CONTEXT_PATH}${path}`

  let response: Response
  try {
    response = await fetch(url, {
      method: 'GET',
      credentials: 'include',
      signal: opts.signal,
    })
  } catch (cause) {
    throw new ApiNetworkError(`Network failure calling GET ${url}`, cause)
  }

  if (!response.ok) {
    // Try to surface a JSON error body when the backend returned one.
    const contentType = response.headers.get('content-type') ?? ''
    let parsed: unknown = null
    try {
      parsed = contentType.includes('application/json')
        ? await response.json()
        : await response.text()
    } catch {
      /* swallow — error body is best-effort */
    }
    const message =
      (parsed && typeof parsed === 'object' && 'message' in parsed
        ? String((parsed as { message: unknown }).message)
        : undefined) ?? `GET ${url} → ${response.status}`
    throw new ApiError(response.status, message, parsed)
  }

  // Spring's LoginUrlAuthenticationEntryPoint 302-redirects unauthenticated
  // calls to /pages/login/login, which returns 200 HTML. Catch the
  // content-type mismatch and translate it into a 401 so the store can
  // route the user back to /login.
  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('text/html')) {
    throw new ApiError(
      401,
      `GET ${url}: expected binary payload but got ${contentType} — likely auth redirect`,
      null,
    )
  }

  const blob = await response.blob()
  const filename = parseFilename(response.headers.get('content-disposition')) ?? fallbackFilename

  // Browser side-effect: trigger the download by clicking a synthetic
  // anchor pointing at an object URL. The anchor is created + revoked
  // immediately so we don't leak DOM nodes.
  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    const objectUrl = URL.createObjectURL(blob)
    try {
      const a = document.createElement('a')
      a.href = objectUrl
      a.download = filename
      a.style.display = 'none'
      document.body.appendChild(a)
      a.click()
      a.remove()
    } finally {
      URL.revokeObjectURL(objectUrl)
    }
  }

  return { filename, bytes: blob.size }
}

/**
 * Extract the filename from a `Content-Disposition` header value.
 * Handles both the legacy `filename="…"` form and the RFC 5987
 * `filename*=UTF-8''…` form. Returns `null` if no filename is present
 * so the caller can fall back to its preferred default.
 */
export function parseFilename(header: string | null): string | null {
  if (!header) return null
  // RFC 5987 form wins over the legacy form per the RFC.
  const utf8Match = header.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
  if (utf8Match) {
    try {
      return decodeURIComponent(utf8Match[1].trim())
    } catch {
      /* fall through */
    }
  }
  const legacy = header.match(/filename\s*=\s*"?([^";]+)"?/i)
  if (legacy) return legacy[1].trim()
  return null
}

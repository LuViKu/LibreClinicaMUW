/**
 * Phase E.6 — multipart upload helper for the SPA.
 *
 * Companion to {@link ./client} for the small handful of endpoints that
 * take a binary body instead of JSON. Currently used by the CRF entry
 * store's {@code uploadFile} action; we keep it tiny so the call site
 * doesn't have to construct {@code FormData} or remember the auth-cookie
 * convention.
 *
 * <p>Auth + error semantics mirror {@code request} in {@code client.ts}:
 *   - {@code credentials: 'include'} so the JSESSIONID cookie rides along
 *   - non-2xx → {@link ApiError}
 *   - non-JSON body on 2xx → {@link ApiError} (likely the login redirect)
 *   - fetch reject → {@link ApiNetworkError}
 *
 * Kept separate from {@code request} because pulling the body builder
 * into a single overloaded function obscures the JSON / multipart split
 * — uploads also can't carry a JSON {@code Content-Type} or browsers
 * stop generating the multipart boundary.
 */

import { ApiError, ApiNetworkError } from './client'

const CONTEXT_PATH = '/LibreClinica'

interface UploadOptions {
  signal?: AbortSignal
}

/**
 * POST a single file as {@code multipart/form-data}. The field name is
 * always {@code file}; extra string fields (e.g. {@code rowOrdinal}) ride
 * alongside as plain form fields the controller can bind via
 * {@code @RequestParam}.
 */
export async function apiUpload<T>(
  path: string,
  file: File,
  fields: Record<string, string> = {},
  opts: UploadOptions = {},
): Promise<T> {
  const url = path.startsWith(CONTEXT_PATH) ? path : `${CONTEXT_PATH}${path}`
  const fd = new FormData()
  fd.append('file', file)
  for (const [k, v] of Object.entries(fields)) {
    fd.append(k, v)
  }

  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: {
        // Intentionally NOT setting Content-Type — the browser sets the
        // boundary automatically when FormData is the body. Setting it
        // here would corrupt the multipart parser server-side.
        Accept: 'application/json',
      },
      body: fd,
      signal: opts.signal,
    })
  } catch (cause) {
    throw new ApiNetworkError(`Network failure calling POST ${url}`, cause)
  }

  if (response.status === 204) return undefined as T

  const contentType = response.headers.get('content-type') ?? ''
  const isJson = contentType.includes('application/json')
  const parsed = isJson
    ? await response.json().catch(() => null)
    : await response.text().catch(() => null)

  if (!response.ok) {
    const message =
      (isJson && parsed && typeof parsed === 'object' && 'message' in parsed
        ? String((parsed as { message: unknown }).message)
        : undefined) ?? `POST ${url} → ${response.status}`
    throw new ApiError(response.status, message, parsed)
  }

  if (!isJson) {
    throw new ApiError(
      401,
      `POST ${url}: expected application/json but got ${contentType || '<unset>'} — likely auth redirect`,
      parsed,
    )
  }

  return parsed as T
}

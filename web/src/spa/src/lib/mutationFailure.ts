import { ApiError, ApiNetworkError } from '@/api/client'
import { i18n } from '@/i18n'

/**
 * A mutation the server refused, or that never got an answer.
 *
 * `fieldErrors` is filled for a 400 (one message per field, keyed by the
 * server's field name); `message` is the server's own text when it sent
 * one, so a 404 or 409 reaches the person who asked in the server's
 * words, and a localised fallback when it did not.
 *
 * Shared by the administration stores (locations, uploaders, ingest
 * sources) so that all of them answer a refusal the same way.
 */
export interface MutationFailure {
  ok: false
  /** The HTTP status; null when no answer arrived. */
  status: number | null
  fieldErrors: Record<string, string>
  message: string
}

/** The i18n keys a store falls back to when the server sent no text of its own. */
export interface FailureKeys {
  /** Takes `{status}`: used for a refusal with no message. */
  fallback: string
  /** No answer arrived at all. */
  network: string
  /** Anything else that was thrown. */
  unknown: string
}

/** One entry of the server's 400 body (`ValidationErrorBody`). */
interface FieldErrorEntry {
  field?: unknown
  message?: unknown
}

function t(key: string, named?: Record<string, unknown>): string {
  return named ? i18n.global.t(key, named) : i18n.global.t(key)
}

/**
 * What a failed call tells the person: the server's message when it sent
 * one, per-field messages for a 400, else a localised text naming the
 * status (or the missing connection).
 */
export function readMutationFailure(e: unknown, keys: FailureKeys): MutationFailure {
  if (e instanceof ApiError) {
    const body = e.body as { message?: unknown; errors?: unknown } | null
    const fieldErrors: Record<string, string> = {}
    if (body && Array.isArray(body.errors)) {
      for (const fe of body.errors as Array<FieldErrorEntry | null>) {
        if (typeof fe?.field === 'string' && typeof fe.message === 'string' && !(fe.field in fieldErrors)) {
          fieldErrors[fe.field] = fe.message
        }
      }
    }
    const sent = body && typeof body.message === 'string' ? body.message : ''
    return {
      ok: false,
      status: e.status,
      fieldErrors,
      message: sent !== '' ? sent : t(keys.fallback, { status: e.status }),
    }
  }
  if (e instanceof ApiNetworkError) {
    return { ok: false, status: null, fieldErrors: {}, message: t(keys.network) }
  }
  return {
    ok: false,
    status: null,
    fieldErrors: {},
    message: e instanceof Error && e.message !== '' ? e.message : t(keys.unknown),
  }
}

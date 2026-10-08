import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  EventCrfRemovalImpact,
  EventDetailDto,
  RemoveEventCrfRequest,
  StartEventCrfRequest,
  StartEventCrfResponse,
} from '@/types/event'

/**
 * Phase E.6 — Event Detail store.
 *
 * Wraps `GET /pages/api/v1/events/{id}`. The view consumed by
 * `/events/:eventId` (replaces the legacy
 * `/pages/EnterDataForStudyEvent?eventId=…` JSP bridge that
 * SubjectDetailView previously linked into).
 *
 * Surfaces four flavours of error so the view can branch:
 *  - `notFound`     — HTTP 404 (no such event id)
 *  - `forbidden`    — HTTP 403 (event in a study the user can't see)
 *  - `network`      — connection refused / abort
 *  - `error`        — any other server-side failure (5xx, parse, ...)
 */
export const useEventDetailStore = defineStore('eventDetail', () => {
  const event = ref<EventDetailDto | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const notFound = ref(false)
  const forbidden = ref(false)
  const network = ref(false)

  /**
   * Phase E.6 — error surface for the {@link startCrf} action. The
   * view's inline "could not start" toast reads from this; the
   * top-level `error` ref is reserved for `load()` failures so the
   * surrounding error banner doesn't hide the loaded event metadata.
   */
  const startCrfError = ref<string | null>(null)
  const isStartingCrf = ref(false)

  async function load(eventId: number | string): Promise<void> {
    isLoading.value = true
    error.value = null
    notFound.value = false
    forbidden.value = false
    network.value = false
    event.value = null
    try {
      event.value = await apiGet<EventDetailDto>(
        `/pages/api/v1/events/${encodeURIComponent(String(eventId))}`,
      )
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.status === 404) {
          notFound.value = true
          return
        }
        if (e.status === 403) {
          forbidden.value = true
          return
        }
        if (e.isUnauthorized) {
          // Surface to the router-level auth guard.
          throw e
        }
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `HTTP ${e.status}`
        return
      }
      if (e instanceof ApiNetworkError) {
        network.value = true
        return
      }
      error.value = e instanceof Error ? e.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Phase E.6 — POST to start a fresh event_crf row for an
   * unstarted CRF slot, then return the freshly-minted
   * {@code eventCrfOid} so the view can `router.push` into the
   * existing CrfEntryView. Resolves to `null` (and sets
   * {@link startCrfError}) on any 4xx/5xx/network failure so the
   * caller can present an inline toast without throwing.
   *
   * <p>409 (slot already started server-side) is handled gracefully:
   * the response body carries the existing {@code eventCrfOid}, so
   * we route to that row instead of bouncing the user.
   */
  async function startCrf(
    eventId: number | string,
    eventDefinitionCrfId: number,
    body: StartEventCrfRequest = {},
  ): Promise<string | null> {
    isStartingCrf.value = true
    startCrfError.value = null
    try {
      const res = await apiPost<StartEventCrfResponse>(
        `/pages/api/v1/events/${encodeURIComponent(String(eventId))}/crfs/${encodeURIComponent(
          String(eventDefinitionCrfId),
        )}:start`,
        body,
      )
      return res.eventCrfOid
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.isUnauthorized) {
          throw e
        }
        // 409 surfaces the existing row — let the view route there
        // instead of stranding the operator on a "couldn't start" toast.
        const body409 = e.body as
          | { eventCrfOid?: string | null; message?: string }
          | null
        if (e.status === 409 && body409?.eventCrfOid) {
          return String(body409.eventCrfOid)
        }
        const parsed = e.body as { message?: string } | null
        startCrfError.value = parsed?.message ?? `HTTP ${e.status}`
        return null
      }
      if (e instanceof ApiNetworkError) {
        startCrfError.value = 'network'
        return null
      }
      startCrfError.value = e instanceof Error ? e.message : 'Unknown error'
      return null
    } finally {
      isStartingCrf.value = false
    }
  }

  /**
   * Phase E.6 restore-quickwins — restore a soft-deleted event_crf.
   * Backend (`POST /api/v1/eventCrfs/{id}/restore`) flips AUTO_DELETED
   * → AVAILABLE and cascades AUTO_DELETED item_data back to AVAILABLE.
   *
   * <p>Refetches the event detail on success so the cards re-render
   * with the new status. Returns true on success, false on a handled
   * server error (the caller renders an inline toast); throws when
   * the session is invalid so the router-level auth guard can pick
   * it up.
   */
  const restoreCrfError = ref<string | null>(null)
  const isRestoringCrf = ref(false)

  async function restoreCrf(eventCrfId: number | string): Promise<boolean> {
    isRestoringCrf.value = true
    restoreCrfError.value = null
    try {
      await apiPost<void>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(String(eventCrfId))}/restore`,
        {},
      )
      // Refetch so the CRF card flips from "removed" to the resumed
      // status without the caller needing to know which slot to
      // patch in place.
      if (event.value?.eventId) {
        await load(event.value.eventId)
      }
      return true
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.isUnauthorized) throw e
        const body = e.body as { message?: string } | null
        restoreCrfError.value = body?.message ?? `HTTP ${e.status}`
        return false
      }
      if (e instanceof ApiNetworkError) {
        restoreCrfError.value = 'network'
        return false
      }
      restoreCrfError.value = e instanceof Error ? e.message : 'Unknown error'
      return false
    } finally {
      isRestoringCrf.value = false
    }
  }

  /**
   * What removing the CRF would take out, for the confirm dialog
   * (`GET /api/v1/eventCrfs/{id}/removal-impact`). A refusal (the CRF is
   * locked, the role may not remove it, ...) comes back as its message so
   * the dialog can say why before a reason is typed; throws on 401.
   */
  async function removalImpact(
    eventCrfId: number | string,
  ): Promise<{ impact: EventCrfRemovalImpact } | { refused: string }> {
    try {
      const impact = await apiGet<EventCrfRemovalImpact>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(String(eventCrfId))}/removal-impact`,
      )
      return { impact }
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.isUnauthorized) throw e
        const body = e.body as { message?: string } | null
        return { refused: body?.message ?? `HTTP ${e.status}` }
      }
      if (e instanceof ApiNetworkError) return { refused: 'network' }
      return { refused: e instanceof Error ? e.message : 'Unknown error' }
    }
  }

  const removeCrfError = ref<string | null>(null)
  const isRemovingCrf = ref(false)

  /**
   * Remove an event CRF with the reason the operator gave
   * (`POST /api/v1/eventCrfs/{id}/remove`): the CRF and its values are
   * marked removed and the open discrepancy notes on them closed.
   * {@link restoreCrf} is the inverse.
   *
   * <p>Does not refetch the event detail: {@link load} empties the event
   * first, which would unmount the confirm dialog while it is still open.
   * The caller closes the dialog, then reloads, and the row shows as
   * removed with its Restore action. Returns true on success, false on a
   * handled server error ({@link removeCrfError} says why); throws on 401.
   */
  async function removeCrf(eventCrfId: number | string, reason: string): Promise<boolean> {
    isRemovingCrf.value = true
    removeCrfError.value = null
    try {
      const body: RemoveEventCrfRequest = { reason }
      await apiPost<void>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(String(eventCrfId))}/remove`,
        body,
      )
      return true
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.isUnauthorized) throw e
        const errorBody = e.body as { message?: string } | null
        removeCrfError.value = errorBody?.message ?? `HTTP ${e.status}`
        return false
      }
      if (e instanceof ApiNetworkError) {
        removeCrfError.value = 'network'
        return false
      }
      removeCrfError.value = e instanceof Error ? e.message : 'Unknown error'
      return false
    } finally {
      isRemovingCrf.value = false
    }
  }

  function reset(): void {
    event.value = null
    isLoading.value = false
    error.value = null
    notFound.value = false
    forbidden.value = false
    network.value = false
    startCrfError.value = null
    isStartingCrf.value = false
    restoreCrfError.value = null
    isRestoringCrf.value = false
    removeCrfError.value = null
    isRemovingCrf.value = false
  }

  return {
    event,
    isLoading,
    error,
    notFound,
    forbidden,
    network,
    startCrfError,
    isStartingCrf,
    restoreCrfError,
    isRestoringCrf,
    removeCrfError,
    isRemovingCrf,
    load,
    startCrf,
    restoreCrf,
    removalImpact,
    removeCrf,
    reset,
  }
})

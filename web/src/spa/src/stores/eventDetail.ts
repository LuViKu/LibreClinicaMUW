import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { EventDetailDto } from '@/types/event'

/**
 * Phase E.6 — Event Detail store.
 *
 * Wraps `GET /pages/api/v1/events/{id}`. The view consumed by
 * `/events/:eventId` (replaces the legacy
 * `/pages/EnterDataForStudyEvent?eventId=…` JSP bridge that
 * SubjectDetailView previously linked into).
 *
 * Surfaces three flavours of error so the view can branch:
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

  function reset(): void {
    event.value = null
    isLoading.value = false
    error.value = null
    notFound.value = false
    forbidden.value = false
    network.value = false
  }

  return {
    event,
    isLoading,
    error,
    notFound,
    forbidden,
    network,
    load,
    reset,
  }
})

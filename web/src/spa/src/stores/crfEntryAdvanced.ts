import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  LockProbe,
  NotesRollup,
  ItemNoteSummary,
  SectionStatus,
} from '@/types/crf'

/**
 * Phase E.6 crf-entry-advanced — composable store for the three
 * advanced features on CrfEntryView:
 *
 *  1. TOC SideRail badges    (sectionStatuses)
 *  2. Concurrent-edit banner (lockProbe + heartbeat)
 *  3. Per-item notes chip    (notesRollup)
 *
 * <p>Composes with {@code useCrfEntryStore} — does not replace it.
 * Three independent fetches because each can fail independently and
 * the SideRail / banner / chips are visually orthogonal.
 *
 * <p>Heartbeat: fires every {@code ttlSeconds / 2} seconds while the
 * view is mounted. The interval id is owned by this store so
 * navigating away can {@link stopHeartbeat} cleanly. Heartbeat
 * failures are silent — they shouldn't block the CRF entry form.
 */
export const useCrfEntryAdvancedStore = defineStore('crfEntryAdvanced', () => {
  const sectionStatuses = ref<SectionStatus[]>([])
  const lockProbe = ref<LockProbe | null>(null)
  const notesRollup = ref<NotesRollup | null>(null)

  const isLoadingStatuses = ref(false)
  const isLoadingProbe = ref(false)
  const isLoadingNotes = ref(false)
  const sectionStatusesError = ref<string | null>(null)
  const lockProbeError = ref<string | null>(null)
  const notesRollupError = ref<string | null>(null)

  // Heartbeat ownership.
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null
  const heartbeatEventCrfOid = ref<string | null>(null)

  const sectionStatusByOid = computed<Record<string, SectionStatus>>(() => {
    const out: Record<string, SectionStatus> = {}
    for (const s of sectionStatuses.value) out[s.sectionOid] = s
    return out
  })

  const noteSummaryByItemOid = computed<Record<string, ItemNoteSummary>>(() => {
    return notesRollup.value?.byItemOid ?? {}
  })

  const concurrentEditorActive = computed<boolean>(() => {
    return lockProbe.value != null
      && !lockProbe.value.sameUser
      && lockProbe.value.lastEditorName != null
  })

  async function loadSectionStatuses(eventCrfOid: string): Promise<void> {
    isLoadingStatuses.value = true
    sectionStatusesError.value = null
    try {
      sectionStatuses.value = await apiGet<SectionStatus[]>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}/section-status`,
      )
    } catch (e) {
      sectionStatuses.value = []
      sectionStatusesError.value = describeError(e, 'TOC-Status')
    } finally {
      isLoadingStatuses.value = false
    }
  }

  async function loadLockProbe(eventCrfOid: string): Promise<void> {
    isLoadingProbe.value = true
    lockProbeError.value = null
    try {
      lockProbe.value = await apiGet<LockProbe>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}/lock-status`,
      )
    } catch (e) {
      lockProbe.value = null
      lockProbeError.value = describeError(e, 'Sperr-Probe')
    } finally {
      isLoadingProbe.value = false
    }
  }

  async function loadNotesRollup(eventCrfOid: string): Promise<void> {
    isLoadingNotes.value = true
    notesRollupError.value = null
    try {
      notesRollup.value = await apiGet<NotesRollup>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}/notes`,
      )
    } catch (e) {
      notesRollup.value = null
      notesRollupError.value = describeError(e, 'Notizen')
    } finally {
      isLoadingNotes.value = false
    }
  }

  /**
   * Single batched fetch on view open — three parallel GETs (the AC
   * calls for "single batched fetch + badges in same paint" so we
   * issue them concurrently rather than sequentially).
   */
  async function loadAll(eventCrfOid: string): Promise<void> {
    await Promise.all([
      loadSectionStatuses(eventCrfOid),
      loadLockProbe(eventCrfOid),
      loadNotesRollup(eventCrfOid),
    ])
  }

  /**
   * Start the heartbeat loop. Sends one POST immediately, then every
   * {@code ttlSeconds / 2} (defaults to 30s when the probe didn't
   * load yet). Idempotent: calling start twice on the same eventCrf
   * keeps the single timer.
   */
  function startHeartbeat(eventCrfOid: string): void {
    if (heartbeatTimer && heartbeatEventCrfOid.value === eventCrfOid) return
    stopHeartbeat()
    heartbeatEventCrfOid.value = eventCrfOid
    // Fire one immediately so the banner update is < 1s on entry.
    void sendHeartbeat(eventCrfOid)
    const ttl = lockProbe.value?.ttlSeconds ?? 60
    const intervalMs = Math.max(5, Math.floor(ttl / 2)) * 1000
    heartbeatTimer = setInterval(() => {
      void sendHeartbeat(eventCrfOid)
    }, intervalMs)
  }

  function stopHeartbeat(): void {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
    heartbeatEventCrfOid.value = null
  }

  async function sendHeartbeat(eventCrfOid: string): Promise<void> {
    try {
      lockProbe.value = await apiPost<LockProbe>(
        `/pages/api/v1/eventCrfs/${encodeURIComponent(eventCrfOid)}/heartbeat`,
        {},
      )
    } catch {
      // Silent — heartbeat failures must not block the form. The
      // next interval retry will try again. Pre-existing lockProbe
      // remains as-is.
    }
  }

  function reset(): void {
    sectionStatuses.value = []
    lockProbe.value = null
    notesRollup.value = null
    sectionStatusesError.value = null
    lockProbeError.value = null
    notesRollupError.value = null
    stopHeartbeat()
  }

  return {
    sectionStatuses,
    lockProbe,
    notesRollup,
    isLoadingStatuses,
    isLoadingProbe,
    isLoadingNotes,
    sectionStatusesError,
    lockProbeError,
    notesRollupError,
    sectionStatusByOid,
    noteSummaryByItemOid,
    concurrentEditorActive,
    loadSectionStatuses,
    loadLockProbe,
    loadNotesRollup,
    loadAll,
    startHeartbeat,
    stopHeartbeat,
    reset,
  }
})

function describeError(e: unknown, what: string): string {
  if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
    return `${what}: Zugriff verweigert (HTTP ${e.status}).`
  }
  if (e instanceof ApiNetworkError) {
    return `${what}: Backend nicht erreichbar.`
  }
  if (e instanceof ApiError) {
    const body = e.body as { message?: string } | null
    return body?.message ?? `${what}: HTTP ${e.status}.`
  }
  return e instanceof Error ? e.message : `${what}: unbekannter Fehler.`
}

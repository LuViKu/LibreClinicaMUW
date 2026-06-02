import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type { SdvRow, SdvRequirement, SdvStatus } from '@/types/sdv'

/**
 * Phase E.6 + E.4 M9 — SDV (Source Data Verification) store.
 *
 * Backs the SdvView. Hydrates from `GET /pages/api/v1/sdv` (the M9
 * adapter). The bulk-verify action POSTs the selected event-CRF
 * stringified ids to `/pages/api/v1/sdv/verify`; on success the
 * server returns `{ verified, rejected }` and the store optimistically
 * flips the verified rows while reporting any rejections via `error`.
 *
 * Mock removal — per the polished-jumping-swan plan's hard-removal
 * policy: the previous `loadMock()` helper + 10-row `MOCK_ROWS`
 * constant are deleted in this PR. If the backend is unreachable
 * the store sets `error` so the view can render an explicit message
 * rather than silently displaying stale demo data.
 */
export const useSdvStore = defineStore('sdv', () => {
  const rows = ref<SdvRow[]>([])
  const isLoading = ref(false)
  const isVerifying = ref(false)
  const error = ref<string | null>(null)

  const query = ref('')
  const statusFilter = ref<'all' | SdvStatus>('all')
  const requirementFilter = ref<'all' | SdvRequirement>('all')
  const onlyWithQueries = ref(false)

  /** v-model selection — Set of eventCrfOid strings. */
  const selected = ref<Set<string>>(new Set())

  const filtered = computed<SdvRow[]>(() => {
    const q = query.value.trim().toLowerCase()
    return rows.value.filter((row) => {
      if (q) {
        const blob = `${row.subjectId} ${row.crfName} ${row.eventLabel}`.toLowerCase()
        if (!blob.includes(q)) return false
      }
      if (statusFilter.value !== 'all' && row.status !== statusFilter.value) return false
      if (requirementFilter.value !== 'all' && row.requirement !== requirementFilter.value) return false
      if (onlyWithQueries.value && row.openQueries === 0) return false
      return true
    })
  })

  const totalCount = computed(() => rows.value.length)
  const visibleCount = computed(() => filtered.value.length)

  const verifiableCount = computed(() =>
    filtered.value.filter((r) => r.status === 'pending').length,
  )

  /** All visible rows that are eligible to be flipped to verified. */
  const verifiableInView = computed(() =>
    filtered.value.filter((r) => r.status === 'pending').map((r) => r.eventCrfOid),
  )

  const selectedCount = computed(() => selected.value.size)

  const allVerifiableSelected = computed(() => {
    if (verifiableInView.value.length === 0) return false
    return verifiableInView.value.every((oid) => selected.value.has(oid))
  })

  async function load(_siteOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<SdvRow[]>('/pages/api/v1/sdv')
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — SDV-Tabelle kann nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden der SDV-Tabelle (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der SDV-Tabelle.'
      }
    } finally {
      isLoading.value = false
    }
  }

  function toggle(eventCrfOid: string) {
    const row = rows.value.find((r) => r.eventCrfOid === eventCrfOid)
    if (!row || row.status !== 'pending') return
    if (selected.value.has(eventCrfOid)) selected.value.delete(eventCrfOid)
    else selected.value.add(eventCrfOid)
    selected.value = new Set(selected.value)
  }

  function toggleAllInView() {
    if (allVerifiableSelected.value) {
      verifiableInView.value.forEach((oid) => selected.value.delete(oid))
    } else {
      verifiableInView.value.forEach((oid) => selected.value.add(oid))
    }
    selected.value = new Set(selected.value)
  }

  function clearSelection() {
    selected.value = new Set()
  }

  function clearFilters() {
    query.value = ''
    statusFilter.value = 'all'
    requirementFilter.value = 'all'
    onlyWithQueries.value = false
  }

  /**
   * Bulk-verify the current selection. Returns the count of rows the
   * server reports as flipped. Any oids the server rejects are surfaced
   * via `error`; locally rejected rows are left in their previous
   * state so the user can retry.
   */
  async function verifySelected(): Promise<number> {
    const targets = [...selected.value]
    if (targets.length === 0) return 0
    isVerifying.value = true
    error.value = null
    try {
      const response = await apiPost<VerifyResponse>('/pages/api/v1/sdv/verify', {
        eventCrfOids: targets,
        verified: true,
      })
      const flipped = new Set(response.verified ?? [])
      const verifiedAt = response.verifiedAt ?? new Date().toISOString()
      for (const oid of flipped) {
        const row = rows.value.find((r) => r.eventCrfOid === oid)
        if (row) {
          row.status = 'verified'
          row.lastUpdatedAt = verifiedAt
        }
      }
      if (response.rejected && response.rejected.length > 0) {
        error.value = `${response.rejected.length} Eintrag/Einträge konnten nicht verifiziert werden.`
      }
      clearSelection()
      return flipped.size
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Verifizierung fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Verifizierung fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler bei der Verifizierung.'
      }
      return 0
    } finally {
      isVerifying.value = false
    }
  }

  /**
   * Phase E A6 — un-verify a single previously-verified CRF.
   * Inverse of `verifySelected`. The backend
   * (`POST /api/v1/sdv/unverify`) requires a `reason` and gates the
   * action on Monitor / DM / Admin role. The SPA collects the
   * reason via a small modal; passing it through unchanged.
   *
   * <p>Single-row by design — un-verify is GCP-significant; rolling
   * back N stamps at once is more dangerous than helpful. If bulk
   * un-verify becomes a real need it can be added later.
   *
   * <p>On success the in-memory row flips from `verified` back to
   * `pending` so the SDV inbox shows it again.
   */
  async function unverifyRow(eventCrfOid: string, reason: string): Promise<boolean> {
    if (reason.trim() === '') {
      error.value = 'Begründung erforderlich.'
      return false
    }
    isVerifying.value = true
    error.value = null
    try {
      await apiPost<UnverifyResponse>('/pages/api/v1/sdv/unverify', {
        eventCrfOids: [eventCrfOid],
        reason: reason.trim(),
      })
      const row = rows.value.find((r) => r.eventCrfOid === eventCrfOid)
      if (row) {
        row.status = 'pending'
        row.lastUpdatedAt = new Date().toISOString()
      }
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Un-Verifizierung nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Un-Verifizierung fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Un-Verifizierung fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value =
          e instanceof Error ? e.message : 'Unbekannter Fehler bei der Un-Verifizierung.'
      }
      return false
    } finally {
      isVerifying.value = false
    }
  }

  return {
    rows,
    isLoading,
    isVerifying,
    error,
    query,
    statusFilter,
    requirementFilter,
    onlyWithQueries,
    selected,
    filtered,
    totalCount,
    visibleCount,
    verifiableCount,
    verifiableInView,
    selectedCount,
    allVerifiableSelected,
    load,
    toggle,
    toggleAllInView,
    clearSelection,
    clearFilters,
    verifySelected,
    unverifyRow,
  }
})

/** Wire shape of POST /pages/api/v1/sdv/verify. */
interface VerifyResponse {
  verified: string[]
  rejected: string[]
  verifiedCount: number
  verifiedAt: string | null
  verifiedBy: string | null
}

/** Phase E A6 — wire shape of POST /pages/api/v1/sdv/unverify. */
interface UnverifyResponse {
  unverified: string[]
  rejected: string[]
  unverifiedCount: number
  unverifiedAt: string | null
  unverifiedBy: string | null
}

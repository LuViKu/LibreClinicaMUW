/**
 * Phase E.6 ophth-field-catalog (2026-06-11) — Pinia store for the
 * read-only ophthalmology field catalog.
 *
 * <p>Loaded once per session (lazy on first access) so the catalog
 * survives navigation between CRF entries without re-fetching. The
 * catalog is institution-wide config — its size is small (≤ ~30 rows
 * for the foreseeable future) so keeping the whole list in memory is
 * trivial.
 *
 * <p>Surface:
 *
 * <ul>
 *   <li>{@code entries} — full catalog as last fetched.</li>
 *   <li>{@code load()} — fetches the catalog. No-op when already loaded
 *       and the cached fetch hasn't expired (we treat the catalog as
 *       effectively immutable per session — admins evolve the catalog
 *       via Liquibase changesets, not runtime CRUD).</li>
 *   <li>{@code entryForOid(oid)} — convenience getter that delegates to
 *       {@link findCatalogEntryByOid}.</li>
 *   <li>{@code isLoading} / {@code error} — standard fetch state.</li>
 * </ul>
 *
 * <p>Errors:
 *
 * <ul>
 *   <li>401 / 403 → re-thrown so the global router guard reacts.</li>
 *   <li>500 / network → swallowed; the store ends with an empty
 *       {@code entries} list and an {@code error} string. The render
 *       path then falls back to the OID heuristic in CrfItemWidget,
 *       which is the existing pre-catalog behaviour. The catalog is
 *       additive — failing to fetch it shouldn't break entry-side
 *       rendering.</li>
 * </ul>
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, ApiError } from '@/api/client'
import type { OphthFieldCatalogEntry } from '@/types/ophthFieldCatalog'
import { findCatalogEntryByOid } from '@/components/ophthCatalogMatcher'

export const useOphthFieldCatalogStore = defineStore('ophthFieldCatalog', () => {
  const entries = ref<OphthFieldCatalogEntry[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  /**
   * Sentinel so {@link load} is idempotent during a session. Resets
   * to false when a manual {@code reload()} is needed — institutional
   * catalog evolution rides via Liquibase + redeploy, so the cache
   * line stays warm across CRF entries.
   */
  const loaded = ref(false)

  async function load(): Promise<void> {
    if (loaded.value || isLoading.value) return
    isLoading.value = true
    error.value = null
    try {
      const fetched = await apiGet<OphthFieldCatalogEntry[] | null>('/pages/api/v1/ophth-field-catalog')
      // The backend always returns an array, but a defensive null
      // guard keeps test fixtures + degenerate responses from breaking
      // the matcher (which expects an array).
      entries.value = Array.isArray(fetched) ? fetched : []
      loaded.value = true
    } catch (e) {
      entries.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      // Otherwise swallow so the form-level render keeps working via
      // the heuristic fallback. The store surfaces an `error` string
      // for diagnostic surfacing in dev tooling.
      error.value = e instanceof Error ? e.message : 'Unknown catalog fetch error'
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Force a re-fetch on next {@link load}. Wired into the dev
   * tooling + tests; production code doesn't need it (catalog
   * evolution requires a redeploy).
   */
  function reset(): void {
    loaded.value = false
    entries.value = []
    error.value = null
  }

  function entryForOid(oid: string): OphthFieldCatalogEntry | null {
    return findCatalogEntryByOid(oid, entries.value)
  }

  return {
    entries,
    isLoading,
    error,
    loaded,
    load,
    reset,
    entryForOid,
  }
})

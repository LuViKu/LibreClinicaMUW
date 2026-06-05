import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type { CreateSiteInput, StudyIdentity } from '@/types/study'

/**
 * Phase E A8.4 — sites store.
 *
 * One parent at a time. The view passes the parent study OID on
 * every action; the store doesn't cache per-parent since the SPA
 * only ever shows the active study's sites.
 */
export const useSitesStore = defineStore('sites', () => {
  const rows = ref<StudyIdentity[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function load(parentOid: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<StudyIdentity[]>(
        `/pages/api/v1/studies/${encodeURIComponent(parentOid)}/sites`,
      )
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      error.value = humanError(e, 'load')
    } finally {
      isLoading.value = false
    }
  }

  type MutationResult =
    | { ok: true; site: StudyIdentity }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }

  async function create(parentOid: string, body: CreateSiteInput): Promise<MutationResult> {
    return mutate(
      () => apiPost<StudyIdentity>(
        `/pages/api/v1/studies/${encodeURIComponent(parentOid)}/sites`,
        body,
      ),
      'create',
      (site) => { rows.value = [...rows.value, site] },
    )
  }

  async function update(
    parentOid: string,
    siteOid: string,
    patch: Partial<CreateSiteInput>,
  ): Promise<MutationResult> {
    return mutate(
      () => apiPut<StudyIdentity>(
        `/pages/api/v1/studies/${encodeURIComponent(parentOid)}/sites/${encodeURIComponent(siteOid)}`,
        patch,
      ),
      'update',
      (site) => {
        const idx = rows.value.findIndex((r) => r.oid === siteOid)
        if (idx >= 0) rows.value[idx] = site
      },
    )
  }

  async function disable(parentOid: string, siteOid: string): Promise<boolean> {
    try {
      const updated = await apiPost<StudyIdentity>(
        `/pages/api/v1/studies/${encodeURIComponent(parentOid)}/sites/${encodeURIComponent(siteOid)}/disable`,
        {},
      )
      const idx = rows.value.findIndex((r) => r.oid === siteOid)
      if (idx >= 0) rows.value[idx] = updated
      return true
    } catch (e) {
      error.value = humanError(e, 'disable')
      return false
    }
  }

  async function restore(parentOid: string, siteOid: string): Promise<boolean> {
    try {
      const updated = await apiPost<StudyIdentity>(
        `/pages/api/v1/studies/${encodeURIComponent(parentOid)}/sites/${encodeURIComponent(siteOid)}/restore`,
        {},
      )
      const idx = rows.value.findIndex((r) => r.oid === siteOid)
      if (idx >= 0) rows.value[idx] = updated
      return true
    } catch (e) {
      error.value = humanError(e, 'restore')
      return false
    }
  }

  async function mutate(
    op: () => Promise<StudyIdentity>,
    label: 'create' | 'update',
    onSuccess: (s: StudyIdentity) => void,
  ): Promise<MutationResult> {
    try {
      const site = await op()
      onSuccess(site)
      return { ok: true, site }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        error.value = humanError(e, label)
        throw e
      }
      if (e instanceof ApiError) {
        const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
        const fieldErrors: Record<string, string> = {}
        if (body?.errors) for (const fe of body.errors) fieldErrors[fe.field] = fe.message
        return {
          ok: false,
          fieldErrors,
          message: body?.message ?? `Standort ${label} fehlgeschlagen (HTTP ${e.status}).`,
        }
      }
      if (e instanceof ApiNetworkError) {
        return { ok: false, fieldErrors: {}, message: `Backend nicht erreichbar — Standort ${label} fehlgeschlagen.` }
      }
      return { ok: false, fieldErrors: {}, message: e instanceof Error ? e.message : `Unbekannter Fehler beim Standort-${label}.` }
    }
  }

  function humanError(e: unknown, op: string): string {
    if (e instanceof ApiNetworkError) return `Backend nicht erreichbar — ${op} fehlgeschlagen.`
    if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      return body?.message ?? `${op} fehlgeschlagen (HTTP ${e.status}).`
    }
    return e instanceof Error ? e.message : `Unbekannter Fehler beim ${op}.`
  }

  /**
   * Phase E.6 — clear study-scoped state so the sites view doesn't
   * show study-A sites on study B. Called by {@link
   * useAuthStore.pickStudy} before re-bootstrapping.
   */
  function reset() {
    rows.value = []
    isLoading.value = false
    error.value = null
  }

  return { rows, isLoading, error, load, create, update, disable, restore, reset }
})

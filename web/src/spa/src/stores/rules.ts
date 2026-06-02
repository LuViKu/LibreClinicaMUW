import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, apiDelete, ApiError, ApiNetworkError } from '@/api/client'
import type { RuleActionRunLogEntry, RuleSet } from '@/types/rule'

/**
 * Phase E RX.3 — wire shape for {@code POST /api/v1/rules/test-expression}.
 *
 * The store-level result type is a discriminated union so the
 * detail-pane view can branch on {@code ok} without parsing the
 * back-end's error envelope itself.
 */
export type TestExpressionResult =
  | { ok: true; result: string; evaluatedAt: string }
  | { ok: false; message: string }

/**
 * Phase E RX.2 — wire shape for {@code POST /api/v1/rules/import}.
 *
 * The preview response mirrors the backend
 * {@code RulesImportPreviewDto}. The `errors` field on the failure
 * branch holds the validator's per-record findings so the dialog
 * can render them inline; the canonical `message` is a short
 * summary suitable for a toast.
 */
export interface RulesImportPreview {
  previewToken: string
  validRuleCount: number
  duplicateRuleCount: number
  invalidRuleCount: number
  validRuleSetCount: number
  duplicateRuleSetCount: number
  invalidRuleSetCount: number
  issues: Array<{
    scope: 'rule' | 'ruleSet'
    identifier: string
    severity: 'ERROR' | 'WARNING'
    message: string
  }>
}

export type RulesImportUploadResult =
  | { ok: true; preview: RulesImportPreview }
  | { ok: false; message: string; errors: string[] }

/** Phase E RX.2 — wire shape for {@code POST /api/v1/rules/import/commit}. */
export interface RulesImportCommit {
  rulesCreated: number
  rulesReplaced: number
  ruleSetsCreated: number
  ruleSetsReplaced: number
  actionsCreated: number
  committedAt: string
}

export type RulesImportCommitResult =
  | { ok: true; result: RulesImportCommit }
  | { ok: false; message: string }

/**
 * Phase E RX.1 — rules store (read-only).
 *
 * Hydrates from `GET /api/v1/rule-sets`. The store keeps the full
 * rule_set list in memory; if the dataset grows past a few hundred
 * rows we'll need a paged surface — for now the typical study
 * carries dozens.
 */
export const useRulesStore = defineStore('rules', () => {
  const rows = ref<RuleSet[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const selected = ref<RuleSet | null>(null)
  const isLoadingSelected = ref(false)
  const selectedError = ref<string | null>(null)

  /**
   * Phase E RX.1b — fire-history rows for the currently inspected
   * rule_set. Keyed by rule_set id so switching between rule_sets in
   * the detail pane doesn't leak entries from the previous selection.
   */
  const runLog = ref<RuleActionRunLogEntry[]>([])
  const runLogRuleSetId = ref<number | null>(null)
  const isLoadingRunLog = ref(false)
  const runLogError = ref<string | null>(null)

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<RuleSet[]>('/pages/api/v1/rule-sets')
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      error.value = humanError(e, 'load')
    } finally {
      isLoading.value = false
    }
  }

  async function fetchOne(id: number): Promise<RuleSet | null> {
    isLoadingSelected.value = true
    selectedError.value = null
    try {
      const detail = await apiGet<RuleSet>(`/pages/api/v1/rule-sets/${id}`)
      selected.value = detail
      return detail
    } catch (e) {
      selected.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      selectedError.value = humanError(e, 'load')
      return null
    } finally {
      isLoadingSelected.value = false
    }
  }

  /**
   * Fetch a page of run-log entries for `ruleSetId`. Newest first
   * (server orders by run-log id DESC). When `offset === 0` the
   * existing array is replaced; otherwise the new page is appended
   * (drives the "Load more" pattern in RulesView).
   */
  async function fetchRunLog(
    ruleSetId: number,
    limit = 25,
    offset = 0,
  ): Promise<RuleActionRunLogEntry[]> {
    isLoadingRunLog.value = true
    runLogError.value = null
    if (offset === 0) {
      runLog.value = []
      runLogRuleSetId.value = ruleSetId
    }
    try {
      const page = await apiGet<RuleActionRunLogEntry[]>(
        `/pages/api/v1/rule-sets/${ruleSetId}/run-log?limit=${limit}&offset=${offset}`,
      )
      // Guard against race: if the user switched rule_sets while the
      // page was in flight, drop the late response.
      if (runLogRuleSetId.value !== ruleSetId) return page
      runLog.value = offset === 0 ? page : runLog.value.concat(page)
      return page
    } catch (e) {
      if (offset === 0) runLog.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      runLogError.value = humanError(e, 'load')
      return []
    } finally {
      isLoadingRunLog.value = false
    }
  }

  /**
   * Phase E RX.3 — POST a rule expression to the test endpoint.
   *
   * Returns a discriminated union so the caller can render the
   * 200 / 400 branches uniformly. Network errors collapse into the
   * 400 branch with a translated message; auth errors propagate
   * (the router guard handles them).
   *
   * <p>Backend contract:
   * <ul>
   *   <li>200 → {@code {result, evaluatedAt}} — result is the
   *       parser's stringified evaluation, typically
   *       {@code "true"} / {@code "false"}</li>
   *   <li>400 → {@code {message, errors: [{field, message}]}} —
   *       parse failure or empty body</li>
   * </ul>
   */
  async function testExpression(
    expression: string,
    testValues: Record<string, string>,
  ): Promise<TestExpressionResult> {
    try {
      const body = await apiPost<{ result: string; evaluatedAt: string }>(
        '/pages/api/v1/rules/test-expression',
        { expression, testValues },
      )
      return { ok: true, result: body.result, evaluatedAt: body.evaluatedAt }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiError) {
        const body = e.body as { errors?: Array<{ message: string }>; message?: string } | null
        const fieldMessage = body?.errors?.[0]?.message
        return { ok: false, message: fieldMessage ?? body?.message ?? `HTTP ${e.status}` }
      }
      if (e instanceof ApiNetworkError) {
        return { ok: false, message: 'Backend nicht erreichbar — Test fehlgeschlagen.' }
      }
      return { ok: false, message: e instanceof Error ? e.message : 'Unbekannter Fehler.' }
    }
  }

  /**
   * Phase E RX.4 — lifecycle mutations.
   *
   * Each helper applies the refreshed RuleSetDto to {@code rows} +
   * {@code selected} so the view re-renders without a refetch. On
   * failure, returns false and sets {@code error}; on auth failures
   * (401/403) re-throws so the router guard can react.
   *
   * The backend returns the full {@code RuleSetDto} from each
   * mutation, mirroring A8.5's "return-the-projected-row" pattern.
   * That lets the SPA swap in-place without a second GET round-trip.
   *
   * Wrapping shared swap logic in {@code applyRefreshed} keeps the
   * four call sites short and identical to each other.
   */
  function applyRefreshed(updated: RuleSet): void {
    const idx = rows.value.findIndex((r) => r.id === updated.id)
    if (idx >= 0) rows.value[idx] = updated
    if (selected.value && selected.value.id === updated.id) selected.value = updated
  }

  function handleMutationError(e: unknown, op: string): void {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      const body = e.body as { message?: string } | null
      error.value = body?.message ?? `${op} nicht erlaubt (HTTP ${e.status}).`
      throw e
    }
    if (e instanceof ApiNetworkError) {
      error.value = `Backend nicht erreichbar — ${op} fehlgeschlagen. Bitte später erneut versuchen.`
    } else if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      error.value = body?.message ?? `${op} fehlgeschlagen (HTTP ${e.status}).`
    } else {
      error.value = e instanceof Error ? e.message : `Unbekannter Fehler beim ${op}.`
    }
  }

  async function disableRuleSet(id: number): Promise<boolean> {
    try {
      const updated = await apiPost<RuleSet>(`/pages/api/v1/rule-sets/${id}/disable`, {})
      applyRefreshed(updated)
      return true
    } catch (e) {
      handleMutationError(e, 'Deaktivieren')
      return false
    }
  }

  async function restoreRuleSet(id: number): Promise<boolean> {
    try {
      const updated = await apiPost<RuleSet>(`/pages/api/v1/rule-sets/${id}/restore`, {})
      applyRefreshed(updated)
      return true
    } catch (e) {
      handleMutationError(e, 'Wiederherstellen')
      return false
    }
  }

  async function disableAttachedRule(
    ruleSetId: number,
    ruleSetRuleId: number,
  ): Promise<boolean> {
    try {
      const updated = await apiPost<RuleSet>(
        `/pages/api/v1/rule-sets/${ruleSetId}/rules/${ruleSetRuleId}/disable`,
        {},
      )
      applyRefreshed(updated)
      return true
    } catch (e) {
      handleMutationError(e, 'Deaktivieren')
      return false
    }
  }

  async function restoreAttachedRule(
    ruleSetId: number,
    ruleSetRuleId: number,
  ): Promise<boolean> {
    try {
      const updated = await apiPost<RuleSet>(
        `/pages/api/v1/rule-sets/${ruleSetId}/rules/${ruleSetRuleId}/restore`,
        {},
      )
      applyRefreshed(updated)
      return true
    } catch (e) {
      handleMutationError(e, 'Wiederherstellen')
      return false
    }
  }

  /**
   * DELETE alias for {@link disableRuleSet}. Kept for callers (e.g.
   * a future RX.5 "permanent delete" UX) that want the verb-specific
   * call site; today's UI uses {@link disableRuleSet} directly.
   * Retained here to keep apiDelete from disappearing under unused-
   * import lint.
   */
  async function deleteRuleSet(id: number): Promise<boolean> {
    try {
      const updated = await apiDelete<RuleSet>(`/pages/api/v1/rule-sets/${id}`)
      applyRefreshed(updated)
      return true
    } catch (e) {
      handleMutationError(e, 'Löschen')
      return false
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

  /* ---------------------------------------------------------------- */
  /* Phase E RX.2 — XML import (preview + commit)                       */
  /* ---------------------------------------------------------------- */

  /**
   * Upload an XML rules file and get a preview back. Wraps
   * {@code POST /api/v1/rules/import} (multipart). Returns a
   * discriminated union so the dialog can branch on {@code ok}
   * uniformly; 4xx / 5xx surface as {@code ok: false}, while 401 /
   * 403 throw so the router guard can route to login or surface a
   * permission toast.
   */
  async function uploadRulesXml(file: File): Promise<RulesImportUploadResult> {
    const form = new FormData()
    form.append('file', file)
    try {
      const res = await fetch('/LibreClinica/pages/api/v1/rules/import', {
        method: 'POST',
        body: form,
        credentials: 'include',
      })
      if (!res.ok) {
        let body: { message?: string; errors?: Array<{ field: string; message: string }> } = {}
        try { body = await res.json() } catch { /* not JSON */ }
        if (res.status === 401 || res.status === 403) {
          throw new ApiError(res.status, body.message ?? res.statusText, body)
        }
        const errors = (body.errors ?? []).map((e) => e.message)
        return {
          ok: false,
          message: body.message ?? `Upload fehlgeschlagen (HTTP ${res.status}).`,
          errors,
        }
      }
      const preview: RulesImportPreview = await res.json()
      return { ok: true, preview }
    } catch (e) {
      if (e instanceof ApiError) throw e
      if (e instanceof ApiNetworkError) {
        return { ok: false, message: 'Backend nicht erreichbar — Upload fehlgeschlagen.', errors: [] }
      }
      return {
        ok: false,
        message: e instanceof Error ? e.message : 'Unbekannter Upload-Fehler.',
        errors: [],
      }
    }
  }

  /**
   * Commit a previously previewed import. Wraps
   * {@code POST /api/v1/rules/import/commit}. After success the
   * caller refreshes the list via {@code load()}.
   */
  async function commitImport(
    previewToken: string,
    ignoreDuplicates: boolean,
  ): Promise<RulesImportCommitResult> {
    try {
      const body = await apiPost<RulesImportCommit>(
        '/pages/api/v1/rules/import/commit',
        { previewToken, ignoreDuplicates },
      )
      return { ok: true, result: body }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        return { ok: false, message: body?.message ?? `Commit fehlgeschlagen (HTTP ${e.status}).` }
      }
      if (e instanceof ApiNetworkError) {
        return { ok: false, message: 'Backend nicht erreichbar — Commit fehlgeschlagen.' }
      }
      return { ok: false, message: e instanceof Error ? e.message : 'Unbekannter Commit-Fehler.' }
    }
  }

  return {
    rows,
    isLoading,
    error,
    selected,
    isLoadingSelected,
    selectedError,
    runLog,
    runLogRuleSetId,
    isLoadingRunLog,
    runLogError,
    load,
    fetchOne,
    fetchRunLog,
    testExpression,
    disableRuleSet,
    restoreRuleSet,
    disableAttachedRule,
    restoreAttachedRule,
    deleteRuleSet,
    uploadRulesXml,
    commitImport,
  }
})

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, apiPut, apiDelete, ApiError, ApiNetworkError } from '@/api/client'
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
 * Phase E RX.7 — wire-shape result for the schedule-edit endpoint.
 *
 * On success the backend returns the refreshed `RuleSetDto` so the
 * caller can swap it into `rows` + `selected` without a second GET.
 * On failure the message is the human-readable backend message
 * (validation, 403, network error all collapse here so the inline
 * form has a single render branch).
 */
export type SetScheduleResult =
  | { ok: true; ruleSet: RuleSet }
  | { ok: false; message: string }

/**
 * Phase E RX.5b — wire-shape result for the inline rule-create
 * endpoint ({@code POST /api/v1/rules}).
 *
 * The success branch returns the freshly persisted rule's id + the
 * canonical-cased fields the backend chose; the wizard threads
 * {@code oid} into the next step so the operator doesn't have to
 * retype it.
 *
 * The failure branch collapses 400 / 403 / network into a single
 * shape — the wizard renders inline per-field messages from
 * {@code fieldErrors} and a global {@code message} for envelope-level
 * problems. Empty {@code fieldErrors} with a {@code message} is the
 * shape for "request couldn't even be parsed" / "permission denied".
 */
export interface CreatedRule {
  id: number
  oid: string
  name: string
  description: string
  expression: string
}
export type CreateRuleResult =
  | { ok: true; rule: CreatedRule }
  | { ok: false; fieldErrors: Record<string, string>; message?: string }

/**
 * Phase E RX.5b — wire-shape result for inline rule_set create.
 * Success carries the freshly persisted {@link RuleSet} DTO with the
 * cascade-persisted {@code rule_set_rule} ids; the wizard threads the
 * first attached row's id into step 3.
 */
export type CreateRuleSetResult =
  | { ok: true; ruleSet: RuleSet }
  | { ok: false; fieldErrors: Record<string, string>; message?: string }

/** Phase E RX.5b — wire-shape result for inline action create. */
export type CreateActionResult =
  | { ok: true }
  | { ok: false; fieldErrors: Record<string, string>; message?: string }

/**
 * Phase E.5 RX.6b — request payload for {@code PUT /api/v1/rules/{id}}.
 *
 * All fields optional — undefined fields are not sent to the backend
 * which interprets absent fields as "leave unchanged". The SPA edit
 * dialog submits the full object every save so the operator sees the
 * persisted values before committing; the store passes through the
 * minimal "actually changed" subset by computing the diff at the call
 * site.
 */
export interface UpdateRuleInput {
  name?: string
  description?: string
  expression?: string
}

/** Phase E.5 RX.6b — result for inline rule edit. */
export type UpdateRuleResult =
  | { ok: true; rule: CreatedRule }
  | { ok: false; fieldErrors: Record<string, string>; message?: string }

/**
 * Phase E.5 RX.6b — request payload for
 * {@code PUT /api/v1/rule-sets/{ruleSetId}/actions/{actionId}}.
 *
 * Same nullable-field semantics as {@link UpdateRuleInput}.
 * {@code phaseGates} is wholesale on/off — non-null replaces all five
 * gate booleans; null leaves them as-is.
 */
export interface UpdateActionInput {
  message?: string
  expressionEvaluatesTo?: boolean
  to?: string
  phaseGates?: {
    administrativeDataEntry?: boolean
    initialDataEntry?: boolean
    doubleDataEntry?: boolean
    importDataEntry?: boolean
    batch?: boolean
  }
}

/** Phase E.5 RX.6b — result for inline action edit. */
export type UpdateActionResult =
  | { ok: true }
  | { ok: false; fieldErrors: Record<string, string>; message?: string }

/** Phase E RX.5b — wire-shape result for the live target-validation probe. */
export interface ValidateTargetResult {
  valid: boolean
  errors: Array<{ message: string }>
}

/**
 * Phase E RX.5b — request payload for {@code POST /api/v1/rule-sets/{id}/actions}.
 *
 * The wizard binds the four supported action types
 * ({@code FILE_DISCREPANCY_NOTE} / {@code EMAIL} / {@code SHOW} /
 * {@code HIDE}); the {@code properties} array is only meaningful for
 * SHOW / HIDE and is skipped for the others (the backend rejects
 * properties on Email / Discrepancy actions).
 */
export interface CreateActionInput {
  ruleSetRuleId: number
  actionType: string
  expressionEvaluatesTo?: boolean
  message?: string
  to?: string
  properties?: Array<{ oid: string; value?: string; valueExpression?: string }>
  phaseGates: {
    administrativeDataEntry?: boolean
    initialDataEntry?: boolean
    doubleDataEntry?: boolean
    importDataEntry?: boolean
    batch?: boolean
  }
}

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

  /**
   * Phase E RX.7 — flip the rule_set's run_schedule / run_time pair.
   *
   * Wraps `PUT /api/v1/rule-sets/{id}/schedule`. The backend returns
   * the full refreshed RuleSetDto; we apply it to {@code rows} +
   * {@code selected} via {@link applyRefreshed} so the view updates
   * without a second GET.
   *
   * <p>Returns a discriminated union so the inline form has a single
   * render branch for 200 / 400 / 403 / network. Auth errors (401 /
   * 403) collapse into the failure branch here rather than throwing —
   * the form already needs to render an inline error message, and
   * the role-gated UI hint makes a 403 unexpected enough that
   * popping the user out to the router guard would be more
   * surprising than helpful.
   */
  async function setSchedule(
    ruleSetId: number,
    runSchedule: boolean,
    runTime: string | null,
  ): Promise<SetScheduleResult> {
    try {
      const updated = await apiPut<RuleSet>(
        `/pages/api/v1/rule-sets/${ruleSetId}/schedule`,
        { runSchedule, runTime },
      )
      applyRefreshed(updated)
      return { ok: true, ruleSet: updated }
    } catch (e) {
      if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        return { ok: false, message: body?.message ?? `HTTP ${e.status}` }
      }
      if (e instanceof ApiNetworkError) {
        return { ok: false, message: 'Backend nicht erreichbar — Speichern fehlgeschlagen.' }
      }
      return { ok: false, message: e instanceof Error ? e.message : 'Unbekannter Fehler.' }
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

  /* ---------------------------------------------------------------- */
  /* Phase E RX.5b — inline create (wizard)                             */
  /*                                                                    */
  /* Four discriminated-union actions backing the 3-step wizard. Each   */
  /* collapses 400 / 403 / network errors into the failure branch with  */
  /* per-field messages where the backend supplies them, mirroring the  */
  /* RX.7 {@code setSchedule} shape. Auth errors (401) propagate via    */
  /* throw so the router guard can route to login.                      */
  /* ---------------------------------------------------------------- */

  /**
   * Convert an {@link ApiError} body's `errors: [{field, message}]`
   * envelope into a flat {field → message} map. Per-field messages
   * are preserved as-is. Same shape the RX.5 backend uses for every
   * validation error.
   */
  function extractFieldErrors(e: unknown): { fieldErrors: Record<string, string>; message?: string } {
    if (e instanceof ApiError) {
      if (e.isUnauthorized) throw e
      const body = e.body as
        | { message?: string; errors?: Array<{ field?: string; message?: string }> }
        | null
      const fieldErrors: Record<string, string> = {}
      if (body?.errors) {
        for (const fe of body.errors) {
          const field = fe?.field
          const message = fe?.message
          if (field && message) fieldErrors[field] = message
        }
      }
      return { fieldErrors, message: body?.message ?? `HTTP ${e.status}` }
    }
    if (e instanceof ApiNetworkError) {
      return { fieldErrors: {}, message: 'Backend nicht erreichbar — Speichern fehlgeschlagen.' }
    }
    return {
      fieldErrors: {},
      message: e instanceof Error ? e.message : 'Unbekannter Fehler.',
    }
  }

  async function createRule(input: {
    oid: string
    name: string
    description?: string
    expression: string
  }): Promise<CreateRuleResult> {
    try {
      const body = await apiPost<CreatedRule>('/pages/api/v1/rules', {
        oid: input.oid,
        name: input.name,
        description: input.description ?? '',
        expression: input.expression,
      })
      return { ok: true, rule: body }
    } catch (e) {
      return { ok: false, ...extractFieldErrors(e) }
    }
  }

  async function createRuleSet(input: {
    target: string
    studyEventDefinitionOid?: string
    crfOid?: string
    crfVersionOid?: string
    ruleOids: string[]
  }): Promise<CreateRuleSetResult> {
    // Backend treats absent vs. blank optional scope fields the same
    // (both resolve to "scope-wide"), so strip blanks here to keep the
    // wire payload tidy and the future audit log readable.
    const payload: Record<string, unknown> = {
      target: input.target,
      ruleOids: input.ruleOids,
    }
    if (input.studyEventDefinitionOid && input.studyEventDefinitionOid.trim().length > 0) {
      payload.studyEventDefinitionOid = input.studyEventDefinitionOid.trim()
    }
    if (input.crfOid && input.crfOid.trim().length > 0) {
      payload.crfOid = input.crfOid.trim()
    }
    if (input.crfVersionOid && input.crfVersionOid.trim().length > 0) {
      payload.crfVersionOid = input.crfVersionOid.trim()
    }
    try {
      const body = await apiPost<RuleSet>('/pages/api/v1/rule-sets', payload)
      // Eagerly append so the list refresh isn't required to surface
      // the new row to the operator. The post-wizard `rules.load()`
      // still runs to keep state honest if anything raced.
      rows.value = [...rows.value, body]
      return { ok: true, ruleSet: body }
    } catch (e) {
      return { ok: false, ...extractFieldErrors(e) }
    }
  }

  async function createAction(
    ruleSetId: number,
    input: CreateActionInput,
  ): Promise<CreateActionResult> {
    try {
      await apiPost<unknown>(`/pages/api/v1/rule-sets/${ruleSetId}/actions`, input)
      return { ok: true }
    } catch (e) {
      return { ok: false, ...extractFieldErrors(e) }
    }
  }

  /**
   * Probe {@code POST /api/v1/rules/validate-target}. The endpoint
   * always returns 200; the validity verdict lives in the response
   * body. Network / auth failures collapse into an invalid result
   * with a single synthesized error so the wizard's inline status
   * has a single render branch.
   */
  async function validateTarget(target: string): Promise<ValidateTargetResult> {
    try {
      const body = await apiPost<ValidateTargetResult>(
        '/pages/api/v1/rules/validate-target',
        { target },
      )
      return body
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        return { valid: false, errors: [{ message: body?.message ?? `HTTP ${e.status}` }] }
      }
      if (e instanceof ApiNetworkError) {
        return { valid: false, errors: [{ message: 'Backend nicht erreichbar.' }] }
      }
      return { valid: false, errors: [{ message: e instanceof Error ? e.message : 'Unbekannter Fehler.' }] }
    }
  }

  /**
   * Phase E.5 RX.6b — PUT /api/v1/rules/{id}. Per-field nullable patch
   * (undefined fields are sent as null, which the backend treats as
   * "leave unchanged"). On success, refresh the selected rule_set so
   * the operator sees the updated rule expression / name / description.
   */
  async function updateRule(
    ruleId: number,
    input: UpdateRuleInput,
  ): Promise<UpdateRuleResult> {
    try {
      const body = await apiPut<CreatedRule>(`/pages/api/v1/rules/${ruleId}`, {
        name: input.name ?? null,
        description: input.description ?? null,
        expression: input.expression ?? null,
      })
      if (selected.value) {
        await fetchOne(selected.value.id)
      }
      return { ok: true, rule: body }
    } catch (e) {
      return { ok: false, ...extractFieldErrors(e) }
    }
  }

  /**
   * Phase E.5 RX.6b — PUT /api/v1/rule-sets/{ruleSetId}/actions/{actionId}.
   * Per-field nullable patch. On success, refresh the rule_set so the
   * updated action message / gates / target render correctly.
   */
  async function updateAction(
    ruleSetId: number,
    actionId: number,
    input: UpdateActionInput,
  ): Promise<UpdateActionResult> {
    try {
      await apiPut<unknown>(
        `/pages/api/v1/rule-sets/${ruleSetId}/actions/${actionId}`,
        {
          message: input.message ?? null,
          expressionEvaluatesTo: input.expressionEvaluatesTo ?? null,
          to: input.to ?? null,
          phaseGates: input.phaseGates ?? null,
        },
      )
      if (selected.value && selected.value.id === ruleSetId) {
        await fetchOne(ruleSetId)
      }
      return { ok: true }
    } catch (e) {
      return { ok: false, ...extractFieldErrors(e) }
    }
  }

  /**
   * Phase E.6 — clear every piece of study-scoped state so the rules
   * view doesn't carry study-A rule_sets / run-log entries into study
   * B. Called by {@link useAuthStore.pickStudy} before re-bootstrapping.
   */
  function reset() {
    rows.value = []
    isLoading.value = false
    error.value = null
    selected.value = null
    isLoadingSelected.value = false
    selectedError.value = null
    runLog.value = []
    runLogRuleSetId.value = null
    isLoadingRunLog.value = false
    runLogError.value = null
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
    setSchedule,
    uploadRulesXml,
    commitImport,
    createRule,
    createRuleSet,
    createAction,
    updateRule,
    updateAction,
    validateTarget,
    reset,
  }
})

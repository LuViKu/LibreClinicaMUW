import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  EventCellSnapshot,
  EventStatus,
  Gender,
  GroupAssignmentInput,
  SignPreflight,
  StudyEye,
  Subject,
  SubjectDetail,
  TransitionEyeRequest,
  TransitionPreflight,
} from '@/types/subject'

/**
 * Phase E.4 M2 — Subjects store (real-backend wired).
 *
 * Hydrates the Investigator's Subject Matrix from
 * `GET /pages/api/v1/subjects` (M2 adapter). The response shape matches
 * the SPA `Subject` TS type byte-for-byte: identity columns, per-event
 * status cells (events[]), aggregate sign-off flag, and aggregate
 * open-query count.
 *
 * Mock-data policy (per plan §"Mock removal policy"): the adapter is
 * real and complete, so there is NO fallback to mock fixtures. If the
 * backend is unreachable or returns an error, `error` is set and
 * `rows` stays empty — the view shows the empty state plus an error
 * banner rather than hiding the issue behind demo data.
 *
 * Auth errors (401/403) still propagate untouched so the router-level
 * guard can redirect to /login.
 *
 * `add()` remains optimistic-append for now (Add Subject swap lands in
 * Phase E.4 M4 — see TODO inside).
 *
 * Filter state lives in the store so navigating away from the matrix
 * and back keeps the user's filter context (the existing JSP also does
 * this via session-scoped state).
 */
export const useSubjectsStore = defineStore('subjects', () => {
  const rows = ref<Subject[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  // Detail-view state — separate from `rows` because the matrix list
  // returns a leaner shape than the detail endpoint. M3 fetches the
  // detail on-demand into `selected`; the view watches the route
  // param and re-fetches when it changes.
  const selected = ref<SubjectDetail | null>(null)
  const isLoadingSelected = ref(false)
  const selectedError = ref<string | null>(null)

  // Phase E.4 M8 — sign-preflight cache. Single slot because the
  // Sign Subject view is single-subject at a time; navigating to a
  // different subject clears it via the next fetch. The view also
  // owns its blocker computation off this ref.
  const preflight = ref<SignPreflight | null>(null)
  const isLoadingPreflight = ref(false)
  const preflightError = ref<string | null>(null)

  // Filter state — persisted across navigation.
  const query = ref('')
  const statusFilter = ref<'all' | 'open-events' | 'all-events-complete' | 'signed'>('all')
  const onlyWithQueries = ref(false)
  /**
   * Phase E.6 subject-lifecycle — Show-removed toggle. Persisted
   * across navigation so a DM / Admin who flipped it on for a
   * remediation pass doesn't have to flip it on again when they
   * come back to the matrix. The store re-fetches the matrix when
   * this flips because the backend filters server-side.
   */
  const showRemoved = ref(false)

  const filtered = computed<Subject[]>(() => {
    const q = query.value.trim().toLowerCase()
    return rows.value.filter((subject) => {
      if (q && !subject.id.toLowerCase().includes(q) && !(subject.secondaryId ?? '').toLowerCase().includes(q)) {
        return false
      }
      if (onlyWithQueries.value && subject.openQueries === 0) return false

      switch (statusFilter.value) {
        case 'open-events':
          return subject.events.some((e) => e.status === 'scheduled' || e.status === 'in-progress' || e.status === 'not-scheduled')
        case 'all-events-complete':
          return subject.events.every((e) => e.status === 'complete' || e.status === 'signed' || e.status === 'locked')
        case 'signed':
          return subject.signed
        case 'all':
        default:
          return true
      }
    })
  })

  const totalCount = computed(() => rows.value.length)
  const visibleCount = computed(() => filtered.value.length)

  async function load(_siteOid?: string) {
    isLoading.value = true
    error.value = null
    try {
      // Phase E.6 subject-lifecycle — propagate the showRemoved flag
      // server-side. Default (false) keeps the legacy matrix shape
      // (only AVAILABLE / LOCKED / SIGNED rows); true brings the
      // DELETED + AUTO_DELETED rows in so DM/Admin can pick a
      // candidate to Restore.
      const qs = showRemoved.value ? '?includeRemoved=true' : ''
      rows.value = await apiGet<Subject[]>(`/pages/api/v1/subjects${qs}`)
    } catch (e) {
      // Always clear rows on error so the view doesn't show stale data
      // alongside a fresh error message.
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        // Let the router-level auth guard handle these — propagate so
        // the calling view doesn't silently render an empty list.
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value = 'Backend nicht erreichbar — bitte später erneut versuchen.'
        return
      }
      if (e instanceof ApiError) {
        // 400 ("no active study bound"), 4xx generic, 5xx — surface the
        // server message so the user knows what's wrong.
        error.value = e.message
        return
      }
      error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Teilnehmenden.'
    } finally {
      isLoading.value = false
    }
  }

  function clearFilters() {
    query.value = ''
    statusFilter.value = 'all'
    onlyWithQueries.value = false
  }

  /**
   * Phase E.6 Y3 — project a `SubjectDetail` back down to the leaner
   * matrix `Subject` shape and overwrite the matching row in
   * {@link rows} (no-op if the matrix hasn't been loaded yet).
   *
   * The matrix list endpoint and the detail endpoint both read
   * per-event status from `study_event.subject_event_status_id` via
   * the same `mapSubjectEventStatus` server-side helper, so the
   * projection is lossless — drops `dateStart`, `dateEnd`,
   * `location`, `dataEntryStage`, `eventId` which the matrix doesn't
   * render. Used by both {@link fetchOne} and {@link signSubject} so
   * any operation that returns a fresh detail keeps the matrix
   * coherent without forcing a second list refetch.
   */
  function syncMatrixRowFromDetail(detail: SubjectDetail): void {
    const idx = rows.value.findIndex((r) => r.id === detail.id)
    if (idx < 0) return
    const eventSnapshots: EventCellSnapshot[] = detail.events.map((e) => ({
      eventDefinitionOid: e.eventDefinitionOid,
      label: e.label,
      status: e.status as EventStatus,
      openQueries: e.openQueries,
    }))
    rows.value[idx] = {
      id: detail.id,
      secondaryId: detail.secondaryId,
      siteOid: detail.siteOid,
      siteLabel: detail.siteLabel,
      gender: detail.gender as Gender,
      yearOfBirth: detail.yearOfBirth,
      groupLabel: detail.groupLabel,
      enrolledOn: detail.enrolledOn,
      signed: detail.signed,
      openQueries: detail.openQueries,
      studyEye: detail.studyEye as StudyEye | null,
      status: detail.status ?? 'available',
      groupAssignments: detail.groupAssignments ?? [],
      events: eventSnapshots,
    }
  }

  /**
   * Phase E.4 M3 — fetch a single subject's detail by study_subject OID.
   *
   * Lookup is by the legacy `study_subject.oc_oid` (e.g. `SS_M001`),
   * NOT the label-style `M-001` identifier shown to the user. The
   * view passes the label and the store performs the `M-001 → SS_M001`
   * normalisation here — the legacy OID convention is to strip the
   * hyphen and prepend `SS_`, matching the seed data exactly.
   *
   * Hard-fails per the plan's mock-removal policy: on error,
   * `selected` is set to null and `selectedError` carries the
   * server message. The detail view renders the empty-state notice
   * rather than falling back to mock data.
   */
  async function fetchOne(subjectIdOrOid: string): Promise<SubjectDetail | null> {
    const oid = toStudySubjectOid(subjectIdOrOid)
    isLoadingSelected.value = true
    selectedError.value = null
    try {
      const detail = await apiGet<SubjectDetail>(`/pages/api/v1/subjects/${encodeURIComponent(oid)}`)
      selected.value = detail
      // Phase E.6 Y3 — reconcile the matching matrix row from the
      // detail payload. Both endpoints read the same source of truth
      // (study_event.subject_event_status_id, via mapSubjectEventStatus
      // server-side), but the matrix's `rows` is loaded once at mount
      // and never refreshed. Without this sync, a markComplete inside
      // CrfEntryView cascades the visit to COMPLETED → the detail
      // re-fetch on /subjects/:id#events flips the detail view's pill
      // to "Abgeschlossen", but the user navigating back to /subjects
      // still sees the pre-cascade "In Bearbeitung" / "Geplant" cells
      // from the stale snapshot. Projecting the detail's events down
      // to the matrix row keeps both views in sync without forcing a
      // second list refetch (which would also reset filter context).
      syncMatrixRowFromDetail(detail)
      return detail
    } catch (e) {
      selected.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        // Propagate so the router-level guard can redirect to /login
        // (401) or render a 403 error toast.
        throw e
      }
      if (e instanceof ApiNetworkError) {
        selectedError.value = 'Backend nicht erreichbar — bitte später erneut versuchen.'
        return null
      }
      if (e instanceof ApiError) {
        selectedError.value = e.message
        return null
      }
      selectedError.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden des Subjects.'
      return null
    } finally {
      isLoadingSelected.value = false
    }
  }

  /**
   * Phase E.4 M4 — enrol a new subject via the real backend.
   *
   * Calls `POST /pages/api/v1/subjects`. On 201 the backend returns the
   * new subject as a `SubjectDetail` (matrix fields + richer detail);
   * we project it down to a `Subject` shape and prepend it to `rows`
   * so the matrix updates without a refetch. On 400 with a structured
   * `errors` array we throw `AddSubjectValidationError` so the view
   * can surface per-field messages; on network / 5xx we throw a
   * generic Error.
   *
   * The backend owns identity / uniqueness / business validation
   * (DR-008); {@link validateAddSubject} is kept as instant client-side
   * UX feedback (used by AddSubjectView's `liveErrors`).
   */
  async function add(input: AddSubjectInput): Promise<Subject> {
    isLoading.value = true
    error.value = null
    try {
      // The backend infers site/study from the bound session — only the
      // user-supplied fields go over the wire. groupLabel is accepted
      // but ignored server-side in M4.
      const payload = {
        id: input.id?.trim() ?? '',
        secondaryId: input.secondaryId?.trim() || null,
        gender: input.gender,
        yearOfBirth: input.yearOfBirth ?? null,
        enrolledOn: input.enrolledOn,
        groupLabel: input.groupLabel ?? null,
        // Phase E.6 Tier 1 — ophthalmology domain fields.
        studyEye: input.studyEye ?? null,
        screeningDate: input.screeningDate?.trim() || null,
        // Phase E.6 subject-lifecycle — Person-ID re-enrol + initial
        // group-class picks. Both optional server-side; the SPA omits
        // them on the legacy enrolment path.
        personId: input.personId?.trim() || null,
        groupAssignments: input.groupAssignments ?? null,
        // Phase E.6 retrospective-backfill — PHI triplet (full DoB
        // wins over yearOfBirth when both present) + the
        // match-prompt acknowledgement when operator confirmed
        // create-new against a candidate.
        firstName: input.firstName?.trim() || null,
        lastName: input.lastName?.trim() || null,
        dateOfBirth: input.dateOfBirth?.trim() || null,
        acknowledgeMatchSubjectId: input.acknowledgeMatchSubjectId ?? null,
      }
      const detail = await apiPost<SubjectDetail>('/pages/api/v1/subjects', payload)

      // Project the detail DTO down to the leaner matrix `Subject`
      // shape. The matrix doesn't render studyOid/studyName or the
      // richer per-event metadata; trimming here keeps `rows` uniform
      // with what `load()` returns.
      const subject: Subject = {
        id: detail.id,
        secondaryId: detail.secondaryId,
        // The detail DTO carries the active study as siteOid/siteLabel
        // (M3 convention). Until per-site enrolment lands, keep the
        // SPA's matrix using the same value for both `site*` fields.
        siteOid: detail.siteOid,
        siteLabel: detail.siteLabel,
        gender: detail.gender as Gender,
        yearOfBirth: detail.yearOfBirth,
        groupLabel: detail.groupLabel,
        enrolledOn: detail.enrolledOn,
        signed: detail.signed,
        openQueries: detail.openQueries,
        // Phase E.6 Tier 1 — propagate study-eye scope to the matrix.
        studyEye: detail.studyEye as StudyEye | null,
        status: detail.status ?? 'available',
        groupAssignments: detail.groupAssignments ?? [],
        // No events scheduled yet — M11 will schedule them.
        events: [],
      }
      // Prepend so the just-created row is the first thing the user
      // sees when navigated back to the matrix.
      rows.value = [subject, ...rows.value]
      return subject
    } catch (e) {
      // 400 validation: backend returns { message, errors: [{field, message}] }
      if (e instanceof ApiError && e.status === 400 && hasFieldErrors(e.body)) {
        const fieldErrors = (e.body.errors as AddSubjectError[]).filter(isAddSubjectError)
        const message = e instanceof Error ? e.message : 'Validation failed'
        // Don't surface field-level validation as a generic toast — the
        // view consumes the structured error list and displays per-field
        // messages itself. Leave `error` null.
        throw new AddSubjectValidationError(message, fieldErrors)
      }
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        // Let the router-level auth guard handle these.
        throw e
      }
      if (e instanceof ApiNetworkError) {
        const msg = 'Backend nicht erreichbar — bitte später erneut versuchen.'
        error.value = msg
        throw new Error(msg)
      }
      if (e instanceof ApiError) {
        // 5xx, or 400 without structured errors body.
        error.value = e.message
        throw new Error(e.message)
      }
      const msg = e instanceof Error ? e.message : 'Unknown error adding subject'
      error.value = msg
      throw e
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Phase E.4 M8 — fetch the sign-preflight checks for a subject.
   *
   * Calls `GET /pages/api/v1/subjects/{oid}/preflightForSign` and
   * caches the result on `preflight`. The Sign Subject view consumes
   * `preflight.blockingFailures` to gate the submit button.
   *
   * Hard-fails on error (clears `preflight` to null) — no fallback to
   * a synthetic preflight, per the plan's mock-removal policy.
   */
  async function loadPreflight(subjectIdOrOid: string): Promise<SignPreflight | null> {
    const oid = toStudySubjectOid(subjectIdOrOid)
    isLoadingPreflight.value = true
    preflightError.value = null
    try {
      const pf = await apiGet<SignPreflight>(
        `/pages/api/v1/subjects/${encodeURIComponent(oid)}/preflightForSign`,
      )
      preflight.value = pf
      return pf
    } catch (e) {
      preflight.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        preflightError.value = 'Backend nicht erreichbar — bitte später erneut versuchen.'
        return null
      }
      if (e instanceof ApiError) {
        preflightError.value = e.message
        return null
      }
      preflightError.value =
        e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Preflight-Checks.'
      return null
    } finally {
      isLoadingPreflight.value = false
    }
  }

  /**
   * Phase E.4 M8 — submit the e-signature for a subject.
   *
   * Calls `POST /pages/api/v1/subjects/{oid}/sign` with the
   * password + attestation flag. On 200 the backend returns the
   * refreshed `SubjectDetail` with `signed: true` and every event
   * flipped to `signed`; we update both `selected` (the detail
   * view's source) and the matching row in `rows` (the matrix's
   * source) so the matrix shows the new state without a re-fetch.
   *
   * All error paths throw — the view surfaces the message inline.
   * - 401 → password mismatch (or the rare "lost authentication"
   *         defence path)
   * - 400 → attestation false or fields missing
   * - 409 → already signed
   * - 412 → preflight blocked the action
   * - 403 → cross-study
   *
   * SECURITY: the password is sent in the body but is NEVER
   * persisted anywhere on the SPA side. The function deliberately
   * does not log the password — even at debug level.
   */
  async function signSubject(
    subjectIdOrOid: string,
    password: string,
    attestation: boolean,
  ): Promise<SubjectDetail> {
    const oid = toStudySubjectOid(subjectIdOrOid)
    const detail = await apiPost<SubjectDetail>(
      `/pages/api/v1/subjects/${encodeURIComponent(oid)}/sign`,
      { password, attestation },
    )

    // Update the detail-view source so the page that called us flips
    // to "signed" without a manual re-fetch.
    selected.value = detail

    // Project the detail back down to the leaner matrix `Subject`
    // shape and overwrite the matching row (if loaded).
    syncMatrixRowFromDetail(detail)

    return detail
  }

  /**
   * Phase E A3 — soft-delete a subject. Backend
   * (`POST /api/v1/subjects/{oid}/remove`) cascades to nested events
   * + CRFs + item_data as AUTO_DELETED. Role-gated to Data Manager /
   * Administrator; the SPA hides the button for other roles.
   *
   * <p>On success the in-memory row is removed from `rows` (so the
   * matrix no longer shows it). The caller is responsible for
   * navigating away from `SubjectDetailView`.
   */
  async function removeSubject(subjectId: string): Promise<boolean> {
    try {
      await apiPost<unknown>(
        `/pages/api/v1/subjects/${encodeURIComponent(subjectId)}/remove`,
        {},
      )
      rows.value = rows.value.filter((s) => s.id !== subjectId)
      if (selected.value && selected.value.id === subjectId) {
        selected.value = null
      }
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Löschen nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Löschen fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Löschen fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Löschen.'
      }
      return false
    }
  }

  /**
   * Phase E A2 — edit subject demographics. The backend
   * (`PUT /api/v1/subjects/{oid}`) accepts a partial body where
   * `gender` is required but `secondaryId` / `yearOfBirth` may be
   * omitted (omitted = unchanged). Role-gated to Investigator / CRC /
   * Data Manager / Administrator; the SPA hides the edit button for
   * Monitor + RA roles.
   *
   * <p>On success the in-memory `selected` (SubjectDetail) is
   * replaced with the refreshed copy, and the matching matrix row
   * is updated in place.
   *
   * <p>Per-field validation errors come back as 400 with
   * `errors: [{ field, message }]` — the caller (form view)
   * extracts them. 401/403 re-thrown so the auth store can route.
   */
  async function updateSubject(
    subjectId: string,
    body: {
      secondaryId: string | null
      gender: 'F' | 'M' | 'O' | 'U'
      yearOfBirth: number | null
      /**
       * 2026-06-10 — null clears the study-eye scope; 'OD' / 'OS' /
       * 'OU' set it. Omit (undefined) when the caller doesn't surface
       * the field at all — the backend's null-preserves-current
       * convention applies in that case.
       */
      studyEye?: StudyEye | null
    },
  ): Promise<{ ok: true; detail: SubjectDetail }
              | { ok: false; fieldErrors: Record<string, string>; message?: string }> {
    try {
      const detail = await apiPut<SubjectDetail>(
        `/pages/api/v1/subjects/${encodeURIComponent(subjectId)}`,
        body,
      )
      selected.value = detail
      const idx = rows.value.findIndex((r) => r.id === subjectId)
      if (idx >= 0) {
        rows.value[idx] = {
          ...rows.value[idx],
          gender: detail.gender,
          secondaryId: detail.secondaryId,
          yearOfBirth: detail.yearOfBirth,
          studyEye: detail.studyEye,
        }
      }
      return { ok: true, detail }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Bearbeiten nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiError) {
        const errBody = e.body as
          | { message?: string; errors?: Array<{ field: string; message: string }> }
          | null
        const fieldErrors: Record<string, string> = {}
        if (errBody?.errors) {
          for (const fe of errBody.errors) {
            fieldErrors[fe.field] = fe.message
          }
        }
        return {
          ok: false,
          fieldErrors,
          message: errBody?.message ?? `Bearbeiten fehlgeschlagen (HTTP ${e.status}).`,
        }
      }
      if (e instanceof ApiNetworkError) {
        return {
          ok: false,
          fieldErrors: {},
          message:
            'Backend nicht erreichbar — Bearbeiten fehlgeschlagen. Bitte später erneut versuchen.',
        }
      }
      return {
        ok: false,
        fieldErrors: {},
        message: e instanceof Error ? e.message : 'Unbekannter Fehler beim Bearbeiten.',
      }
    }
  }

  /**
   * Phase E A3-lock — freeze a subject (status AVAILABLE → LOCKED).
   * The subject row stays in the matrix; downstream edit /
   * data-entry actions surface a "locked" warning. After success
   * `selected` is refreshed so the locked badge appears immediately.
   * DM / Admin only.
   */
  async function lockSubject(subjectId: string): Promise<boolean> {
    return _lifecycleAction(subjectId, 'lock')
  }

  /** Phase E A3-lock — inverse of {@link lockSubject}. */
  async function unlockSubject(subjectId: string): Promise<boolean> {
    return _lifecycleAction(subjectId, 'unlock')
  }

  async function _lifecycleAction(subjectId: string, op: 'lock' | 'unlock'): Promise<boolean> {
    try {
      const detail = await apiPost<SubjectDetail>(
        `/pages/api/v1/subjects/${encodeURIComponent(subjectId)}/${op}`,
        {},
      )
      if (selected.value && selected.value.id === subjectId) {
        selected.value = detail
      }
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `${op} nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          `Backend nicht erreichbar — ${op} fehlgeschlagen. Bitte später erneut versuchen.`
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `${op} fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value =
          e instanceof Error ? e.message : `Unbekannter Fehler beim ${op}.`
      }
      return false
    }
  }

  /**
   * Phase E.6 subject-lifecycle — toggle the Show-removed filter
   * and re-fetch the matrix. Wrapping the boolean flip + reload in
   * one action keeps the view code from having to remember the
   * order.
   */
  async function setShowRemoved(value: boolean) {
    if (showRemoved.value === value) return
    showRemoved.value = value
    await load()
  }

  /**
   * Phase E.6 subject-lifecycle — inverse of removeSubject. Restores
   * a soft-deleted subject + cascades the child rows back to
   * AVAILABLE. The view code uses this when the user clicks the
   * Restore button on a Show-removed row.
   *
   * On success: the in-memory matrix row's status flips to
   * 'available' and the selected detail (if matching) is cleared so
   * the next navigation fetches a fresh copy.
   */
  async function restoreSubject(subjectId: string): Promise<boolean> {
    try {
      await apiPost<unknown>(
        `/pages/api/v1/subjects/${encodeURIComponent(subjectId)}/restore`,
        {},
      )
      const idx = rows.value.findIndex((s) => s.id === subjectId)
      if (idx >= 0) {
        rows.value[idx] = { ...rows.value[idx], status: 'available' }
      }
      if (selected.value && selected.value.id === subjectId) {
        // Force a refetch on next navigation so the events / group
        // assignments come back fresh after the cascade.
        selected.value = null
      }
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Wiederherstellen nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Wiederherstellen fehlgeschlagen. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Wiederherstellen fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Wiederherstellen.'
      }
      return false
    }
  }

  /**
   * Phase E.6 subject-lifecycle — replace a subject's full
   * group-class assignment state. The SPA always sends the desired
   * final state (not deltas); the backend reconciles inserts +
   * soft-deletes + group switches in one call.
   *
   * On 200 the refreshed SubjectDetail replaces both `selected`
   * (if matching) and the matching matrix row's `groupAssignments`
   * cell so the SPA renders the new arms without a refetch.
   *
   * On 400 with structured field errors, the caller receives them
   * in the resolved tuple's `fieldErrors` map keyed by the
   * `assignments[<groupClassId>]` shape from the backend
   * ValidationErrorBody.
   */
  async function replaceGroups(
    subjectId: string,
    assignments: GroupAssignmentInput[],
  ): Promise<
    | { ok: true; detail: SubjectDetail }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }
  > {
    try {
      const detail = await apiPut<SubjectDetail>(
        `/pages/api/v1/subjects/${encodeURIComponent(subjectId)}/groups`,
        { assignments },
      )
      if (selected.value && selected.value.id === subjectId) {
        selected.value = detail
      }
      const idx = rows.value.findIndex((r) => r.id === subjectId)
      if (idx >= 0) {
        rows.value[idx] = {
          ...rows.value[idx],
          groupAssignments: detail.groupAssignments ?? [],
        }
      }
      return { ok: true, detail }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Zuweisung nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiError) {
        const errBody = e.body as
          | { message?: string; errors?: Array<{ field: string; message: string }> }
          | null
        const fieldErrors: Record<string, string> = {}
        if (errBody?.errors) {
          for (const fe of errBody.errors) {
            fieldErrors[fe.field] = fe.message
          }
        }
        return {
          ok: false,
          fieldErrors,
          message: errBody?.message ?? `Gruppenzuweisung fehlgeschlagen (HTTP ${e.status}).`,
        }
      }
      if (e instanceof ApiNetworkError) {
        return {
          ok: false,
          fieldErrors: {},
          message:
            'Backend nicht erreichbar — Gruppenzuweisung fehlgeschlagen. Bitte später erneut versuchen.',
        }
      }
      return {
        ok: false,
        fieldErrors: {},
        message: e instanceof Error ? e.message : 'Unbekannter Fehler bei der Gruppenzuweisung.',
      }
    }
  }

  /**
   * Phase E.6 per-eye cohort transition workflow — POST a
   * single-eye downgrade / upgrade transition to the backend.
   *
   * The active-study subject is the source side; the backend
   * resolves (or creates) the matching target subject in the
   * partner study per the clinical rules:
   *   1. OU sources downgrade to the partner eye on the source
   *      row; the transitioned eye lives on a new target row.
   *   2. Single-eye sources are stubbed to NULL on the source
   *      row when their lone eye transitions out.
   *   3. Bilateral GA: a second eye progressing into an existing
   *      target row upgrades OD→OU on that row (no new row).
   *
   * On success the store re-fetches the subject detail so both the
   * source banner (this view) and `eyeTransitions` rendering reflect
   * the new cross-reference without a manual refresh. Returns the
   * parsed response body for callers (the dialog uses it for the
   * success toast text — partner label, partner study, etc.).
   *
   * 4xx / 5xx are rethrown verbatim so the calling dialog can
   * surface the error inline without competing with the store's
   * banner-level error ref.
   */
  async function transitionEye(
    label: string,
    eye: 'OD' | 'OS',
    body: TransitionEyeRequest,
  ): Promise<unknown> {
    const response = await apiPost<unknown>(
      `/pages/api/v1/subjects/${encodeURIComponent(label)}/eyes/${eye}/transition`,
      body,
    )
    // Re-fetch the subject detail so eyeTransitions on the active-
    // study subject reflects the brand-new source-side cross-
    // reference. The detail endpoint is authoritative; no need to
    // push the response shape onto selected.value manually.
    await fetchOne(label)
    return response
  }

  /**
   * 2026-06-10 — preflight the TransitionEyeDialog calls when the
   * operator picks a target study (and again, debounced, while typing
   * a candidate target-study label).
   *
   * <p>Two questions in one call:
   * <ul>
   *   <li>Is the subject already enrolled in the target study? Drives
   *       the dialog's "Patient ist bereits angelegt" info line + lets
   *       the dialog drop the targetLabel input.</li>
   *   <li>If a candidate {@code targetLabel} was passed, is it free in
   *       the target study? Surfaces inline beneath the input.</li>
   * </ul>
   *
   * <p>Failures bubble — the dialog treats network errors as
   * "unknown / let backend gate at submit" so a transient hiccup
   * never blocks the transition flow.
   */
  async function transitionPreflight(
    label: string,
    eye: 'OD' | 'OS',
    targetStudyOid: string,
    targetLabel: string | null,
  ): Promise<TransitionPreflight> {
    const params = new URLSearchParams({ targetStudyOid })
    if (targetLabel !== null && targetLabel !== '') {
      params.set('targetLabel', targetLabel)
    }
    return apiGet<TransitionPreflight>(
      `/pages/api/v1/subjects/${encodeURIComponent(label)}/eyes/${eye}`
        + `/transition/preflight?${params.toString()}`,
    )
  }

  /**
   * Phase E.6 retrospective-backfill — duplicate-patient lookup.
   *
   * <p>Fires from the AddSubject form once the operator has filled
   * first name + last name + DoB. The backend returns the list of
   * matching subjects (exact, case-insensitive); the SPA renders a
   * dialog when the list is non-empty so the operator can either
   * link to an existing subject or confirm "different person".
   *
   * <p>The endpoint is read-only; failures bubble up as ApiError
   * (rare — 5xx from a DB issue) and the form treats "no candidates"
   * as the success path so a backend hiccup never blocks enrolment.
   */
  async function preflightMatch(
    firstName: string,
    lastName: string,
    dateOfBirth: string,
  ): Promise<SubjectMatchCandidate[]> {
    return apiPost<SubjectMatchCandidate[]>(
      '/pages/api/v1/subjects/match-preflight',
      { firstName, lastName, dateOfBirth },
    )
  }

  /**
   * Phase E.6 — live Study-Subject-ID availability check.
   *
   * <p>Fires from the AddSubject form on debounced input of the
   * Study Subject ID field; surfaces "already taken" inline before
   * the operator clicks submit (the backend's submit-time check
   * still fires as the authoritative gate, but the live check
   * trades a 4xx round-trip for instant feedback).
   *
   * <p>Failures bubble — the form treats network errors as
   * "unknown / pass through to submit" so a transient backend
   * hiccup never blocks enrolment.
   */
  async function checkLabelAvailability(
    label: string,
  ): Promise<SubjectLabelAvailability> {
    return apiGet<SubjectLabelAvailability>(
      `/pages/api/v1/subjects/check-label?label=${encodeURIComponent(label)}`,
    )
  }

  /**
   * Phase E.6 — clear every piece of study-scoped state so the store
   * doesn't bleed subjects from study A into the matrix after the
   * user switches to study B. Called by {@link useAuthStore.pickStudy}
   * before re-bootstrapping. Filters reset too — keeping a "signed
   * subjects only" filter active across study switches confuses the
   * empty-state read.
   */
  function reset() {
    rows.value = []
    isLoading.value = false
    error.value = null
    selected.value = null
    isLoadingSelected.value = false
    selectedError.value = null
    preflight.value = null
    isLoadingPreflight.value = false
    preflightError.value = null
    query.value = ''
    statusFilter.value = 'all'
    onlyWithQueries.value = false
    showRemoved.value = false
  }

  return {
    // state
    rows,
    isLoading,
    error,
    query,
    statusFilter,
    onlyWithQueries,
    showRemoved,
    selected,
    isLoadingSelected,
    selectedError,
    preflight,
    isLoadingPreflight,
    preflightError,
    // derived
    filtered,
    totalCount,
    visibleCount,
    // actions
    load,
    clearFilters,
    add,
    fetchOne,
    loadPreflight,
    signSubject,
    removeSubject,
    restoreSubject,
    setShowRemoved,
    replaceGroups,
    updateSubject,
    lockSubject,
    unlockSubject,
    transitionEye,
    transitionPreflight,
    preflightMatch,
    checkLabelAvailability,
    reset,
  }
})

/**
 * Phase E.6 retrospective-backfill — single match-preflight result.
 *
 * Mirrors the backend `SubjectMatchCandidate` record. `studies` lists
 * the operator-visible enrolments with the per-study subject label
 * ("this patient is M-001 in default-study, GA-008 in GA-Studie").
 * `otherStudyCount` carries the count of additional enrolments in
 * studies the operator can't see — surfaced so the dialog can show
 * "Bekannt in {n} weiteren Studien (kein Zugriff)" without leaking
 * the study identities.
 *
 * Phase E.6 follow-up 2026-06-10 — added {@link firstName} +
 * {@link lastName} (operator-confirmation aid: the operator typed
 * these on the form, so echoing the persisted spelling sanity-checks
 * the match), and replaced the bare {@link studyOids} list with the
 * richer {@link studies} shape. {@link studyOids} stays for a brief
 * transition window because the backend still emits it as a derived
 * view.
 */
export interface SubjectMatchCandidate {
  subjectId: number
  uniqueIdentifier: string | null
  gender: string | null
  dateOfBirth: string | null
  /** Phase E.6 follow-up 2026-06-10 — operator-confirmation aid. */
  firstName: string | null
  /** Phase E.6 follow-up 2026-06-10 — operator-confirmation aid. */
  lastName: string | null
  /** Phase E.6 follow-up 2026-06-10 — visible enrolments + per-study label. */
  studies: PatientMatchStudyEnrollment[]
  otherStudyCount: number
}

/**
 * Phase E.6 follow-up 2026-06-10 — one visible enrolment surfaced by
 * the match-preflight endpoint. {@link studyUniqueIdentifier} is the
 * operator-facing protocol short-code ({@code default-study}); the
 * {@link studyOid} carries the system OID for navigation; {@link label}
 * is the operator-typed per-study subject-id ({@code M-001}).
 */
export interface PatientMatchStudyEnrollment {
  studyUniqueIdentifier: string
  studyOid: string
  studyName: string
  label: string
}

/**
 * Phase E.6 — response shape for `GET /api/v1/subjects/check-label`.
 *
 * <p>{@code available} is the operator-facing answer: true → safe
 * to submit, false → the typed label is already taken in the bound
 * study. {@code existingSubjectOid} carries the colliding row's OID
 * when {@code available=false} so the SPA can later surface an
 * "Open existing" affordance.
 */
export interface SubjectLabelAvailability {
  available: boolean
  existingSubjectOid: string | null
}

/**
 * Convert a SPA subject identifier (the human-readable `M-001`-style
 * label OR a raw `SS_M001`-style OID) to the legacy
 * `study_subject.oc_oid` value the backend's M3 lookup keys on.
 *
 * Convention from the demo seed: label `M-001` → oid `SS_M001`,
 * label `M-007` → oid `SS_M007`. The legacy `AddNewSubjectServlet`
 * auto-generates OIDs from labels via `SS_` + sanitized-label, so
 * this stripping rule mirrors what the backend would have written.
 *
 * If the caller already passes an OID (starts with `SS_`), it's
 * returned unchanged. This lets the SPA accept either form.
 */
function toStudySubjectOid(idOrOid: string): string {
  if (idOrOid.startsWith('SS_')) return idOrOid
  return 'SS_' + idOrOid.replace(/[^A-Za-z0-9]/g, '')
}

/** Form payload from the Add Subject view. */
export interface AddSubjectInput {
  id: string
  secondaryId?: string | null
  siteOid: string
  siteLabel: string
  gender: Gender
  yearOfBirth?: number | null
  groupLabel?: string | null
  /** ISO `YYYY-MM-DD`. */
  enrolledOn: string
  /**
   * Phase E.6 Tier 1 — ophthalmology study-eye scope. Optional;
   * non-ophth studies leave this blank. One of OD / OS / OU.
   */
  studyEye?: StudyEye | null
  /**
   * Phase E.6 Tier 1 — eligibility-screening date (ISO YYYY-MM-DD).
   * Optional; some MUW deployments don't run a separate screening.
   */
  screeningDate?: string | null
  /**
   * Phase E.6 subject-lifecycle — Person-ID (subject.unique_identifier).
   * When supplied and a matching subject exists in the tree, the new
   * enrolment reuses the existing subject_id (one human, many
   * studies). When omitted, a fresh subject row is created.
   */
  personId?: string | null
  /**
   * Phase E.6 subject-lifecycle — initial group-class picks. Same
   * shape as the PUT /groups body; sent in the same POST as the
   * enrolment so the SPA doesn't need a second round trip.
   */
  groupAssignments?: GroupAssignmentInput[] | null
  /**
   * Phase E.6 retrospective-backfill — patient identity captured on
   * the AddSubject form. `dateOfBirth` is the canonical DoB (ISO
   * `YYYY-MM-DD`); when present the backend uses it directly and
   * flips `dob_collected` to true. Legacy callers that only have a
   * year still post `yearOfBirth` for back-compat.
   */
  firstName?: string | null
  lastName?: string | null
  dateOfBirth?: string | null
  /**
   * Set when the operator clicked "Different person, create new" on
   * a match-preflight dialog. Carries the subject_id(s) of the
   * candidates they explicitly rejected so the backend can audit
   * the override.
   */
  acknowledgeMatchSubjectId?: number | null
}

export type AddSubjectErrorField =
  | 'id'
  | 'secondaryId'
  | 'enrolledOn'
  | 'gender'
  | 'yearOfBirth'
  | 'personId'
  | 'firstName'
  | 'lastName'
  | 'dateOfBirth'

export interface AddSubjectError {
  field: AddSubjectErrorField
  message: string
}

export class AddSubjectValidationError extends Error {
  constructor(message: string, public readonly errors: AddSubjectError[]) {
    super(message)
    this.name = 'AddSubjectValidationError'
  }
}

/** Server-side AddSubject error envelope. */
interface AddSubjectErrorsBody {
  message?: string
  errors: AddSubjectError[]
}

function hasFieldErrors(body: unknown): body is AddSubjectErrorsBody {
  return (
    body != null &&
    typeof body === 'object' &&
    Array.isArray((body as { errors?: unknown }).errors)
  )
}

const allowedErrorFields: ReadonlyArray<AddSubjectErrorField> = [
  'id',
  'secondaryId',
  'enrolledOn',
  'gender',
  'yearOfBirth',
  'personId',
]

function isAddSubjectError(value: unknown): value is AddSubjectError {
  if (value == null || typeof value !== 'object') return false
  const v = value as { field?: unknown; message?: unknown }
  return (
    typeof v.field === 'string' &&
    (allowedErrorFields as ReadonlyArray<string>).includes(v.field) &&
    typeof v.message === 'string'
  )
}

/**
 * Local validation that mirrors the `/AddNewSubject` servlet's checks:
 *  - Subject ID required + unique within the site.
 *  - Secondary ID may not contain obvious PHI tokens (the legacy
 *    servlet leaves this to a study-config rule; we keep a soft
 *    client-side check matching the mockup's red ErrorText).
 *  - Enrolment date must be present + not in the future.
 *  - Gender must be one of the allowed codes.
 *  - Year of birth must be plausible (1900–current year).
 *
 * The backend remains authoritative — the SPA's validation is for UX
 * feedback only. Per DR-008 the server-side rules engine is still the
 * source of truth.
 */
export function validateAddSubject(
  input: AddSubjectInput,
  existing: ReadonlyArray<Subject>,
  options: { today?: string } = {},
): AddSubjectError[] {
  const errors: AddSubjectError[] = []
  const today = options.today ?? new Date().toISOString().slice(0, 10)

  const id = input.id?.trim() ?? ''
  if (!id) errors.push({ field: 'id', message: 'Subject ID is required.' })
  else if (id.length > 30) errors.push({ field: 'id', message: 'Subject ID is too long (max 30 characters).' })
  else if (existing.some((s) => s.id.toLowerCase() === id.toLowerCase()))
    errors.push({ field: 'id', message: `Subject ID "${id}" already exists at this site.` })

  const secondary = input.secondaryId?.trim() ?? ''
  if (secondary && /[0-9]{8,}/.test(secondary))
    errors.push({ field: 'secondaryId', message: 'Secondary ID must not contain an 8+ digit run — risks PHI exposure.' })

  if (!input.enrolledOn) errors.push({ field: 'enrolledOn', message: 'Enrolment date is required.' })
  else if (input.enrolledOn > today)
    errors.push({ field: 'enrolledOn', message: 'Enrolment date must not be in the future.' })

  if (!input.gender) errors.push({ field: 'gender', message: 'Gender is required.' })
  else if (!(['F', 'M', 'O', 'U'] as const).includes(input.gender))
    errors.push({ field: 'gender', message: `"${input.gender}" is not a valid gender code.` })

  if (input.yearOfBirth != null) {
    const year = Number(input.yearOfBirth)
    const thisYear = Number(today.slice(0, 4))
    if (!Number.isInteger(year) || year < 1900 || year > thisYear)
      errors.push({ field: 'yearOfBirth', message: `Year of birth must be between 1900 and ${thisYear}.` })
  }

  return errors
}

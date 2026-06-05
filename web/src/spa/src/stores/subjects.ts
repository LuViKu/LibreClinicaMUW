import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  EventCellSnapshot,
  EventStatus,
  Gender,
  SignPreflight,
  Subject,
  SubjectDetail,
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
      rows.value = await apiGet<Subject[]>('/pages/api/v1/subjects')
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

    // Update the matrix row (if loaded). We project the detail DTO
    // back down to the leaner matrix `Subject` shape — events get
    // re-mapped to `EventCellSnapshot` (drops dateStart, dateEnd,
    // location, dataEntryStage which the matrix doesn't render).
    const idx = rows.value.findIndex((r) => r.id === detail.id)
    if (idx >= 0) {
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
        events: eventSnapshots,
      }
    }

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
    body: { secondaryId: string | null; gender: 'F' | 'M' | 'O' | 'U'; yearOfBirth: number | null },
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
  }

  return {
    // state
    rows,
    isLoading,
    error,
    query,
    statusFilter,
    onlyWithQueries,
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
    updateSubject,
    lockSubject,
    unlockSubject,
    reset,
  }
})

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
}

export type AddSubjectErrorField =
  | 'id'
  | 'secondaryId'
  | 'enrolledOn'
  | 'gender'
  | 'yearOfBirth'

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

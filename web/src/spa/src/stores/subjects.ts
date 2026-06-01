import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { Gender, Subject, SubjectDetail } from '@/types/subject'

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
   * Phase E.5.2 — enrol a new subject (optimistic local append).
   *
   * TODO(E.4 M4): replace with `apiPost<Subject>('/pages/api/v1/subjects', input)`
   * and remove the local id-generation / optimistic-append branch — the
   * backend will own id allocation, secondary-id uniqueness, and the
   * authoritative validation.
   *
   * Until then the form's validation is the SPA's local
   * {@link validateAddSubject}; we append a Subject with empty events
   * so the matrix immediately reflects the new row.
   */
  async function add(input: AddSubjectInput): Promise<Subject> {
    const errors = validateAddSubject(input, rows.value)
    if (errors.length > 0) {
      const message = errors.map((e) => e.message).join('; ')
      throw new AddSubjectValidationError(message, errors)
    }

    isLoading.value = true
    error.value = null
    try {
      // TODO(E.4 M4): apiPost<Subject>('/pages/api/v1/subjects', input).
      await new Promise((resolve) => setTimeout(resolve, 30))

      const subject: Subject = {
        id: input.id.trim(),
        secondaryId: input.secondaryId?.trim() || null,
        siteOid: input.siteOid,
        siteLabel: input.siteLabel,
        gender: input.gender,
        yearOfBirth: input.yearOfBirth ?? null,
        groupLabel: input.groupLabel ?? null,
        enrolledOn: input.enrolledOn,
        signed: false,
        openQueries: 0,
        // No events yet — the legacy /AddNewSubject flow only creates the
        // study_subject row; visits are scheduled separately. The matrix
        // shows the new row immediately with an empty event grid.
        events: [],
      }
      rows.value = [...rows.value, subject]
      return subject
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Unknown error adding subject'
      error.value = msg
      throw e
    } finally {
      isLoading.value = false
    }
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
    // derived
    filtered,
    totalCount,
    visibleCount,
    // actions
    load,
    clearFilters,
    add,
    fetchOne,
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

/**
 * Wave 2B — PatientSearchModal spec.
 *
 * Pins the load-bearing contract:
 *  - "Mindestens 2 Zeichen" empty state when query < 2 chars.
 *  - Debounced fetch fires after 300 ms with the query.
 *  - Results render with study + site context.
 *  - Clicking a result emits {@code subject-picked} with the full hit.
 *  - "Abbrechen" closes the modal (emits {@code close}).
 *  - Backend error surfaces as the error banner.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import deMessages from '@/locales/de.json'

// Stub the retinal API module at the import boundary — the component
// calls `searchStudySubjects` to hit Wave 1B's search endpoint.
vi.mock('@/api/retinal', () => ({
  searchStudySubjects: vi.fn(),
}))

// The portal variant calls the anonymous endpoint instead — see the
// `publicContext` prop. Stubbed at the same boundary.
// DR-029 — the public lookup lives on the combined upload page's client now.
vi.mock('@/api/uploadWorkbench', () => ({
  searchPatientsPublic: vi.fn(),
}))

import PatientSearchModal from '../PatientSearchModal.vue'
import { searchStudySubjects, type StudySubjectSearchHit } from '@/api/retinal'
import { searchPatientsPublic } from '@/api/uploadWorkbench'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

const HIT: StudySubjectSearchHit = {
  studySubjectId: 42,
  label: 'GA-014',
  studyId: 7,
  studyName: 'GA-Studie',
  siteName: 'Wien-AKH',
}

function mountModal(
  props: { open: boolean; initialQuery?: string; publicContext?: boolean } = { open: true },
) {
  return mount(PatientSearchModal, {
    props,
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('PatientSearchModal', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(searchStudySubjects).mockReset()
    vi.mocked(searchPatientsPublic).mockReset()
  })
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('shows "Mindestens 2 Zeichen" while the query is too short', async () => {
    const w = mountModal({ open: true })
    await flushPromises()
    expect(document.body.textContent ?? '').toContain('Mindestens 2 Zeichen')
    expect(searchStudySubjects).not.toHaveBeenCalled()
  })

  it('debounces the search 300 ms then calls searchStudySubjects with the query', async () => {
    vi.mocked(searchStudySubjects).mockResolvedValue([HIT])
    const w = mountModal({ open: true })
    await flushPromises()

    const input = document.querySelector<HTMLInputElement>('[data-testid="patient-search-input"]')
    expect(input).not.toBeNull()
    input!.value = 'GA'
    input!.dispatchEvent(new Event('input'))
    await flushPromises()

    // No call yet — within the debounce window.
    expect(searchStudySubjects).not.toHaveBeenCalled()

    // Advance just past the debounce.
    await vi.advanceTimersByTimeAsync(310)
    await flushPromises()

    expect(searchStudySubjects).toHaveBeenCalledTimes(1)
    expect(searchStudySubjects).toHaveBeenCalledWith('GA', 10)
  })

  it('renders results with study + site context after a successful search', async () => {
    vi.mocked(searchStudySubjects).mockResolvedValue([HIT])
    const w = mountModal({ open: true, initialQuery: 'GA-0' })
    await flushPromises()
    // Initial query >=2 chars → modal kicks off a search itself.
    await vi.advanceTimersByTimeAsync(310)
    await flushPromises()

    const text = document.body.textContent ?? ''
    expect(text).toContain('GA-014')
    expect(text).toContain('GA-Studie')
    expect(text).toContain('Wien-AKH')
  })

  it('clicking a result emits subject-picked with the full hit', async () => {
    vi.mocked(searchStudySubjects).mockResolvedValue([HIT])
    const w = mountModal({ open: true, initialQuery: 'GA' })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(310)
    await flushPromises()

    const row = document.querySelector<HTMLButtonElement>(
      `[data-testid="patient-search-result-${HIT.studySubjectId}"]`,
    )
    expect(row).not.toBeNull()
    row!.click()
    await flushPromises()

    const emitted = w.emitted('subject-picked')
    expect(emitted).toBeTruthy()
    expect(emitted![0]).toEqual([HIT])
  })

  it('Abbrechen emits close', async () => {
    const w = mountModal({ open: true })
    await flushPromises()
    const cancel = document.querySelector<HTMLButtonElement>('[data-testid="patient-search-cancel"]')
    expect(cancel).not.toBeNull()
    cancel!.click()
    await flushPromises()
    expect(w.emitted('close')).toBeTruthy()
  })

  it('surfaces backend errors via the error banner', async () => {
    vi.mocked(searchStudySubjects).mockRejectedValue(new Error('boom'))
    const w = mountModal({ open: true, initialQuery: 'GA' })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(310)
    await flushPromises()

    const errBanner = document.querySelector<HTMLElement>('[data-testid="patient-search-error"]')
    expect(errBanner).not.toBeNull()
    expect(errBanner!.textContent ?? '').toContain('boom')
  })
})

describe('PatientSearchModal — publicContext (unauthenticated portals)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(searchStudySubjects).mockReset()
    vi.mocked(searchPatientsPublic).mockReset()
  })
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  async function typeAndSettle(query: string): Promise<void> {
    const input = document.querySelector<HTMLInputElement>('[data-testid="patient-search-input"]')
    expect(input).not.toBeNull()
    input!.value = query
    input!.dispatchEvent(new Event('input'))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(310)
    await flushPromises()
  }

  /**
   * The regression this prop exists for: on the upload portals the modal used
   * to call the session-gated staff endpoint, which answers 401 for an
   * anonymous operator, so the dialog stayed empty with no explanation.
   */
  it('queries the public endpoint and never the session-gated one', async () => {
    vi.mocked(searchPatientsPublic).mockResolvedValue([
      { studySubjectId: 42, label: 'GA-014', studyName: 'GA-Studie', siteName: 'Wien-AKH' },
    ])
    mount(PatientSearchModal, {
      props: { open: true, publicContext: true },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    await typeAndSettle('GA-0')

    expect(searchPatientsPublic).toHaveBeenCalledTimes(1)
    expect(searchPatientsPublic).toHaveBeenCalledWith('GA-0', 10)
    expect(searchStudySubjects).not.toHaveBeenCalled()
    expect(document.body.textContent ?? '').toContain('GA-014')
  })

  /** The public endpoint rejects prefixes under three characters, so don't send them. */
  it('does not call the public endpoint for a two-character prefix', async () => {
    mount(PatientSearchModal, {
      props: { open: true, publicContext: true },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    await typeAndSettle('GA')

    expect(searchPatientsPublic).not.toHaveBeenCalled()
  })

  /** Without the flag the staff endpoint is still the one used. */
  it('keeps using the staff endpoint when publicContext is not set', async () => {
    vi.mocked(searchStudySubjects).mockResolvedValue([HIT])
    mount(PatientSearchModal, {
      props: { open: true },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    await typeAndSettle('GA')

    expect(searchStudySubjects).toHaveBeenCalledTimes(1)
    expect(searchPatientsPublic).not.toHaveBeenCalled()
  })
})

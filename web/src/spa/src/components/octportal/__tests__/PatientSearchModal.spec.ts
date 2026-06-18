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

import PatientSearchModal from '../PatientSearchModal.vue'
import { searchStudySubjects, type StudySubjectSearchHit } from '@/api/retinal'

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

function mountModal(props: { open: boolean; initialQuery?: string } = { open: true }) {
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

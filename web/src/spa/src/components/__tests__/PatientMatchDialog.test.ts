/**
 * Phase E.6 follow-up 2026-06-10 — PatientMatchDialog specs.
 *
 * <p>Pins the operator-confirmation aids added on top of the existing
 * dedup-preflight dialog:
 *
 * <ul>
 *   <li>Renders the candidate's persisted name in surname-first form
 *       ("Schmidt, Anna") so the operator can sanity-check the match
 *       against what they just typed on the AddSubject form.</li>
 *   <li>Renders the per-study label cross-reference ("default-study
 *       (M-001), GA-Studie (GA-008)") so the operator can confirm the
 *       human's identity across studies, not just the study OIDs.</li>
 *   <li>Formats the DoB in German clinical convention (DD.MM.YYYY)
 *       — the SPA's other date-display call sites use the same
 *       format; centralizing would be premature for one consumer.</li>
 * </ul>
 *
 * <p>i18n keys are loaded with `missingWarn: false` so any parallel
 * worktree's evolution of the locale tree doesn't generate console
 * noise during local runs.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import PatientMatchDialog from '@/components/PatientMatchDialog.vue'
import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'
import type { SubjectMatchCandidate } from '@/stores/subjects'

function makeI18n(locale: 'en' | 'de' = 'en') {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    missingWarn: false,
    fallbackWarn: false,
    messages: { en: enMessages, de: deMessages },
  })
}

const CANDIDATE_FULL: SubjectMatchCandidate = {
  subjectId: 1,
  uniqueIdentifier: 'P-12345',
  gender: 'f',
  dateOfBirth: '1970-03-15',
  firstName: 'Anna',
  lastName: 'Schmidt',
  studies: [
    {
      studyUniqueIdentifier: 'default-study',
      studyOid: 'S_DEFAULTS1',
      studyName: 'Default Study',
      label: 'M-001',
    },
    {
      studyUniqueIdentifier: 'GA',
      studyOid: 'S_GA1',
      studyName: 'GA-Studie',
      label: 'GA-008',
    },
  ],
  otherStudyCount: 0,
}

function mountDialog(
  candidates: SubjectMatchCandidate[],
  locale: 'en' | 'de' = 'en',
) {
  return mount(PatientMatchDialog, {
    props: { open: true, candidates },
    global: { plugins: [makeI18n(locale)] },
    attachTo: document.body,
  })
}

describe('PatientMatchDialog', () => {
  it('renders the candidate name in surname-first convention', () => {
    const wrapper = mountDialog([CANDIDATE_FULL])
    const nameLine = wrapper.find('[data-testid="patient-match-name-1"]')
    expect(nameLine.exists()).toBe(true)
    expect(nameLine.text()).toBe('Schmidt, Anna')
    wrapper.unmount()
  })

  it('falls back to the "name unknown" placeholder when both halves are null', () => {
    const wrapper = mountDialog([
      { ...CANDIDATE_FULL, firstName: null, lastName: null },
    ])
    const nameLine = wrapper.find('[data-testid="patient-match-name-1"]')
    expect(nameLine.text()).toBe('Name unknown')
    wrapper.unmount()
  })

  it('formats multiple visible enrolments as "{short-code} ({label})"', () => {
    const wrapper = mountDialog([CANDIDATE_FULL])
    const studiesLine = wrapper.find('[data-testid="patient-match-studies-1"]')
    // The full string comes from the "studiesVisible" template which
    // wraps the list with "Active in: ". Assert the formatted list
    // is present and ordered as supplied.
    expect(studiesLine.text()).toContain('default-study (M-001)')
    expect(studiesLine.text()).toContain('GA (GA-008)')
    expect(studiesLine.text()).toMatch(/default-study \(M-001\), GA \(GA-008\)/)
    wrapper.unmount()
  })

  it('appends the "+ N more without access" suffix when otherStudyCount > 0', () => {
    const wrapper = mountDialog([
      { ...CANDIDATE_FULL, otherStudyCount: 2 },
    ])
    const studiesLine = wrapper.find('[data-testid="patient-match-studies-1"]')
    expect(studiesLine.text()).toMatch(
      /default-study \(M-001\), GA \(GA-008\) \(\+ 2 more without access\)/,
    )
    wrapper.unmount()
  })

  it('renders DoB as DD.MM.YYYY (German clinical convention)', () => {
    const wrapper = mountDialog([CANDIDATE_FULL])
    // The DoB is one segment of the identifier line; assert its
    // formatted form appears verbatim. ISO 1970-03-15 → 15.03.1970.
    expect(wrapper.text()).toContain('15.03.1970')
    // Defensive — the raw ISO should NOT leak through.
    expect(wrapper.text()).not.toContain('1970-03-15')
    wrapper.unmount()
  })

  it('renders DoB placeholder ("—") when dateOfBirth is null', () => {
    const wrapper = mountDialog([
      { ...CANDIDATE_FULL, dateOfBirth: null },
    ])
    expect(wrapper.text()).toContain('—')
    wrapper.unmount()
  })

})

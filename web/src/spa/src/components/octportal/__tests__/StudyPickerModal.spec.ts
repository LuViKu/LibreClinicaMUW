/**
 * Wave 2C follow-up (2026-06-19) — StudyPickerModal spec.
 *
 * Locks down the disambiguation picker:
 *  - renders one row per candidate with the studyName + subjectLabel
 *  - tags candidates with vs without matchingEvent
 *  - emits study-picked on row click with the candidate verbatim
 *  - cancel button emits close
 *
 * <p>Teleport-to-body means {@code wrapper.find/text} can't see the
 * modal DOM — assert via {@code document.body} the same way the
 * VisitPickerModal spec does.
 */
import { describe, expect, it, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import StudyPickerModal from '../StudyPickerModal.vue'
import deMessages from '@/locales/de.json'
import type { ResolveCandidate } from '@/api/octPortal'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

const WITH_EVENT: ResolveCandidate = {
  studyId: 1,
  studyName: 'Default Study',
  studyOid: 'S_DEFAULT',
  studySubjectId: 9,
  subjectLabel: 'EIAMD139',
  siteName: null,
  matchingEvent: {
    eventCrfId: 18,
    definitionLabel: 'Baseline',
    dateStart: '2026-06-17',
    matchPolicy: 'same-day',
  },
}

const WITHOUT_EVENT: ResolveCandidate = {
  studyId: 102,
  studyName: 'RIS Demo',
  studyOid: 'S_RIS',
  studySubjectId: 102,
  subjectLabel: 'EIAMD139',
  siteName: 'Wien-Vienna',
  matchingEvent: null,
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('StudyPickerModal', () => {
  it('renders one row per candidate with studyName + subject label', () => {
    mount(StudyPickerModal, {
      global: { plugins: [i18n] },
      attachTo: document.body,
      props: {
        open: true,
        candidates: [WITH_EVENT, WITHOUT_EVENT],
        patientId: 'EIAMD139',
      },
    })
    const text = document.body.textContent ?? ''
    expect(text).toContain('Default Study')
    expect(text).toContain('RIS Demo')
    expect(text).toContain('EIAMD139')
    expect(text).toContain('Visite gefunden')
    expect(text).toContain('Keine Visite am Scan-Datum')
    expect(text).toContain('Wien-Vienna')
  })

  it('emits study-picked with the chosen candidate on row click', async () => {
    const w = mount(StudyPickerModal, {
      global: { plugins: [i18n] },
      attachTo: document.body,
      props: {
        open: true,
        candidates: [WITH_EVENT, WITHOUT_EVENT],
        patientId: 'EIAMD139',
      },
    })
    const row = document.body.querySelector(
      `[data-testid="study-picker-result-${WITH_EVENT.studySubjectId}"]`,
    ) as HTMLButtonElement | null
    expect(row).not.toBeNull()
    row!.click()
    const emitted = w.emitted('study-picked')
    expect(emitted).toBeDefined()
    expect(emitted![0][0]).toEqual(WITH_EVENT)
  })

  it('cancel button emits close', async () => {
    const w = mount(StudyPickerModal, {
      global: { plugins: [i18n] },
      attachTo: document.body,
      props: {
        open: true,
        candidates: [WITH_EVENT],
        patientId: 'EIAMD139',
      },
    })
    const cancel = document.body.querySelector(
      '[data-testid="study-picker-cancel"]',
    ) as HTMLButtonElement | null
    expect(cancel).not.toBeNull()
    cancel!.click()
    expect(w.emitted('close')).toBeDefined()
  })
})

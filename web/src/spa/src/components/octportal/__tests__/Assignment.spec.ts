/**
 * Phase E retinal-inference (Wave C) — Assignment spec.
 *
 * Locks down the per-state action buttons + the German strings the
 * mockup specifies. We avoid full DOM snapshots (they churn on
 * unrelated whitespace) and instead assert on:
 *   - which action button(s) render for each row state
 *   - the visible German label on each action
 *   - the emit fires with the right rowId
 *
 * The five "meaningful" review states each get a stanza:
 *   suggested → Bestätigen + ändern
 *   confirmed → Rückgängig
 *   novisit   → Visite wählen + Später zuordnen
 *   nopatient → Patient suchen + Parken
 *   error     → red ✕ dismiss
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import Assignment from '../Assignment.vue'
import deMessages from '@/locales/de.json'
import type { ReviewRow } from '@/stores/octPortal'
import type { E2eScan } from '@/lib/e2eParser'
import type { EventCandidate, ResolveCandidate } from '@/api/octPortal'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function makeFile(name: string): File {
  return new File([new Uint8Array(8)], name, { type: 'application/octet-stream' })
}

const SCAN: E2eScan = {
  patientId: 'GA-014',
  scanDate: new Date('2026-06-17T09:12:00Z'),
  laterality: 'OD',
  scanIndex: 0,
  nBscans: 49,
}

const CANDIDATE: ResolveCandidate = {
  studyId: 7,
  studyName: 'GA-Studie',
  studyOid: 'S_GA',
  studySubjectId: 42,
  subjectLabel: 'GA-014',
  siteName: null,
  matchingEvent: null,
}

const EVENT: EventCandidate = {
  eventCrfId: 1234,
  definitionLabel: 'V1 · Follow-up',
  dateStart: '2026-06-17',
  matchPolicy: 'same-day',
}

function baseRow(state: ReviewRow['state'], overrides: Partial<ReviewRow> = {}): ReviewRow {
  return {
    rowId: 'row-1',
    file: makeFile('GA-014_OD.e2e'),
    scan: SCAN,
    state,
    candidates: [CANDIDATE],
    selectedCandidate: CANDIDATE,
    selectedEvent: EVENT,
    ...overrides,
  }
}

describe('Assignment (per-state action buttons)', () => {
  it('suggested → renders Bestätigen + ändern; confirm emits rowId', async () => {
    const w = mount(Assignment, { global: { plugins: [i18n] }, props: { row: baseRow('suggested') } })
    expect(w.text()).toContain('Bestätigen')
    expect(w.text()).toContain('ändern')
    expect(w.text()).toContain('Patient gefunden')
    const btn = w.find('[data-testid="action-confirm-row-1"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    expect(w.emitted('confirm')?.[0]).toEqual(['row-1'])
  })

  it('confirmed → renders Rückgängig; undo emits rowId', async () => {
    const w = mount(Assignment, { global: { plugins: [i18n] }, props: { row: baseRow('confirmed', { jobId: 99 }) } })
    expect(w.text()).toContain('Rückgängig')
    expect(w.text()).toContain('Zugeordnet')
    const btn = w.find('[data-testid="action-undo-row-1"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    expect(w.emitted('undo')?.[0]).toEqual(['row-1'])
  })

  it('novisit → renders Visite wählen + Später zuordnen; park emits rowId', async () => {
    const w = mount(Assignment, {
      global: { plugins: [i18n] },
      props: {
        row: baseRow('novisit', {
          selectedEvent: null,
          selectedCandidate: { ...CANDIDATE, matchingEvent: null },
        }),
      },
    })
    expect(w.text()).toContain('Visite wählen')
    expect(w.text()).toContain('Später zuordnen')
    expect(w.text()).toContain('Patient gefunden')
    const park = w.find('[data-testid="action-park-row-1"]')
    expect(park.exists()).toBe(true)
    await park.trigger('click')
    expect(w.emitted('park')?.[0]).toEqual(['row-1'])
  })

  it('nopatient → renders Patient suchen + Parken; park emits rowId', async () => {
    const w = mount(Assignment, {
      global: { plugins: [i18n] },
      props: {
        row: baseRow('nopatient', {
          candidates: [],
          selectedCandidate: undefined,
          selectedEvent: null,
        }),
      },
    })
    expect(w.text()).toContain('Patient nicht gefunden')
    expect(w.text()).toContain('Patient suchen')
    expect(w.text()).toContain('Parken')
    expect(w.text()).toContain('GA-014')
    const park = w.find('[data-testid="action-park-row-1"]')
    expect(park.exists()).toBe(true)
    await park.trigger('click')
    expect(w.emitted('park')?.[0]).toEqual(['row-1'])
  })

  it('ambiguous → renders Studie wählen; click emits pick-study (not pick-visit)', async () => {
    const SECOND: ResolveCandidate = {
      ...CANDIDATE,
      studyId: 8,
      studyName: 'AMD-Studie',
      studyOid: 'S_AMD',
      studySubjectId: 43,
    }
    const w = mount(Assignment, {
      global: { plugins: [i18n] },
      props: {
        row: baseRow('ambiguous', {
          candidates: [CANDIDATE, SECOND],
          selectedCandidate: undefined,
          selectedEvent: null,
        }),
      },
    })
    expect(w.text()).toContain('Mehrere Studien')
    expect(w.text()).toContain('Studie wählen')
    const btn = w.find('[data-testid="action-pick-study-row-1"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    expect(w.emitted('pick-study')?.[0]).toEqual(['row-1'])
    expect(w.emitted('pick-visit')).toBeUndefined()
  })

  it('error → renders dismiss button; dismiss emits rowId', async () => {
    const w = mount(Assignment, {
      global: { plugins: [i18n] },
      props: {
        row: baseRow('error', {
          candidates: undefined,
          selectedCandidate: undefined,
          selectedEvent: undefined,
          scan: undefined,
          error: 'Kein .e2e-Format — Datei übersprungen',
        }),
      },
    })
    expect(w.text()).toContain('Kein .e2e-Format')
    const dismiss = w.find('[data-testid="action-dismiss-row-1"]')
    expect(dismiss.exists()).toBe(true)
    await dismiss.trigger('click')
    expect(w.emitted('dismiss')?.[0]).toEqual(['row-1'])
  })
})

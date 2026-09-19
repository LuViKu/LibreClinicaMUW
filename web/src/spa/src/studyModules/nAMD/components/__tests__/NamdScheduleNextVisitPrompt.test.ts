/**
 * P2-5 — the prompt that puts the next visit on the calendar.
 *
 * The decision panel recorded an interval and stopped there, so "extend to 10
 * weeks" was a note in a form and the appointment existed only if someone
 * remembered to make it. In this study a visit is an injection.
 *
 * These pin both halves: the date the prompt proposes, and every case where it
 * must stay quiet rather than offering a visit that should not be created.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import NamdScheduleNextVisitPrompt from '../NamdScheduleNextVisitPrompt.vue'
import en from '../../locales/en.json'
import { useEventsStore } from '@/stores/events'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en },
})

interface TestEvent {
  id: string
  subjectId: string
  eventDefinitionOid: string
  eventLabel: string
  ordinal: number
  dateStarted: string
  dateEnded: string | null
  location: string
  status: string
  repeating: boolean
  scheduledFor: string | null
  scheduledIntervalDays: number | null
}

function event(over: Partial<TestEvent> = {}): TestEvent {
  return {
    id: '10',
    subjectId: 'M-001',
    eventDefinitionOid: 'SE_NAMDVISIT',
    eventLabel: 'nAMD Visit',
    ordinal: 1,
    dateStarted: '2026-01-01',
    dateEnded: null,
    location: '',
    status: 'data-entry-started',
    repeating: true,
    scheduledFor: null,
    scheduledIntervalDays: null,
    ...over,
  }
}

function mountPrompt(props: {
  studyEventId?: number | null
  decisionDate?: string
  intervalWeeks?: number | null
}) {
  return mount(NamdScheduleNextVisitPrompt, {
    props: {
      subjectLabel: 'M-001',
      studyEventId: props.studyEventId === undefined ? 10 : props.studyEventId,
      decisionDate: props.decisionDate ?? '2026-01-01',
      intervalWeeks: props.intervalWeeks === undefined ? 8 : props.intervalWeeks,
    },
    global: { plugins: [i18n] },
  })
}

let store: ReturnType<typeof useEventsStore>

beforeEach(() => {
  setActivePinia(createPinia())
  store = useEventsStore()
  store.load = vi.fn(async () => {})
  store.schedule = vi.fn(async () => event({ id: '11', dateStarted: '2026-02-26', status: 'scheduled' }))
  store.events = [event()] as never
})

describe('NamdScheduleNextVisitPrompt', () => {
  it('proposes the date the interval implies', async () => {
    const w = mountPrompt({})
    await flushPromises()
    // 2026-01-01 + 8 weeks = 2026-02-26.
    expect(w.find('[data-testid="namd-schedule-prompt"]').text()).toContain('2026-02-26')
  })

  it('creates the visit with the interval it was planned against', async () => {
    const w = mountPrompt({})
    await flushPromises()
    await w.find('[data-testid="namd-schedule-confirm"]').trigger('click')
    await flushPromises()

    expect(store.schedule).toHaveBeenCalledWith({
      subjectId: 'M-001',
      eventDefinitionOid: 'SE_NAMDVISIT',
      dateStarted: '2026-02-26',
      scheduledIntervalDays: 56,
    })
    expect(w.emitted('done')).toBeTruthy()
  })

  it('skipping creates nothing — the date is a clinical commitment', async () => {
    const w = mountPrompt({})
    await flushPromises()
    await w.find('[data-testid="namd-schedule-skip"]').trigger('click')
    await flushPromises()

    expect(store.schedule).not.toHaveBeenCalled()
    expect(w.emitted('done')).toBeTruthy()
    expect(w.find('[data-testid="namd-schedule-prompt"]').exists()).toBe(false)
  })

  it('stays quiet when the decision recorded no interval', async () => {
    const w = mountPrompt({ intervalWeeks: null })
    await flushPromises()
    expect(w.find('[data-testid="namd-schedule-prompt"]').exists()).toBe(false)
  })

  /** A non-repeating visit cannot be scheduled twice; offering it invites a 409. */
  it('stays quiet when the visit definition does not repeat', async () => {
    store.events = [event({ repeating: false })] as never
    const w = mountPrompt({})
    await flushPromises()
    expect(w.find('[data-testid="namd-schedule-prompt"]').exists()).toBe(false)
  })

  it('says so instead of offering when a visit is already planned ahead', async () => {
    store.events = [
      event(),
      event({ id: '12', dateStarted: '2026-03-01', status: 'scheduled' }),
    ] as never
    const w = mountPrompt({})
    await flushPromises()

    expect(w.find('[data-testid="namd-schedule-prompt"]').exists()).toBe(false)
    expect(w.find('[data-testid="namd-schedule-existing"]').text()).toContain('2026-03-01')
  })

  /** A past visit of the same definition is history, not a reason to stay quiet. */
  it('still offers when the only other visit of that definition is in the past', async () => {
    store.events = [
      event(),
      event({ id: '9', dateStarted: '2025-11-01', status: 'scheduled' }),
    ] as never
    const w = mountPrompt({})
    await flushPromises()
    expect(w.find('[data-testid="namd-schedule-prompt"]').exists()).toBe(true)
  })

  it('surfaces a failure instead of pretending the visit exists', async () => {
    store.schedule = vi.fn(async () => null)
    store.error = 'Anlegen fehlgeschlagen (HTTP 409).'
    const w = mountPrompt({})
    await flushPromises()
    await w.find('[data-testid="namd-schedule-confirm"]').trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="namd-schedule-error"]').text()).toContain('409')
    expect(w.emitted('done')).toBeFalsy()
  })
})

/**
 * nAMD treat-and-extend Slice 1 (2026-06-19) — ScheduleEventDialog
 * spec covering the new "Next interval (weeks)" field that drives
 * `study_event.scheduled_interval_days` via the backend
 * VisitIntervalCalculator service.
 *
 * Pins three contracts:
 *
 *  1. **Omitted interval** → `scheduledIntervalDays` is NOT on the wire.
 *     Existing non-T-and-E callers stay byte-identical with what they
 *     used to send.
 *  2. **Valid interval (weeks)** → converted to days (× 7) and put on
 *     the wire as `scheduledIntervalDays`.
 *  3. **Negative interval** → field-level validation error; the
 *     schedule action is never called.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import ScheduleEventDialog from '@/components/ScheduleEventDialog.vue'
import { useEventsStore } from '@/stores/events'
import { useEventDefinitionsStore } from '@/stores/eventDefinitions'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function seedEventDefinitions() {
  const eventDefs = useEventDefinitionsStore()
  eventDefs.rows = [
    {
      id: 1,
      oid: 'V1',
      name: 'Visit 1',
      type: 'scheduled',
      repeating: false,
      category: '',
      status: 'AVAILABLE',
    },
  ] as unknown as typeof eventDefs.rows
}

function mountDialog() {
  return mount(ScheduleEventDialog, {
    props: { open: true, subjectId: 'S-001', studyOid: 'S_TEST' },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('ScheduleEventDialog — nAMD interval handling', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedEventDefinitions()
  })

  it('omits scheduledIntervalDays when the field is left blank', async () => {
    const events = useEventsStore()
    const scheduleSpy = vi.spyOn(events, 'schedule').mockResolvedValue({
      id: '42',
      subjectId: 'S-001',
      eventDefinitionOid: 'V1',
      eventLabel: 'Visit 1',
      ordinal: 1,
      dateStarted: '2026-06-19',
      dateEnded: null,
      location: null,
      status: 'scheduled',
      repeating: false,
    } as unknown as ReturnType<typeof events.schedule> extends Promise<infer T> ? T : never)

    const wrapper = mountDialog()
    await flushPromises()

    // Pick the event definition + date; leave the interval blank.
    const defSelect = document.body.querySelector(
      'select#schedule-event-def',
    ) as HTMLSelectElement
    defSelect.value = 'V1'
    defSelect.dispatchEvent(new Event('change', { bubbles: true }))
    const dateInput = document.body.querySelector(
      'input#schedule-event-date',
    ) as HTMLInputElement
    dateInput.value = '19/06/2026'
    dateInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    const submit = Array.from(
      document.body.querySelectorAll('button'),
    ).find((b) => b.textContent?.trim() === 'Schedule')
    expect(submit).toBeTruthy()
    submit!.click()
    await flushPromises()

    expect(scheduleSpy).toHaveBeenCalledTimes(1)
    const payload = scheduleSpy.mock.calls[0][0]
    expect(payload.dateStarted).toBe('2026-06-19')
    expect('scheduledIntervalDays' in payload).toBe(false)
    wrapper.unmount()
  })

  it('converts weeks to days and sends scheduledIntervalDays when filled', async () => {
    const events = useEventsStore()
    const scheduleSpy = vi.spyOn(events, 'schedule').mockResolvedValue({
      id: '43',
      subjectId: 'S-001',
      eventDefinitionOid: 'V1',
      eventLabel: 'Visit 1',
      ordinal: 1,
      dateStarted: '2026-06-19',
      dateEnded: null,
      location: null,
      status: 'scheduled',
      repeating: false,
    } as unknown as ReturnType<typeof events.schedule> extends Promise<infer T> ? T : never)

    const wrapper = mountDialog()
    await flushPromises()

    const defSelect = document.body.querySelector(
      'select#schedule-event-def',
    ) as HTMLSelectElement
    defSelect.value = 'V1'
    defSelect.dispatchEvent(new Event('change', { bubbles: true }))
    const dateInput = document.body.querySelector(
      'input#schedule-event-date',
    ) as HTMLInputElement
    dateInput.value = '19/06/2026'
    dateInput.dispatchEvent(new Event('input', { bubbles: true }))
    const intervalInput = document.body.querySelector(
      'input#schedule-event-interval',
    ) as HTMLInputElement
    intervalInput.value = '8'
    intervalInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    const submit = Array.from(
      document.body.querySelectorAll('button'),
    ).find((b) => b.textContent?.trim() === 'Schedule')
    submit!.click()
    await flushPromises()

    expect(scheduleSpy).toHaveBeenCalledTimes(1)
    const payload = scheduleSpy.mock.calls[0][0]
    expect(payload.scheduledIntervalDays).toBe(56)
    wrapper.unmount()
  })

  it('blocks submit on negative interval', async () => {
    const events = useEventsStore()
    const scheduleSpy = vi.spyOn(events, 'schedule')

    const wrapper = mountDialog()
    await flushPromises()

    const defSelect = document.body.querySelector(
      'select#schedule-event-def',
    ) as HTMLSelectElement
    defSelect.value = 'V1'
    defSelect.dispatchEvent(new Event('change', { bubbles: true }))
    const dateInput = document.body.querySelector(
      'input#schedule-event-date',
    ) as HTMLInputElement
    dateInput.value = '19/06/2026'
    dateInput.dispatchEvent(new Event('input', { bubbles: true }))
    const intervalInput = document.body.querySelector(
      'input#schedule-event-interval',
    ) as HTMLInputElement
    intervalInput.value = '-2'
    intervalInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    const submit = Array.from(
      document.body.querySelectorAll('button'),
    ).find((b) => b.textContent?.trim() === 'Schedule')
    submit!.click()
    await flushPromises()

    expect(scheduleSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

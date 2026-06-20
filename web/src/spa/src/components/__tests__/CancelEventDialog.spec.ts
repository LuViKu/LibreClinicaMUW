/**
 * Wave 1A (app-feedback, 2026-06-19) — CancelEventDialog spec.
 *
 * Pins:
 *  - mounts cleanly when open + fetches the reason catalog on first open.
 *  - revealing the free-text textarea is conditional on the picked
 *    row's `isOther` flag.
 *  - confirm path calls `events.cancelEvent(...)` with the picked
 *    reasonCode (and reasonText when the row is the "Other" entry),
 *    then emits `cancelled`.
 *  - load-failure path surfaces an inline error and pushes to the
 *    global errors store.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
  }
})

import CancelEventDialog from '@/components/CancelEventDialog.vue'
import { apiGet, apiDelete, ApiError } from '@/api/client'
import { useEventsStore } from '@/stores/events'
import { useErrorsStore } from '@/stores/errors'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de',
  fallbackLocale: 'de',
  messages: { de: deMessages },
})

const REASONS = [
  { code: 'PATIENT_NO_SHOW', labelDe: 'Patient nicht erschienen',
    labelEn: 'Patient did not attend', sortOrder: 10, isOther: false },
  { code: 'INTERVAL_CHANGE_3_TO_6M', labelDe: 'Intervall geändert (3→6 Monate)',
    labelEn: 'Schedule changed (3→6 months)', sortOrder: 20, isOther: false },
  { code: 'OTHER', labelDe: 'Sonstiges (Freitext)',
    labelEn: 'Other (free text)', sortOrder: 90, isOther: true },
]

function mountDialog(props: Partial<{ open: boolean; eventId: string; eventLabel: string }> = {}) {
  return mount(CancelEventDialog, {
    props: {
      open: true,
      eventId: '42',
      eventLabel: 'Visite 3 — Follow-up',
      ...props,
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

/**
 * The reason picker is a {@code <SelectInput>} primitive whose root
 * element is a wrapping {@code <div class="relative">} — so the
 * {@code data-testid="cancel-event-reason"} attribute lands on the div,
 * not on the underlying {@code <select>}. The actual control carries
 * the {@code id="cancel-event-reason"} attribute (set inside
 * {@link SelectInput}). Looking it up by id avoids the
 * {@code wrapper.setValue() cannot be called on DIV} trap and lets us
 * drive the value through {@link DOMWrapper.setValue} which trips Vue's
 * native {@code @change} handler (a bare
 * {@code dispatchEvent(new Event('change'))} doesn't wake the listener
 * under jsdom + Teleport).
 */
function pickReason(code: string) {
  const select = document.body.querySelector('select#cancel-event-reason')
  if (!select) throw new Error('select#cancel-event-reason not in DOM yet')
  return new DOMWrapper(select as HTMLSelectElement).setValue(code)
}

/**
 * Same trap as {@link pickReason} but for the conditional reason
 * textarea — this one lives in the dialog template directly (no
 * primitive wrapper), so the {@code data-testid} attribute is on the
 * textarea itself. We keep the helper symmetric so the test bodies stay
 * one-shot.
 */
function setOtherText(value: string) {
  const ta = document.body.querySelector('textarea#cancel-event-other-text')
  if (!ta) throw new Error('textarea#cancel-event-other-text not in DOM yet')
  return new DOMWrapper(ta as HTMLTextAreaElement).setValue(value)
}

describe('CancelEventDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiDelete).mockReset()
  })

  it('mounts when open=true and fetches the reason catalog', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(REASONS)
    const wrapper = mountDialog()
    await flushPromises()

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/event-cancel-reasons')

    // SelectInput's root is a wrapping <div> — the underlying control
    // lives at #cancel-event-reason. Query by id, not by data-testid.
    const select = document.body.querySelector<HTMLSelectElement>(
      'select#cancel-event-reason',
    )
    expect(select).not.toBeNull()
    // 3 seeded options + 1 placeholder.
    const opts = select!.querySelectorAll('option')
    expect(opts.length).toBe(REASONS.length + 1)

    wrapper.unmount()
  })

  it('reveals the free-text textarea only when the picked row is is_other=true', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(REASONS)
    const wrapper = mountDialog()
    await flushPromises()

    // No textarea before a reason is picked.
    expect(document.body.querySelector('[data-testid="cancel-event-other-text"]')).toBeNull()

    // Pick a non-other reason — textarea stays hidden.
    await pickReason('PATIENT_NO_SHOW')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="cancel-event-other-text"]')).toBeNull()

    // Pick OTHER — textarea appears.
    await pickReason('OTHER')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="cancel-event-other-text"]')).not.toBeNull()

    wrapper.unmount()
  })

  it('confirm with a non-other reason calls cancelEvent and emits cancelled', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(REASONS)
    vi.mocked(apiDelete).mockResolvedValueOnce(undefined)

    const wrapper = mountDialog()
    await flushPromises()
    const events = useEventsStore()
    const spy = vi.spyOn(events, 'cancelEvent')

    await pickReason('PATIENT_NO_SHOW')
    await flushPromises()

    const confirmBtn = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="cancel-event-confirm"]',
    )!
    confirmBtn.click()
    await flushPromises()

    expect(spy).toHaveBeenCalledWith('42', { reasonCode: 'PATIENT_NO_SHOW', reasonText: undefined })
    const emitted = wrapper.emitted('cancelled')
    expect(emitted).toBeTruthy()

    wrapper.unmount()
  })

  it('OTHER with blank text blocks submission with an inline error', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(REASONS)
    const wrapper = mountDialog()
    await flushPromises()
    const events = useEventsStore()
    const spy = vi.spyOn(events, 'cancelEvent')

    await pickReason('OTHER')
    await flushPromises()

    const confirmBtn = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="cancel-event-confirm"]',
    )!
    confirmBtn.click()
    await flushPromises()

    expect(spy).not.toHaveBeenCalled()
    expect(wrapper.emitted('cancelled')).toBeFalsy()
    wrapper.unmount()
  })

  it('OTHER with text on confirm carries reasonText to the store', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(REASONS)
    vi.mocked(apiDelete).mockResolvedValueOnce(undefined)

    const wrapper = mountDialog()
    await flushPromises()
    const events = useEventsStore()
    const spy = vi.spyOn(events, 'cancelEvent')

    await pickReason('OTHER')
    await flushPromises()
    await setOtherText('Patient meldete sich krank')
    await flushPromises()

    const confirmBtn = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="cancel-event-confirm"]',
    )!
    confirmBtn.click()
    await flushPromises()

    expect(spy).toHaveBeenCalledWith('42', {
      reasonCode: 'OTHER',
      reasonText: 'Patient meldete sich krank',
    })
    expect(wrapper.emitted('cancelled')).toBeTruthy()

    wrapper.unmount()
  })

  it('catalog load failure surfaces inline error and pushes to errors store', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, 'boom', null, 'req-1'))
    const wrapper = mountDialog()
    await flushPromises()
    const errors = useErrorsStore()

    const errEl = document.body.querySelector('[data-testid="cancel-event-load-error"]')
    expect(errEl).not.toBeNull()
    expect(errors.recent.length).toBeGreaterThan(0)

    wrapper.unmount()
  })
})

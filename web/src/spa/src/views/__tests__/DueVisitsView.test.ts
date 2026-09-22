/**
 * P2-6 — the due-visits view.
 *
 * A visit nobody schedules a patient for simply does not happen, and in a
 * treat-and-extend study a missed visit is a missed injection. The view exists
 * so somebody notices, which makes the ordering load-bearing: what was missed
 * has to be visible before what is merely coming.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import DueVisitsView from '../DueVisitsView.vue'
import enMessages from '@/locales/en.json'

const { listMock } = vi.hoisted(() => ({ listMock: vi.fn() }))
vi.mock('@/api/events', () => ({ listDueVisits: (...a: unknown[]) => listMock(...a) }))

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function visit(over: Record<string, unknown> = {}) {
  return {
    studyEventId: 1,
    studySubjectId: 1,
    subjectLabel: 'M-001',
    studyName: 'HealthAEye',
    eventLabel: 'V3 Day 90',
    date: '2026-09-20',
    time: null,
    status: 'scheduled',
    overdue: false,
    ...over,
  }
}

function mountView() {
  return mount(DueVisitsView, {
    global: { plugins: [i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

beforeEach(() => {
  listMock.mockReset()
  listMock.mockResolvedValue({ from: '2026-09-04', to: '2026-10-02', visits: [] })
})

describe('DueVisitsView', () => {
  it('asks for a window that reaches into the past, so misses are included', async () => {
    mountView()
    await flushPromises()
    const arg = listMock.mock.calls[0][0] as { from: string; to: string }
    expect(arg.from < new Date().toISOString().slice(0, 10)).toBe(true)
    expect(arg.to > new Date().toISOString().slice(0, 10)).toBe(true)
  })

  it('puts overdue visits above upcoming ones', async () => {
    listMock.mockResolvedValue({
      from: 'x',
      to: 'y',
      visits: [
        visit({ studyEventId: 1, subjectLabel: 'UPCOMING', date: '2026-09-30', overdue: false }),
        visit({ studyEventId: 2, subjectLabel: 'MISSED', date: '2026-09-01', overdue: true }),
      ],
    })
    const w = mountView()
    await flushPromises()

    const rows = w.findAll('[data-testid="due-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('MISSED')
    expect(rows[1].text()).toContain('UPCOMING')
  })

  it('marks an overdue visit and counts them', async () => {
    listMock.mockResolvedValue({
      from: 'x',
      to: 'y',
      visits: [visit({ overdue: true }), visit({ studyEventId: 2, overdue: false })],
    })
    const w = mountView()
    await flushPromises()

    expect(w.findAll('[data-testid="due-overdue-badge"]')).toHaveLength(1)
    expect(w.find('[data-testid="due-overdue-count"]').text()).toContain('1')
  })

  it('says the window is empty rather than showing a bare table', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-testid="due-empty"]').exists()).toBe(true)
    expect(w.findAll('[data-testid="due-row"]')).toHaveLength(0)
  })

  it('surfaces a failure instead of reporting an empty schedule', async () => {
    listMock.mockRejectedValue(new Error('window may not exceed 92 days'))
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="due-error"]').text()).toContain('92 days')
    expect(w.find('[data-testid="due-empty"]').exists()).toBe(false)
  })

  it('reloads with the dates the user chose', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('#dv-from').setValue('2026-01-01')
    await w.find('#dv-to').setValue('2026-01-31')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(listMock).toHaveBeenLastCalledWith({ from: '2026-01-01', to: '2026-01-31' })
  })
})

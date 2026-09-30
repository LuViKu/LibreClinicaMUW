/**
 * R1-export — the schedules panel under a dataset row.
 *
 * Pins the calls the panel makes (the server validates and reschedules;
 * the panel only has to send the right request) and that the list shows
 * what the server answered: a pause is a PATCH of enabled alone, an edit
 * sends the whole form, a delete asks first, and a refusal keeps the
 * dialog open with the server's reason.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  ApiError: class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
    constructor(public status = 0, msg = '', public body: unknown = null) { super(msg) }
  },
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

const confirmAnswer = { value: true }
vi.mock('@/composables/useConfirm', () => ({
  useConfirm: () => () => Promise.resolve(confirmAnswer.value),
}))

import { apiDelete, apiGet, apiPatch, apiPost, ApiError } from '@/api/client'
import DatasetSchedules from '@/components/DatasetSchedules.vue'
import type { ExportScheduleDto } from '@/types/export'

import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const RUNNING: ExportScheduleDto = {
  id: 5, datasetId: 11, format: 'odm', cronExpression: '0 0 3 ? * MON', active: true, enabled: true,
  notifyEmail: 'dm-team@example.org', createdAt: '2026-09-01T08:00:00Z',
  nextRunAt: '2026-10-05T03:00:00Z', lastRunAt: null, lastRunJobId: null,
}
const PAUSED: ExportScheduleDto = {
  ...RUNNING, id: 6, format: 'csv', cronExpression: '0 0 2 * * ?', enabled: false, notifyEmail: null, nextRunAt: null,
}

async function mountPanel(schedules: ExportScheduleDto[]) {
  setActivePinia(createPinia())
  ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(schedules)
  const wrapper = mount(DatasetSchedules, {
    props: { datasetId: 11, datasetName: 'Alpha cohort', formats: ['odm', 'csv', 'tsv'] },
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('DatasetSchedules', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmAnswer.value = true
  })

  it("lists the dataset's schedules, running and paused", async () => {
    const w = await mountPanel([RUNNING, PAUSED])

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/datasets/11/schedules')
    const running = w.get('[data-testid="schedule-5"]')
    expect(running.text()).toContain('0 0 3 ? * MON')
    expect(running.text()).toContain('dm-team@example.org')
    expect(running.get('[data-testid="schedule-status"]').text()).toBe(enMessages.dataExport.schedules.status.active)
    expect(running.find('[data-testid="schedule-pause"]').exists()).toBe(true)

    const paused = w.get('[data-testid="schedule-6"]')
    expect(paused.get('[data-testid="schedule-status"]').text()).toBe(enMessages.dataExport.schedules.status.paused)
    expect(paused.find('[data-testid="schedule-resume"]').exists()).toBe(true)
  })

  it('adds a schedule with the chosen format, time and contact', async () => {
    const created: ExportScheduleDto = { ...RUNNING, id: 9, format: 'csv', cronExpression: '0 0 2 ? * MON', notifyEmail: 'dm@example.org' }
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(created)
    const w = await mountPanel([])

    await w.get('[data-testid="schedule-add"]').trigger('click')
    await w.get('[data-testid="schedule-format"]').setValue('csv')
    await w.get('[data-testid="schedule-preset-weekly"]').trigger('click')
    await w.get('[data-testid="schedule-email"]').setValue('dm@example.org')
    await w.get('[data-testid="schedule-save"]').trigger('click')
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/datasets/11/schedules', {
      format: 'csv', cronExpression: '0 0 2 ? * MON', notifyEmail: 'dm@example.org',
    })
    expect(w.find('[role="dialog"]').exists()).toBe(false)
    expect(w.get('[data-testid="schedule-9"]').text()).toContain('0 0 2 ? * MON')
  })

  it('edits a schedule and shows what the server saved', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ ...RUNNING, cronExpression: '0 30 4 * * ?', notifyEmail: null })
    const w = await mountPanel([RUNNING])

    await w.get('[data-testid="schedule-5"] [data-testid="schedule-edit"]').trigger('click')
    expect((w.get('[data-testid="schedule-cron"]').element as HTMLInputElement).value).toBe('0 0 3 ? * MON')
    await w.get('[data-testid="schedule-cron"]').setValue('0 30 4 * * ?')
    await w.get('[data-testid="schedule-email"]').setValue('')
    await w.get('[data-testid="schedule-save"]').trigger('click')
    await flushPromises()

    expect(apiPatch).toHaveBeenCalledWith('/pages/api/v1/schedules/5', {
      format: 'odm', cronExpression: '0 30 4 * * ?', enabled: true, notifyEmail: '',
    })
    const row = w.get('[data-testid="schedule-5"]')
    expect(row.text()).toContain('0 30 4 * * ?')
    expect(row.text()).not.toContain('dm-team@example.org')
  })

  it('pauses and resumes with a PATCH of enabled alone', async () => {
    const w = await mountPanel([RUNNING])

    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ ...RUNNING, enabled: false, nextRunAt: null })
    await w.get('[data-testid="schedule-pause"]').trigger('click')
    await flushPromises()
    expect(apiPatch).toHaveBeenLastCalledWith('/pages/api/v1/schedules/5', { enabled: false })
    expect(w.get('[data-testid="schedule-status"]').text()).toBe(enMessages.dataExport.schedules.status.paused)

    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(RUNNING)
    await w.get('[data-testid="schedule-resume"]').trigger('click')
    await flushPromises()
    expect(apiPatch).toHaveBeenLastCalledWith('/pages/api/v1/schedules/5', { enabled: true })
    expect(w.get('[data-testid="schedule-status"]').text()).toBe(enMessages.dataExport.schedules.status.active)
  })

  it('deletes a schedule only once confirmed', async () => {
    ;(apiDelete as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
    const w = await mountPanel([RUNNING])

    confirmAnswer.value = false
    await w.get('[data-testid="schedule-delete"]').trigger('click')
    await flushPromises()
    expect(apiDelete).not.toHaveBeenCalled()

    confirmAnswer.value = true
    await w.get('[data-testid="schedule-delete"]').trigger('click')
    await flushPromises()
    expect(apiDelete).toHaveBeenCalledWith('/pages/api/v1/schedules/5')
    expect(w.find('[data-testid="schedule-5"]').exists()).toBe(false)
  })

  it("keeps the dialog open with the server's reason when it refuses", async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(400, 'Bad Request', { message: "Invalid cron expression: '0 0 25 * * ?'" }),
    )
    const w = await mountPanel([])

    await w.get('[data-testid="schedule-add"]').trigger('click')
    await w.get('[data-testid="schedule-cron"]').setValue('0 0 25 * * ?')
    await w.get('[data-testid="schedule-save"]').trigger('click')
    await flushPromises()

    expect(w.find('[role="dialog"]').exists()).toBe(true)
    expect(w.get('[data-testid="schedule-error"]').text()).toContain('Invalid cron expression')
  })
})

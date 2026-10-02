/**
 * R1-export — legacy scheduled exports on the jobs page.
 *
 * Every trigger is listed; only a legacy scheduled export (group
 * XsltTriggersExportJobs, made by the retiring /CreateJobExport) carries a
 * delete action, with what it was set to do beside it so an administrator can
 * recreate it as a dataset schedule first. The application's own triggers
 * stay read-only.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiDelete: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(public status = 0, msg = '', public body: unknown = null) { super(msg) }
  },
}))

const confirmAnswer = { value: true }
vi.mock('@/composables/useConfirm', () => ({
  useConfirm: () => () => Promise.resolve(confirmAnswer.value),
}))

import { apiDelete, apiGet } from '@/api/client'
import AdminJobsView from '@/views/AdminJobsView.vue'

import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const POLLER = {
  name: 'exportJobRunnerTrigger', group: 'exportJobPoller', description: null, priority: 5,
  previousFireTime: null, nextFireTime: '2026-09-30T18:00:30Z', finalFireTime: null, state: 'NORMAL',
}
const LEGACY = {
  name: 'weekly odm', group: 'XsltTriggersExportJobs', description: 'Weekly ODM for the sponsor', priority: 5,
  previousFireTime: null, nextFireTime: '2026-10-05T03:00:00Z', finalFireTime: null, state: 'NORMAL',
  legacyExport: {
    jobName: 'weekly odm', datasetId: 3, period: 'weekly',
    exportFormat: 'CDISC ODM XML 1.3 Full', contactEmail: 'dm-team@example.org',
  },
}
const UNREADABLE = {
  name: 'from-before-the-rename', group: 'XsltTriggersExportJobs', state: 'ERROR',
  unreadable: true, legacyExport: {},
}

function jobs(list: unknown[]) {
  return { schedulerName: 'public', isStarted: true, isStandby: false, jobs: list }
}

async function mountView(list: unknown[]) {
  setActivePinia(createPinia())
  ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(jobs(list))
  const wrapper = mount(AdminJobsView, {
    global: { plugins: [i18n], stubs: { SystemRail: true } },
  })
  await flushPromises()
  return wrapper
}

describe('AdminJobsView — legacy scheduled exports', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmAnswer.value = true
  })

  it('offers delete on a legacy export only, with what it was set to do', async () => {
    const w = await mountView([POLLER, LEGACY])

    expect(w.find('[data-testid="legacy-export-note"]').exists()).toBe(true)
    const legacy = w.get('[data-testid="job-XsltTriggersExportJobs-weekly odm"]')
    expect(legacy.text()).toContain(enMessages.adminJobs.legacy.badge)
    expect(legacy.text()).toContain('weekly')
    expect(legacy.text()).toContain('CDISC ODM XML 1.3 Full')
    expect(legacy.text()).toContain('dm-team@example.org')
    expect(legacy.find('[data-testid="legacy-export-delete"]').exists()).toBe(true)

    const poller = w.get('[data-testid="job-exportJobPoller-exportJobRunnerTrigger"]')
    expect(poller.find('[data-testid="legacy-export-delete"]').exists()).toBe(false)
  })

  it('shows no note while no legacy export exists', async () => {
    const w = await mountView([POLLER])
    expect(w.find('[data-testid="legacy-export-note"]').exists()).toBe(false)
    expect(w.find('[data-testid="legacy-export-delete"]').exists()).toBe(false)
  })

  it('deletes a legacy export once confirmed, then reloads the list', async () => {
    ;(apiDelete as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
    const w = await mountView([POLLER, LEGACY])

    confirmAnswer.value = false
    await w.get('[data-testid="legacy-export-delete"]').trigger('click')
    await flushPromises()
    expect(apiDelete).not.toHaveBeenCalled()

    confirmAnswer.value = true
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(jobs([POLLER]))
    await w.get('[data-testid="legacy-export-delete"]').trigger('click')
    await flushPromises()

    expect(apiDelete).toHaveBeenCalledWith('/pages/api/v1/admin/jobs/legacy-exports/weekly%20odm')
    expect(apiGet).toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="job-XsltTriggersExportJobs-weekly odm"]').exists()).toBe(false)
  })

  it('lists a legacy export whose stored data cannot be read, and lets it be deleted', async () => {
    const w = await mountView([UNREADABLE])

    const row = w.get('[data-testid="job-XsltTriggersExportJobs-from-before-the-rename"]')
    expect(row.text()).toContain(enMessages.adminJobs.legacy.unreadable)
    expect(row.find('[data-testid="legacy-export-delete"]').exists()).toBe(true)
  })
})

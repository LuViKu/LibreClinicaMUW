/**
 * CrfMigrationView — moving existing event CRFs to another CRF version, kept
 * apart from changing the default version for new event CRFs.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import en from '@/locales/en.json'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { ApiError, apiGet, apiPost } from '@/api/client'
import CrfMigrationView from '@/views/CrfMigrationView.vue'
import { useAuthStore } from '@/stores/auth'
import type { EventCrfMigrationOptions, EventCrfMigrationPreview, EventCrfMigrationResult } from '@/types/crfMigration'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en },
})

const OPTIONS: EventCrfMigrationOptions = {
  crfOid: 'F_AE',
  crfName: 'Adverse events',
  study: { oid: 'S_DEFAULTS1', name: 'Default Study' },
  versions: [
    { oid: 'F_AE_V1', name: 'v1', status: 'available', eventCrfCount: 12 },
    { oid: 'F_AE_V2', name: 'v2', status: 'available', eventCrfCount: 0 },
  ],
  sites: [{ oid: 'S_DEFAULTS1', name: 'Default Study' }, { oid: 'S_SITE_A', name: 'Site A' }],
  eventDefinitions: [{ oid: 'SE_V1', name: 'V1 Inclusion' }],
}

const DETAIL = {
  oid: 'F_AE',
  name: 'Adverse events',
  description: '',
  status: 'available',
  mayEdit: true,
  versions: [
    { oid: 'F_AE_V1', name: 'v1', description: '', revisionNotes: '', status: 'available', uploadedAt: null },
    { oid: 'F_AE_V2', name: 'v2', description: '', revisionNotes: '', status: 'available', uploadedAt: null },
  ],
  items: [],
  studies: [],
}

function row(id: number, label: string) {
  return {
    eventCrfId: id,
    studySubjectLabel: label,
    siteOid: 'S_DEFAULTS1',
    siteName: 'Default Study',
    eventDefinitionOid: 'SE_V1',
    eventName: 'V1 Inclusion',
    eventOrdinal: 1,
    sdvVerified: true,
    subjectSigned: id === 2,
    eventSigned: id === 2,
    eventCrfSigned: id === 2,
  }
}

const PREVIEW: EventCrfMigrationPreview = {
  crfOid: 'F_AE',
  crfName: 'Adverse events',
  study: { oid: 'S_DEFAULTS1', name: 'Default Study' },
  sourceVersion: { oid: 'F_AE_V1', name: 'v1' },
  targetVersion: { oid: 'F_AE_V2', name: 'v2' },
  sites: [],
  eventDefinitions: [],
  studySubjectLabel: null,
  eventCrfCount: 2,
  subjectCount: 2,
  sdvVerifiedCount: 2,
  signedSubjectCount: 1,
  signedEventCount: 1,
  signedEventCrfCount: 1,
  eventCrfs: [row(1, 'M-001'), row(2, 'M-002')],
  eventCrfsTruncated: false,
  locked: [{ row: row(3, 'M-003'), reason: 'subject-locked' }],
  notOffered: [{ siteOid: 'S_SITE_A', siteName: 'Site A', eventDefinitionOid: 'SE_V1', eventName: 'V1 Inclusion', eventCrfCount: 4 }],
  hiddenValueCount: 5,
  hiddenItems: [{ name: 'AE_GRADE', oid: 'I_AE_GRADE', valueCount: 5 }],
}

const RESULT: EventCrfMigrationResult = {
  crfOid: 'F_AE',
  crfName: 'Adverse events',
  study: { oid: 'S_DEFAULTS1', name: 'Default Study' },
  sourceVersion: { oid: 'F_AE_V1', name: 'v1' },
  targetVersion: { oid: 'F_AE_V2', name: 'v2' },
  migratedEventCrfCount: 2,
  subjectCount: 2,
  sdvClearedCount: 2,
  unsignedSubjectCount: 1,
  unsignedEventCount: 1,
  unsignedEventCrfCount: 1,
  log: [row(1, 'M-001'), row(2, 'M-002')],
  completedAt: '2026-09-30T10:00:00Z',
}

function getByUrl(options: EventCrfMigrationOptions | Error = OPTIONS) {
  vi.mocked(apiGet).mockImplementation(async (url: string) => {
    if (url.includes('/event-crf-migration/options')) {
      if (options instanceof Error) throw options
      return options
    }
    if (url === '/pages/api/v1/crfs/F_AE') return DETAIL
    return []
  })
}

async function mountView(): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    username: 'dm',
    displayName: 'Demo DM',
    email: null,
    role: 'Data Manager',
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: null,
    timezone: null,
    activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false, settings: {} },
  } as never
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/crf-library', name: 'crf-library', component: { template: '<div />' } },
      { path: '/audit-log', name: 'audit-log', component: { template: '<div />' } },
      { path: '/crf-library/:crfOid/migrate', name: 'crf-migration', component: CrfMigrationView },
    ],
  })
  await router.push('/crf-library/F_AE/migrate')
  await router.isReady()
  const wrapper = mount(CrfMigrationView, {
    global: { plugins: [pinia, router, i18n], stubs: { BuildStudyRail: true } },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

async function choose(wrapper: VueWrapper, source = 'F_AE_V1', target = 'F_AE_V2') {
  await wrapper.find('#mig-source').setValue(source)
  await wrapper.find('#mig-target').setValue(target)
}

async function preview(wrapper: VueWrapper) {
  vi.mocked(apiPost).mockResolvedValueOnce(PREVIEW)
  await wrapper.find('[data-testid="mig-preview"]').trigger('click')
  await flushPromises()
}

describe('CrfMigrationView', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    document.body.innerHTML = ''
  })

  it('keeps moving data and changing the default version apart, each with what it does', async () => {
    getByUrl()
    const wrapper = await mountView()

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/crfs/F_AE/event-crf-migration/options?studyOid=S_DEFAULTS1')
    const move = wrapper.find('[data-testid="crf-migration-move"]').text()
    expect(move).toContain('Move existing event CRFs to another version')
    expect(move).toContain('SDV is cleared on every moved event CRF')
    const defaults = wrapper.find('[data-testid="crf-migration-defaults"]').text()
    expect(defaults).toContain('Change the default version for new event CRFs')
    expect(defaults).toContain('existing event CRFs and their data stay on their version')
  })

  it('previews what moves and warns what it clears before anything is written', async () => {
    getByUrl()
    const wrapper = await mountView()
    await choose(wrapper)
    await preview(wrapper)

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/crfs/F_AE/event-crf-migration/preview', {
      studyOid: 'S_DEFAULTS1',
      sourceVersionOid: 'F_AE_V1',
      targetVersionOid: 'F_AE_V2',
      siteOids: [],
      eventDefinitionOids: [],
      studySubjectLabel: undefined,
      eventCrfIds: undefined,
    })
    expect(wrapper.find('[data-testid="mig-preview-summary"]').text())
      .toContain('2 event CRF(s) of 2 subject(s) will move from version "v1" to "v2"')
    const warning = wrapper.find('[data-testid="mig-warning"]').text()
    expect(warning).toContain('Source data verification is cleared')
    expect(warning).toContain('1 signed subject(s), 1 signed event(s), 1 signed event CRF(s)')
    expect(wrapper.find('[data-testid="mig-hidden"]').text()).toContain('AE_GRADE (5)')
    expect(wrapper.find('[data-testid="mig-locked"]').text()).toContain('M-003 · V1 Inclusion — subject locked')
    expect(wrapper.find('[data-testid="mig-not-offered"]').text()).toContain('Site A · V1 Inclusion: 4 event CRF(s)')
  })

  it('runs only after the explicit confirmation, with the previewed count, and shows the log', async () => {
    getByUrl()
    const wrapper = await mountView()
    await choose(wrapper)
    await preview(wrapper)

    const run = wrapper.find('[data-testid="mig-run"]')
    expect((run.element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.find('[data-testid="mig-confirm"]').setValue(true)
    expect((run.element as HTMLButtonElement).disabled).toBe(false)

    vi.mocked(apiPost).mockResolvedValueOnce(RESULT)
    await run.trigger('click')
    await flushPromises()

    expect(apiPost).toHaveBeenLastCalledWith('/pages/api/v1/crfs/F_AE/event-crf-migration',
      expect.objectContaining({ sourceVersionOid: 'F_AE_V1', targetVersionOid: 'F_AE_V2', expectedEventCrfCount: 2 }))
    const result = wrapper.find('[data-testid="mig-result"]').text()
    expect(result).toContain('Moved 2 event CRF(s) of 2 subject(s) from version "v1" to "v2"')
    expect(result).toContain('study audit log')
    expect(wrapper.find('[data-testid="mig-log"]').text()).toContain('M-002')

    const createObjectURL = vi.fn(() => 'blob:log')
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL: vi.fn() })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await wrapper.find('[data-testid="mig-download-log"]').trigger('click')
    const blob = (createObjectURL.mock.calls[0] as unknown as [Blob])[0]
    // jsdom's Blob has no text(); its FileReader reads it.
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.readAsText(blob)
    })
    expect(text).toContain('Adverse events,v1,v2,M-001')
    expect(click).toHaveBeenCalled()
    click.mockRestore()
    vi.unstubAllGlobals()
  })

  it('drops the preview when the selection changes, so a run always matches what was shown', async () => {
    getByUrl()
    const wrapper = await mountView()
    await choose(wrapper)
    await preview(wrapper)
    await wrapper.find('[data-testid="mig-confirm"]').setValue(true)

    await wrapper.find('#mig-subject').setValue('M-001')

    expect(wrapper.find('[data-testid="mig-preview-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="mig-run"]').exists()).toBe(false)
  })

  it('narrows the move to one event CRF with "Only this one"', async () => {
    getByUrl()
    const wrapper = await mountView()
    await choose(wrapper)
    await preview(wrapper)

    vi.mocked(apiPost).mockResolvedValueOnce({ ...PREVIEW, eventCrfCount: 1, eventCrfs: [row(2, 'M-002')] })
    await wrapper.find('[data-testid="mig-only-2"]').trigger('click')
    await flushPromises()

    expect(apiPost).toHaveBeenLastCalledWith('/pages/api/v1/crfs/F_AE/event-crf-migration/preview',
      expect.objectContaining({ eventCrfIds: [2] }))
    expect(wrapper.find('[data-testid="mig-only-chip"]').text()).toContain('Only event CRF #2')
    expect(wrapper.find('[data-testid="mig-preview-summary"]').text()).toContain('1 event CRF(s)')
  })

  it('shows the refusal of a run whose selection changed, and keeps the preview', async () => {
    getByUrl()
    const wrapper = await mountView()
    await choose(wrapper)
    await preview(wrapper)
    await wrapper.find('[data-testid="mig-confirm"]').setValue(true)

    vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(409, 'Conflict', {
      message: 'The selection changed since the preview: 3 event CRF(s) would move now, the preview showed 2. Nothing was changed; preview again.',
    }))
    await wrapper.find('[data-testid="mig-run"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="mig-error"]').text()).toContain('Nothing was changed')
    expect(wrapper.find('[data-testid="mig-result"]').exists()).toBe(false)
  })

  it('shows a refused move in its own section and still offers the default-version change', async () => {
    getByUrl(new ApiError(403, 'Forbidden', {
      message: "Only a Data Manager or CRC of study 'Default Study' may move event CRFs to another CRF version",
    }))
    const wrapper = await mountView()

    expect(wrapper.find('[data-testid="crf-migration-options-error"]').text()).toContain('Only a Data Manager or CRC')
    expect(wrapper.find('#mig-source').exists()).toBe(false)
    expect(wrapper.find('#def-from').exists()).toBe(true)
  })

  it('changes the default version through migrate-to, dry run first', async () => {
    getByUrl()
    const wrapper = await mountView()
    await wrapper.find('#def-from').setValue('F_AE_V1')
    await wrapper.find('#def-to').setValue('F_AE_V2')

    const planned = {
      crfOid: 'F_AE', fromVersionOid: 'F_AE_V1', toVersionOid: 'F_AE_V2', dryRun: true, totalMigrated: 1,
      perSed: [{ studyOid: 'S_DEFAULTS1', sedOid: 'SE_V1', sedName: 'V1 Inclusion', migrated: true, reasonSkipped: null }],
    }
    vi.mocked(apiPost).mockResolvedValueOnce(planned)
    await wrapper.find('[data-testid="def-preview"]').trigger('click')
    await flushPromises()
    expect(apiPost).toHaveBeenLastCalledWith('/pages/api/v1/crfs/F_AE/versions/F_AE_V1/migrate-to/F_AE_V2', { dryRun: true })
    expect(wrapper.find('[data-testid="def-preview-panel"]').text()).toContain('V1 Inclusion')

    vi.mocked(apiPost).mockResolvedValueOnce({ ...planned, dryRun: false })
    await wrapper.find('[data-testid="def-commit"]').trigger('click')
    await flushPromises()
    expect(apiPost).toHaveBeenLastCalledWith('/pages/api/v1/crfs/F_AE/versions/F_AE_V1/migrate-to/F_AE_V2', { dryRun: false })
    expect(wrapper.find('[data-testid="def-committed"]').text()).toContain('Migrated 1 event definition(s)')
  })
})

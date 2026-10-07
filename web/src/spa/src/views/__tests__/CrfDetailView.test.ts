/**
 * CrfDetailView — the CRF view: versions, the item table with the integrity
 * check, and the studies using the CRF (legacy ViewCRF).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import en from '@/locales/en.json'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { ApiError, apiGet } from '@/api/client'
import CrfDetailView from '@/views/CrfDetailView.vue'
import type { CrfDetail } from '@/types/crfLibrary'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en },
})

const DETAIL: CrfDetail = {
  oid: 'F_AE',
  name: 'Adverse events',
  description: 'AE log',
  status: 'available',
  mayEdit: true,
  versions: [
    { oid: 'F_AE_V1', name: 'v1', description: 'first', revisionNotes: '', status: 'locked', uploadedAt: null },
    { oid: 'F_AE_V2', name: 'v2', description: 'second', revisionNotes: 'moved grade', status: 'available', uploadedAt: null },
  ],
  items: [
    { name: 'AE_TERM', oid: 'I_AE_TERM', description: 'Term', dataType: 'ST', versions: ['v1', 'v2'], integrity: 'ok', placements: [] },
    {
      name: 'AE_GRADE',
      oid: 'I_AE_GRADE',
      description: 'Grade',
      dataType: 'INT',
      versions: ['v1', 'v2'],
      integrity: 'problem',
      placements: [
        { groupLabel: 'AE_MAIN', versionName: 'v1' },
        { groupLabel: 'AE_DETAIL', versionName: 'v2' },
      ],
    },
    {
      name: 'AE_OLD',
      oid: 'I_AE_OLD',
      description: '',
      dataType: 'DATE',
      versions: ['v1'],
      integrity: 'warning',
      placements: [{ groupLabel: 'A', versionName: 'v0' }, { groupLabel: 'B', versionName: 'v1' }],
    },
  ],
  studies: [
    { oid: 'S_DEFAULTS1', name: 'Default Study', uniqueProtocolId: 'default-study', status: 'available', parentOid: null, parentName: null },
    { oid: 'S_SITE_A', name: 'Site A', uniqueProtocolId: 'site-a', status: 'available', parentOid: 'S_DEFAULTS1', parentName: 'Default Study' },
  ],
}

async function mountAt(oid: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/crf-library', name: 'crf-library', component: { template: '<div />' } },
      { path: '/crf-library/:crfOid', name: 'crf-detail', component: CrfDetailView },
    ],
  })
  await router.push(`/crf-library/${oid}`)
  await router.isReady()
  const wrapper = mount(CrfDetailView, {
    global: { plugins: [pinia, router, i18n], stubs: { BuildStudyRail: true } },
  })
  await flushPromises()
  return wrapper
}

describe('CrfDetailView', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
  })

  it('loads the CRF named in the route and lists its versions', async () => {
    vi.mocked(apiGet).mockResolvedValue(DETAIL)
    const wrapper = await mountAt('F_AE')

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/crfs/F_AE')
    expect(wrapper.text()).toContain('Adverse events')
    expect(wrapper.text()).toContain('F_AE_V2')
    expect(wrapper.text()).toContain('moved grade')
  })

  it('shows each item with its data type, versions and integrity check', async () => {
    vi.mocked(apiGet).mockResolvedValue(DETAIL)
    const wrapper = await mountAt('F_AE')

    const ok = wrapper.find('[data-testid="crf-detail-item-I_AE_TERM"]')
    expect(ok.text()).toContain('ST')
    expect(ok.text()).toContain('v1, v2')
    expect(ok.text()).toContain('OK')

    const problem = wrapper.find('[data-testid="crf-detail-item-I_AE_GRADE"]')
    expect(problem.text()).toContain('Problem')
    expect(problem.text()).toContain('Group "AE_MAIN" in version "v1"')
    expect(problem.text()).toContain('Group "AE_DETAIL" in version "v2"')

    expect(wrapper.find('[data-testid="crf-detail-item-I_AE_OLD"]').text()).toContain('Warning')
    expect(wrapper.find('[data-testid="crf-detail-integrity-summary"]').text())
      .toContain('2 item(s) change their item group')
  })

  it('lists the studies and sites whose event definitions use the CRF', async () => {
    vi.mocked(apiGet).mockResolvedValue(DETAIL)
    const wrapper = await mountAt('F_AE')

    const studies = wrapper.find('[data-testid="crf-detail-studies"]').text()
    expect(studies).toContain('Default Study')
    expect(studies).toContain('default-study')
    expect(studies).toContain('Site A')
    expect(studies).toContain('site of Default Study')
  })

  it('shows the server\'s refusal instead of the view', async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError(403, 'Forbidden', {
      message: 'Your role does not permit managing CRFs — sysadmin or Director/Coordinator only',
    }))
    const wrapper = await mountAt('F_AE')

    expect(wrapper.find('[data-testid="crf-detail-error"]').text()).toContain('does not permit managing CRFs')
    expect(wrapper.find('[data-testid="crf-detail-items"]').exists()).toBe(false)
  })
})

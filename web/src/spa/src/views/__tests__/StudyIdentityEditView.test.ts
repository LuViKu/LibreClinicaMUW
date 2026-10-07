/**
 * StudyIdentityEditView — the detailed description, collaborators and
 * contact e-mail, which the API accepted but the form never bound, and
 * the study-metadata (ODM) download.
 *
 * The three fields are pre-filled from the study, sent on save, and
 * cleared when emptied (all three are optional).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})
vi.mock('@/api/download', () => ({ apiDownload: vi.fn() }))

import { apiGet, apiPut } from '@/api/client'
import { apiDownload } from '@/api/download'
import StudyIdentityEditView from '@/views/StudyIdentityEditView.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const IDENTITY = {
  oid: 'S_EDIT', name: 'Edit Study', uniqueProtocolId: 'edit', briefSummary: 'Summary',
  principalInvestigator: 'Dr. E', sponsor: 'MUW', officialTitle: '', secondaryProtocolId: '',
  collaborators: 'AKH Wien', protocolDescription: 'The long description.', contactEmail: 'pm@example.org',
  protocolType: 'observational', phase: '', status: 'available', parentStudyOid: null, parentStudyName: null,
  datePlannedStart: null,
}

async function mountView() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/studies/:oid/edit', component: StudyIdentityEditView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/studies/S_EDIT/edit')
  await router.isReady()
  const w = mount(StudyIdentityEditView, { global: { plugins: [router, i18n], stubs: { BuildStudyRail: true } } })
  await flushPromises()
  return w
}

describe('StudyIdentityEditView', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPut).mockReset()
    vi.mocked(apiDownload).mockReset()
    vi.mocked(apiGet).mockResolvedValue(IDENTITY)
    vi.mocked(apiPut).mockResolvedValue(IDENTITY)
  })

  it('pre-fills the detailed description, collaborators and contact e-mail', async () => {
    const w = await mountView()
    expect((w.get('#study-description').element as HTMLTextAreaElement).value).toBe('The long description.')
    expect((w.get('#study-collaborators').element as HTMLInputElement).value).toBe('AKH Wien')
    expect((w.get('#study-contact-email').element as HTMLInputElement).value).toBe('pm@example.org')
  })

  it('sends them on save, and an emptied one as empty so it is cleared', async () => {
    const w = await mountView()
    await w.get('#study-collaborators').setValue('')
    await w.get('#study-contact-email').setValue('trials@example.org')
    await w.findAll('button').find((b) => b.text() === 'Save changes')!.trigger('click')
    await flushPromises()

    expect(apiPut).toHaveBeenCalledTimes(1)
    const [path, patch] = vi.mocked(apiPut).mock.calls[0] as [string, Record<string, string>]
    expect(path).toBe('/pages/api/v1/studies/S_EDIT')
    expect(patch.collaborators).toBe('')
    expect(patch.contactEmail).toBe('trials@example.org')
    expect(patch.protocolDescription).toBe('The long description.')
  })

  it('downloads the study metadata', async () => {
    const w = await mountView()
    await w.get('[data-testid="study-edit-download-metadata"]').trigger('click')
    await flushPromises()
    expect(apiDownload).toHaveBeenCalledWith('/pages/api/v1/studies/S_EDIT/metadata', 'S_EDIT_metadata.xml')
  })
})

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
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'
import type { AuthenticatedUser, LocationMode } from '@/types/auth'
import type { LocationOption } from '@/types/location'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const IDENTITY = {
  oid: 'S_EDIT', name: 'Edit Study', uniqueProtocolId: 'edit', briefSummary: 'Summary',
  principalInvestigator: 'Dr. E', sponsor: 'MUW', officialTitle: '', secondaryProtocolId: '',
  collaborators: 'AKH Wien', protocolDescription: 'The long description.', contactEmail: 'pm@example.org',
  protocolType: 'observational', phase: '', status: 'available', parentStudyOid: null, parentStudyName: null,
  datePlannedStart: null,
}

async function mountView(mode?: LocationMode) {
  setActivePinia(createPinia())
  if (mode) {
    useAuthStore().user = {
      username: 'root', displayName: 'Root', role: 'Administrator', userType: 'SYSADMIN',
      profileComplete: true, mustChangePassword: false, manageableLocations: [], activeStudy: null,
      locationMode: mode,
    } as unknown as AuthenticatedUser
  }
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

function option(id: number, name: string): LocationOption {
  return { id, code: `L${id}`, name, street: null, city: null, zip: null, country: null }
}

describe('StudyIdentityEditView — Standort (DR-040 phase 4)', () => {
  const LOCATED = { ...IDENTITY, locationId: 1, locationCode: 'L1', locationName: 'MUW Augenklinik' }

  function answer(identity: typeof LOCATED | typeof IDENTITY, locations: LocationOption[]) {
    vi.mocked(apiGet).mockImplementation(async (path: string) => {
      if (path === '/pages/api/v1/locations') return structuredClone(locations)
      if (path === '/pages/api/v1/studies/S_EDIT') return structuredClone(identity)
      throw new Error(`unexpected GET ${path}`)
    })
  }

  async function save(w: Awaited<ReturnType<typeof mountView>>) {
    await w.findAll('button').find((b) => b.text() === 'Save changes')!.trigger('click')
    await flushPromises()
    return vi.mocked(apiPut).mock.calls[0][1] as Record<string, unknown>
  }

  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPut).mockReset()
    vi.mocked(apiPut).mockResolvedValue(LOCATED)
  })

  it('has no Standort select in single mode, and sends no locationId', async () => {
    answer(LOCATED, [option(1, 'MUW Augenklinik'), option(2, 'Klinik Graz')])
    const w = await mountView('single')
    expect(w.find('#study-location').exists()).toBe(false)
    expect(apiGet).not.toHaveBeenCalledWith('/pages/api/v1/locations')
    expect(await save(w)).not.toHaveProperty('locationId')
  })

  it('prefills the select from the study’s location in multi mode and sends it', async () => {
    answer(LOCATED, [option(2, 'Klinik Graz'), option(1, 'MUW Augenklinik')])
    const w = await mountView('multi')
    expect((w.get('#study-location').element as HTMLSelectElement).value).toBe('1')
    expect(w.get('[data-testid="study-location-field"]').text()).toContain(enMessages.studyForm.locationHint)
    expect((await save(w)).locationId).toBe(1)
  })

  it('sends the newly chosen location', async () => {
    answer(LOCATED, [option(2, 'Klinik Graz'), option(1, 'MUW Augenklinik')])
    const w = await mountView('multi')
    await w.get('#study-location').setValue('2')
    expect((await save(w)).locationId).toBe(2)
  })

  it('shows a stored location outside the options instead of an empty select', async () => {
    answer(LOCATED, [option(2, 'Klinik Graz')])
    const w = await mountView('multi')
    const select = w.get('#study-location').element as HTMLSelectElement
    expect(select.value).toBe('1')
    expect(Array.from(select.options).map((o) => o.text)).toEqual(['MUW Augenklinik', 'Klinik Graz'])
  })

  it('sends no locationId for a study without one until one is chosen', async () => {
    answer(IDENTITY, [option(2, 'Klinik Graz'), option(1, 'MUW Augenklinik')])
    const w = await mountView('multi')
    const select = w.get('#study-location').element as HTMLSelectElement
    expect(select.value).toBe('')
    expect(select.options[0].text).toBe(enMessages.studyForm.locationNone)
    expect(await save(w)).not.toHaveProperty('locationId')
  })
})

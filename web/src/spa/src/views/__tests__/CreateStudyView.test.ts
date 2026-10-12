/**
 * #7/#12 (2026-08-12) — CreateStudyView pre-submit validation.
 * Locks the contract: submitting with required fields blank shows inline
 * errors + a summary and does NOT call the backend; a fully-filled form
 * submits exactly once.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})

import { apiGet } from '@/api/client'
import CreateStudyView from '@/views/CreateStudyView.vue'
import { useStudyStore } from '@/stores/study'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'
import type { AuthenticatedUser, LocationMode } from '@/types/auth'
import type { LocationOption } from '@/types/location'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/build-study', name: 'build-study', component: { template: '<div />' } },
    ],
  })
}

async function mountView() {
  const router = makeRouter()
  router.push('/')
  await router.isReady()
  return mount(CreateStudyView, { global: { plugins: [router, i18n] } })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(apiGet).mockReset()
})

function option(id: number, name: string): LocationOption {
  return { id, code: `L${id}`, name, street: null, city: null, zip: null, country: null }
}

/** A system administrator in the given location mode, with these locations to choose from. */
function signIn(mode: LocationMode, locations: LocationOption[]) {
  const auth = useAuthStore()
  auth.user = {
    username: 'root', displayName: 'Root', role: 'Administrator', userType: 'SYSADMIN',
    profileComplete: true, mustChangePassword: false, manageableLocations: [], activeStudy: null,
    locationMode: mode,
  } as unknown as AuthenticatedUser
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === '/pages/api/v1/locations') return structuredClone(locations)
    throw new Error(`unexpected GET ${path}`)
  })
}

async function fillRequired(w: VueWrapper) {
  await w.get('#study-name').setValue('Glaucoma Cohort')
  await w.get('#study-uid').setValue('GLAU01')
  await w.get('#study-summary').setValue('A study')
  await w.get('#study-pi').setValue('Dr. Vision')
  await w.get('#study-sponsor').setValue('MUW')
}

describe('CreateStudyView — pre-submit validation', () => {
  it('blocks submit and shows errors when required fields are blank', async () => {
    const w = await mountView()
    const createSpy = vi.spyOn(useStudyStore(), 'create')

    const submit = w.findAll('button').find((b) => b.text() === 'Create study')!
    await submit.trigger('click')
    await flushPromises()

    expect(createSpy).not.toHaveBeenCalled()
    expect(w.text()).toContain('Required field')
    expect(w.text()).toContain('Please complete the highlighted required fields.')
  })

  it('submits once when every required field is filled', async () => {
    const w = await mountView()
    const studies = useStudyStore()
    const createSpy = vi
      .spyOn(studies, 'create')
      .mockResolvedValue({ ok: true, study: { oid: 'S_NEW' } } as never)
    vi.spyOn(useAuthStore(), 'pickStudy').mockResolvedValue(undefined as never)

    await w.get('#study-name').setValue('Glaucoma Cohort')
    await w.get('#study-uid').setValue('GLAU01')
    await w.get('#study-summary').setValue('A study')
    await w.get('#study-pi').setValue('Dr. Vision')
    await w.get('#study-sponsor').setValue('MUW')

    await w.findAll('button').find((b) => b.text() === 'Create study')!.trigger('click')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledTimes(1)
  })

  it('sends the detailed description, collaborators and contact e-mail', async () => {
    const w = await mountView()
    const createSpy = vi
      .spyOn(useStudyStore(), 'create')
      .mockResolvedValue({ ok: true, study: { oid: 'S_NEW' } } as never)
    vi.spyOn(useAuthStore(), 'pickStudy').mockResolvedValue(undefined as never)

    await w.get('#study-name').setValue('Glaucoma Cohort')
    await w.get('#study-uid').setValue('GLAU01')
    await w.get('#study-summary').setValue('A study')
    await w.get('#study-pi').setValue('Dr. Vision')
    await w.get('#study-sponsor').setValue('MUW')
    await w.get('#study-description').setValue('Randomised, double-masked, 24 months.')
    await w.get('#study-collaborators').setValue('AKH Wien')
    await w.get('#study-contact-email').setValue('pm@example.org')

    await w.findAll('button').find((b) => b.text() === 'Create study')!.trigger('click')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledWith(expect.objectContaining({
      protocolDescription: 'Randomised, double-masked, 24 months.',
      collaborators: 'AKH Wien',
      contactEmail: 'pm@example.org',
    }))
  })

  it('shows the server’s verdict on a malformed contact e-mail next to the field', async () => {
    const w = await mountView()
    vi.spyOn(useStudyStore(), 'create').mockResolvedValue({
      ok: false,
      fieldErrors: { contactEmail: 'Contact e-mail must be a valid e-mail address' },
      message: 'Validation failed',
    })

    await w.get('#study-name').setValue('Glaucoma Cohort')
    await w.get('#study-uid').setValue('GLAU01')
    await w.get('#study-summary').setValue('A study')
    await w.get('#study-pi').setValue('Dr. Vision')
    await w.get('#study-sponsor').setValue('MUW')
    await w.get('#study-contact-email').setValue('nobody')

    await w.findAll('button').find((b) => b.text() === 'Create study')!.trigger('click')
    await flushPromises()

    expect(w.text()).toContain('Contact e-mail must be a valid e-mail address')
  })
})

describe('CreateStudyView — Standort (DR-040 phase 4)', () => {
  it('has no Standort select in single mode, and sends no locationId', async () => {
    signIn('single', [option(1, 'MUW Augenklinik')])
    const w = await mountView()
    await flushPromises()
    expect(w.find('#study-location').exists()).toBe(false)
    expect(apiGet).not.toHaveBeenCalled()

    const createSpy = vi
      .spyOn(useStudyStore(), 'create')
      .mockResolvedValue({ ok: true, study: { oid: 'S_NEW' } } as never)
    vi.spyOn(useAuthStore(), 'pickStudy').mockResolvedValue(undefined as never)
    await fillRequired(w)
    await w.findAll('button').find((b) => b.text() === 'Create study')!.trigger('click')
    await flushPromises()
    expect(createSpy).toHaveBeenCalledTimes(1)
    expect(createSpy.mock.calls[0][0].locationId).toBeUndefined()
  })

  it('requires a Standort in multi mode and names the coordinating location in its hint', async () => {
    signIn('multi', [option(2, 'Klinik Graz'), option(1, 'MUW Augenklinik')])
    const w = await mountView()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/locations')
    const field = w.get('[data-testid="study-location-field"]')
    expect(field.text()).toContain(enMessages.studyForm.location)
    expect(field.text()).toContain(enMessages.studyForm.locationHint)
    expect((w.get('#study-location').element as HTMLSelectElement).value).toBe('')

    const createSpy = vi.spyOn(useStudyStore(), 'create')
    await fillRequired(w)
    await w.findAll('button').find((b) => b.text() === 'Create study')!.trigger('click')
    await flushPromises()
    expect(createSpy).not.toHaveBeenCalled()
    expect(w.get('[data-testid="study-location-error"]').text()).toContain('Required field')
  })

  it('sends the chosen Standort in multi mode', async () => {
    signIn('multi', [option(2, 'Klinik Graz'), option(1, 'MUW Augenklinik')])
    const w = await mountView()
    await flushPromises()
    const createSpy = vi
      .spyOn(useStudyStore(), 'create')
      .mockResolvedValue({ ok: true, study: { oid: 'S_NEW' } } as never)
    vi.spyOn(useAuthStore(), 'pickStudy').mockResolvedValue(undefined as never)
    await fillRequired(w)
    await w.get('#study-location').setValue('2')
    await w.findAll('button').find((b) => b.text() === 'Create study')!.trigger('click')
    await flushPromises()
    expect(createSpy).toHaveBeenCalledWith(expect.objectContaining({ locationId: 2 }))
  })

  it('preselects the one Standort there is in multi mode', async () => {
    signIn('multi', [option(1, 'MUW Augenklinik')])
    const w = await mountView()
    await flushPromises()
    expect((w.get('#study-location').element as HTMLSelectElement).value).toBe('1')
  })

  it('shows the server’s locationId error on the select', async () => {
    signIn('multi', [option(2, 'Klinik Graz'), option(1, 'MUW Augenklinik')])
    const w = await mountView()
    await flushPromises()
    vi.spyOn(useStudyStore(), 'create').mockResolvedValue({
      ok: false,
      fieldErrors: { locationId: 'Location is not available' },
      message: 'Validation failed',
    })
    await fillRequired(w)
    await w.get('#study-location').setValue('1')
    await w.findAll('button').find((b) => b.text() === 'Create study')!.trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="study-location-error"]').text()).toContain('Location is not available')
  })
})

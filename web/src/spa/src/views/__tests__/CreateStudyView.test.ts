/**
 * #7/#12 (2026-08-12) — CreateStudyView pre-submit validation.
 * Locks the contract: submitting with required fields blank shows inline
 * errors + a summary and does NOT call the backend; a fully-filled form
 * submits exactly once.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createI18n } from 'vue-i18n'

import CreateStudyView from '@/views/CreateStudyView.vue'
import { useStudyStore } from '@/stores/study'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'

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

beforeEach(() => setActivePinia(createPinia()))

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
})

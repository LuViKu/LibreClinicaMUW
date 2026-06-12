/**
 * Phase E.6 build-study tracker — operator-discretion task ack tests.
 *
 * Pins the SPA-side surface that the controller's
 * BuildStudyAcknowledgeDatabaseIT pairs with:
 *  - tasks whose status is "complete" (auto-complete on count>0, or
 *    "create-study" sentinel) render no ack button.
 *  - tasks whose status is "optional" and id is one of
 *    {groups, rules, sites} render the ack button + invoke the store
 *    action on click.
 *  - non-ack-eligible tasks (e.g. "crf" / "events") never render the
 *    ack button regardless of status.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
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

import BuildStudyView from '@/views/BuildStudyView.vue'
import { useStudyStore } from '@/stores/study'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'
import type { StudyBuildStatus } from '@/types/study'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/build-study', name: 'build-study', component: { template: '<div />' } },
      { path: '/manage-users', name: 'manage-users', component: { template: '<div />' } },
      { path: '/group-classes', name: 'group-classes', component: { template: '<div />' } },
      { path: '/rules', name: 'rules', component: { template: '<div />' } },
      { path: '/sites', name: 'sites', component: { template: '<div />' } },
      { path: '/event-definitions', name: 'event-definitions', component: { template: '<div />' } },
      { path: '/crf-library', name: 'crf-library', component: { template: '<div />' } },
      { path: '/studies/:oid/edit', name: 'study-edit', component: { template: '<div />' } },
      { path: '/studies/:oid/parameters', name: 'study-parameters', component: { template: '<div />' } },
      { path: '/studies/new', name: 'study-new', component: { template: '<div />' } },
    ],
  })
}

function buildStatus(overrides: Partial<StudyBuildStatus['tasks'][number]>[] = []): StudyBuildStatus {
  const base: StudyBuildStatus = {
    studyOid: 'S_DEFAULTS1',
    studyName: 'Default Study',
    studyVersion: '1.0',
    sites: 0,
    enrolledSubjects: 0,
    tasks: [
      { id: 'create-study', count: null, status: 'complete', to: null },
      { id: 'crf', count: 5, status: 'complete', to: null },
      { id: 'events', count: 3, status: 'complete', to: null },
      { id: 'groups', count: 0, status: 'optional', to: null },
      { id: 'rules', count: 0, status: 'optional', to: null },
      { id: 'sites', count: 0, status: 'optional', to: null },
      { id: 'users', count: 2, status: 'complete', to: '/manage-users' },
    ],
  }
  for (const override of overrides) {
    const idx = base.tasks.findIndex((t) => t.id === override.id)
    if (idx >= 0) base.tasks[idx] = { ...base.tasks[idx], ...override }
  }
  return base
}

async function mountView(status: StudyBuildStatus) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = makeRouter()
  await router.push('/build-study')
  await router.isReady()

  // Seed auth + study stores so the view skips its onMounted load.
  const auth = useAuthStore()
  auth.user = {
    id: 1,
    username: 'root',
    name: 'Root User',
    role: 'Administrator',
    activeStudy: { oid: 'S_DEFAULTS1', name: 'Default Study' },
  } as unknown as typeof auth.user

  const study = useStudyStore()
  study.status = status

  const wrapper = mount(BuildStudyView, {
    global: { plugins: [pinia, router, i18n] },
  })
  await flushPromises()
  return { wrapper, study }
}

describe('BuildStudyView — operator-discretion ack', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('does not render the ack button on tasks whose status is complete', async () => {
    // All three optional tasks acknowledged (or non-zero count) — no
    // ack button should render at all.
    const { wrapper } = await mountView(
      buildStatus([
        { id: 'groups', count: 1, status: 'complete' },
        { id: 'rules', count: 2, status: 'complete' },
        { id: 'sites', count: 1, status: 'complete' },
      ]),
    )
    const buttons = wrapper.findAll('button').filter((b) => b.text() === 'Mark as complete')
    expect(buttons).toHaveLength(0)
  })

  it('renders the ack button on optional groups/rules/sites tasks and invokes the store on click', async () => {
    const { wrapper, study } = await mountView(buildStatus())
    const ackSpy = vi
      .spyOn(study, 'acknowledgeTask')
      .mockResolvedValue({ ok: true })

    const buttons = wrapper.findAll('button').filter((b) => b.text() === 'Mark as complete')
    // groups + rules + sites — three optional cards, three buttons.
    expect(buttons).toHaveLength(3)

    await buttons[0].trigger('click')
    await flushPromises()
    expect(ackSpy).toHaveBeenCalledWith('S_DEFAULTS1', 'groups')
  })

  it('does not render the ack button on non-ack-eligible tasks even if status is optional', async () => {
    // Force "crf" into optional status (synthetic — backend would never
    // emit this, but the guard should hold anyway).
    const { wrapper } = await mountView(
      buildStatus([{ id: 'crf', count: 0, status: 'optional' }]),
    )
    // No button labelled "Mark as complete" on the CRF card — gated by
    // isAckTaskId() in the template.
    const liItems = wrapper.findAll('li')
    const crfLi = liItems.find((li) => li.text().includes('Design CRFs'))
    expect(crfLi).toBeDefined()
    const crfButtons = crfLi!.findAll('button').filter((b) => b.text() === 'Mark as complete')
    expect(crfButtons).toHaveLength(0)
  })

  it('surfaces an error banner when the ack store call fails', async () => {
    const { wrapper, study } = await mountView(buildStatus())
    vi.spyOn(study, 'acknowledgeTask').mockResolvedValue({
      ok: false,
      message: 'Boom.',
    })
    const buttons = wrapper.findAll('button').filter((b) => b.text() === 'Mark as complete')
    await buttons[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Boom.')
  })
})

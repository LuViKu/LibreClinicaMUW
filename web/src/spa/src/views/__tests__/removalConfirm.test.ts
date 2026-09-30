/**
 * Removing an event definition or a group class takes the rows under it
 * with it (the legacy cascade). The confirm prompt must say so, and give
 * the numbers the server's removal-impact endpoint reports.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn(() => Promise.resolve(false)) }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { apiGet, apiPost } from '@/api/client'
import EventDefinitionsView from '@/views/EventDefinitionsView.vue'
import GroupClassesView from '@/views/GroupClassesView.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

const STUDY = 'S_DEFAULTS1'

const EVENT_DEFINITION = {
  sedId: 1, oid: 'SE_V1', name: 'V1 Inclusion', description: '', category: '',
  type: 'scheduled', repeating: false, ordinal: 1, status: 'available',
}
const GROUP_CLASS = {
  id: 7, name: 'Treatment arm', groupClassType: 'Arm', subjectAssignment: 'OPTIONAL',
  status: 'available', groups: [
    { id: 1, name: 'A', description: '', status: 'available' },
    { id: 2, name: 'B', description: '', status: 'available' },
  ],
}

/** apiGet answers by path; an impact of null makes that request fail. */
function routeApiGet(impact: object | null) {
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path.endsWith('/removal-impact')) {
      if (impact == null) throw new Error('unavailable')
      return impact as never
    }
    if (path.includes('/event-definitions')) return [EVENT_DEFINITION] as never
    if (path.includes('/group-classes')) return [GROUP_CLASS] as never
    return [] as never
  })
}

async function mountAs(view: unknown) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:any(.*)*', component: { template: '<div />' } }],
  })
  const auth = useAuthStore()
  auth.user = {
    id: 1, username: 'manual_dm', name: 'Data Manager', role: 'Data Manager',
    activeStudy: { oid: STUDY, name: 'Default Study' },
  } as unknown as typeof auth.user
  const wrapper = mount(view as never, {
    global: { plugins: [pinia, router, i18n], stubs: { BuildStudyRail: true } },
  })
  await flushPromises()
  return wrapper
}

async function clickRemove(wrapper: Awaited<ReturnType<typeof mountAs>>) {
  const button = wrapper.findAll('button').find((b) => b.text() === 'Remove')
  expect(button, 'no Remove button rendered').toBeTruthy()
  await button!.trigger('click')
  await flushPromises()
}

function promptedMessage(): string {
  expect(confirmMock).toHaveBeenCalledTimes(1)
  return (confirmMock.mock.calls[0] as unknown as [{ message: string }])[0].message
}

describe('removal confirm prompts', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    confirmMock.mockReset()
    confirmMock.mockResolvedValue(false)
  })

  it('event definition: names the visits, CRFs and values removed with it, with counts', async () => {
    routeApiGet({ crfAssignments: 1, visits: 7, subjects: 6, eventCrfs: 5, itemValues: 27 })
    const wrapper = await mountAs(EventDefinitionsView)
    await clickRemove(wrapper)

    expect(apiGet).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY}/event-definitions/SE_V1/removal-impact`)
    const message = promptedMessage()
    expect(message).toContain('every visit scheduled from it')
    expect(message).toContain('1 CRF assignment(s), 7 visit(s) of 6 subject(s), 5 CRF(s) and 27 entered value(s)')
    // Cancelled: nothing is removed.
    expect(apiPost).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('event definition: still names the cascade when the counts are unavailable', async () => {
    routeApiGet(null)
    const wrapper = await mountAs(EventDefinitionsView)
    await clickRemove(wrapper)

    const message = promptedMessage()
    expect(message).toContain('every visit scheduled from it')
    expect(message).not.toContain('Removed with it')
    wrapper.unmount()
  })

  it('group class: names the subject assignments removed with it, with counts', async () => {
    routeApiGet({ groups: 2, subjectAssignments: 3 })
    const wrapper = await mountAs(GroupClassesView)
    await clickRemove(wrapper)

    expect(apiGet).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY}/group-classes/7/removal-impact`)
    const message = promptedMessage()
    expect(message).toContain('assignments of subjects to its groups are removed with it')
    expect(message).toContain('3 subject assignment(s) to its 2 group(s)')
    expect(apiPost).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

/**
 * The CRF removal dialog says what the removal takes out before it asks
 * why, removes only with a reason, and shows the server's refusal instead
 * of the form when the CRF cannot be removed.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import en from '@/locales/en.json'

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    isUnauthorized = false
    constructor(public status = 0, msg = '', public body: unknown = null) {
      super(msg)
      if (status === 401) this.isUnauthorized = true
    }
  }
  class ApiNetworkError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), ApiError, ApiNetworkError }
})

// The modal renders through a teleport in the real component; a stub keeps
// the dialog's own markup in the wrapper so the test can reach it.
vi.mock('@/components/Modal.vue', () => ({
  default: {
    props: ['open', 'labelledBy', 'panelClass'],
    template: '<div v-if="open"><slot name="header" /><slot /><slot name="footer" /></div>',
  },
}))

// eslint-disable-next-line import/first
import { apiGet, apiPost, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import RemoveEventCrfDialog from '../RemoveEventCrfDialog.vue'
// eslint-disable-next-line import/first
import type { EventCrfRowDto } from '@/types/event'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>
const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const crf: EventCrfRowDto = {
  eventCrfId: 9,
  eventCrfOid: '9',
  crfName: 'Demographics',
  crfVersionName: 'v1.0',
  crfVersionOid: 'F_DEMOGRAPHICS_V1',
  eventDefinitionCrfId: 1,
  status: 'data-entry-started',
  required: true,
  passwordRequired: false,
}

function mountDialog() {
  return mount(RemoveEventCrfDialog, {
    props: { open: true, crf, subjectLabel: 'M-004', eventLabel: 'V1 Inclusion' },
    global: { plugins: [i18n] },
  })
}

describe('RemoveEventCrfDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
    apiPostMock.mockReset()
  })

  it('names the CRF and what is removed with it before asking why', async () => {
    apiGetMock.mockResolvedValueOnce({ values: 2, openNoteThreads: 3 })
    const wrapper = mountDialog()
    await flushPromises()

    expect(apiGetMock).toHaveBeenCalledWith('/pages/api/v1/eventCrfs/9/removal-impact')
    const impact = wrapper.find('[data-testid="remove-event-crf-impact"]').text()
    expect(impact).toContain('Demographics')
    expect(impact).toContain('M-004')
    expect(impact).toContain('V1 Inclusion')
    expect(wrapper.find('[data-testid="remove-event-crf-values"]').text()).toContain('2')
    expect(wrapper.find('[data-testid="remove-event-crf-notes"]').text()).toContain('3')
  })

  it('removes with the reason given, and not without one', async () => {
    apiGetMock.mockResolvedValueOnce({ values: 2, openNoteThreads: 0 })
    apiPostMock.mockResolvedValueOnce(undefined)
    const wrapper = mountDialog()
    await flushPromises()

    const confirm = () => wrapper.find('[data-testid="remove-event-crf-confirm"]')
    expect(confirm().attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="remove-event-crf-notes"]').exists()).toBe(false)

    await wrapper.find('[data-testid="remove-event-crf-reason"]').setValue('  Entered on the wrong subject  ')
    expect(confirm().attributes('disabled')).toBeUndefined()
    await confirm().trigger('click')
    await flushPromises()

    expect(apiPostMock).toHaveBeenCalledWith('/pages/api/v1/eventCrfs/9/remove',
      { reason: 'Entered on the wrong subject' })
    expect(wrapper.emitted('removed')).toHaveLength(1)
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })

  it('shows the refusal instead of the form when the CRF cannot be removed', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(409, 'conflict',
      { message: 'event_crf 9 is signed or locked; unlock it before removing it' }))
    const wrapper = mountDialog()
    await flushPromises()

    expect(wrapper.find('[data-testid="remove-event-crf-refused"]').text()).toContain('signed or locked')
    expect(wrapper.find('[data-testid="remove-event-crf-reason"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="remove-event-crf-confirm"]').attributes('disabled')).toBeDefined()
  })

  it('stays open with the server message when the removal fails', async () => {
    apiGetMock.mockResolvedValueOnce({ values: 1, openNoteThreads: 0 })
    apiPostMock.mockRejectedValueOnce(new ApiError(409, 'conflict',
      { message: 'event_crf 9 changed meanwhile; reload the visit' }))
    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('[data-testid="remove-event-crf-reason"]').setValue('Duplicate entry')
    await wrapper.find('[data-testid="remove-event-crf-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="remove-event-crf-error"]').text()).toContain('changed meanwhile')
    expect(wrapper.emitted('removed')).toBeUndefined()
  })
})

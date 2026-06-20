/**
 * Phase E.8 Slice L2 (2026-06-20) — ContactView spec.
 *
 * Drives the form through happy + validation + 503 unavailable paths
 * with `@/api/client.apiPost` stubbed at the module boundary. Pinia is
 * set up so the auth-store imports in tree don't blow up when the view
 * is mounted; the view itself doesn't touch auth state.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en-US',
  fallbackLocale: 'en-US',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'en-US': enMessages },
})

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiPost: vi.fn(),
  }
})

import ContactView from '@/views/ContactView.vue'
import { apiPost, ApiError } from '@/api/client'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/login', name: 'login', component: { template: '<div />' } },
    { path: '/contact', name: 'contact', component: ContactView },
  ],
})

function mountView() {
  return mount(ContactView, {
    global: {
      plugins: [createPinia(), i18n, router],
    },
  })
}

async function fillValid(wrapper: ReturnType<typeof mountView>) {
  await wrapper.find('#contact-name').setValue('Anne Tester')
  await wrapper.find('#contact-email').setValue('anne@example.org')
  await wrapper.find('#contact-subject').setValue('CRF rendering issue')
  await wrapper.find('#contact-message').setValue('I see a blank panel on visit 3.')
}

describe('ContactView', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.mocked(apiPost).mockReset()
    router.push('/contact')
    await router.isReady()
  })

  it('renders the form heading and four labelled inputs', () => {
    const wrapper = mountView()
    expect(wrapper.text()).toContain('Contact the administrator')
    expect(wrapper.find('#contact-name').exists()).toBe(true)
    expect(wrapper.find('#contact-email').exists()).toBe(true)
    expect(wrapper.find('#contact-subject').exists()).toBe(true)
    expect(wrapper.find('#contact-message').exists()).toBe(true)
  })

  it('submits the form and shows the success card on 200', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({ delivered: true })
    const wrapper = mountView()
    await fillValid(wrapper)
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/contact',
      {
        name: 'Anne Tester',
        email: 'anne@example.org',
        subject: 'CRF rendering issue',
        message: 'I see a blank panel on visit 3.',
      },
    )
    expect(wrapper.text()).toContain('Your message has been sent.')
    // Form replaced — the message textarea must be gone.
    expect(wrapper.find('#contact-message').exists()).toBe(false)
  })

  it('surfaces per-field errors when the backend returns 400', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(400, 'Validation failed.', {
        message: 'Validation failed.',
        errors: [
          { field: 'email', message: 'Enter a valid email address.' },
          { field: 'message', message: 'Message is required.' },
        ],
      }),
    )
    const wrapper = mountView()
    await fillValid(wrapper)
    await wrapper.find('#contact-email').setValue('not-an-email')
    await wrapper.find('#contact-message').setValue('')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('Enter a valid email address.')
    expect(wrapper.text()).toContain('Message is required.')
    // Still on the form, NOT the success card.
    expect(wrapper.find('#contact-message').exists()).toBe(true)
  })

  it('shows the unavailable copy when the backend returns 503', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(503, 'Service unavailable', {
        message: 'Contact form is not currently accepting messages; please contact the sysadmin directly.',
      }),
    )
    const wrapper = mountView()
    await fillValid(wrapper)
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('not currently accepting messages')
    expect(wrapper.find('#contact-message').exists()).toBe(true)
  })
})

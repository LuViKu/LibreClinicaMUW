/**
 * Phase E.6 follow-up 2026-06-11 — bug-report dialog smoke test.
 *
 * The component's behaviour is covered end-to-end by manual smoke
 * (mail delivery + the success banner are easier to verify in compose
 * than to mock at the unit-test layer). The vitest pass here is a
 * mount + render smoke so that future template / i18n / Modal
 * refactors don't silently break the dialog.
 */
import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import BugReportDialog from '@/components/BugReportDialog.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { saving: 'Saving…' },
      bugReport: {
        title: 'Report a bug',
        dialog: {
          title: 'Report a bug',
          description: 'Describe the bug as clearly as possible.',
        },
        field: {
          title: { label: 'Title', placeholder: 'Short summary' },
          description: { label: 'Description', placeholder: 'What went wrong?' },
          reproductionSteps: { label: 'Reproduction steps (optional)', placeholder: 'Steps' },
        },
        submit: 'Send',
        cancel: 'Cancel',
        success: 'Thanks — ticket {ticketId} sent to the administrator.',
        error: {
          network: 'Could not send the report. Please try again.',
          recipientNotConfigured: 'Bug-report recipient is not configured.',
        },
      },
    },
  },
})

describe('BugReportDialog', () => {
  it('mounts cleanly when open=true and renders the form scaffolding', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(BugReportDialog, {
      props: { open: true },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    // Modal teleports the panel to <body>; query both the wrapper and the document.
    const hasTitleInput =
      wrapper.find('[data-testid="bug-report-title-input"]').exists() ||
      document.body.querySelector('[data-testid="bug-report-title-input"]') !== null
    const hasDescription =
      wrapper.find('[data-testid="bug-report-description"]').exists() ||
      document.body.querySelector('[data-testid="bug-report-description"]') !== null
    const hasSubmit =
      wrapper.find('[data-testid="bug-report-submit"]').exists() ||
      document.body.querySelector('[data-testid="bug-report-submit"]') !== null
    const hasCancel =
      wrapper.find('[data-testid="bug-report-cancel"]').exists() ||
      document.body.querySelector('[data-testid="bug-report-cancel"]') !== null

    expect(hasTitleInput).toBe(true)
    expect(hasDescription).toBe(true)
    expect(hasSubmit).toBe(true)
    expect(hasCancel).toBe(true)

    wrapper.unmount()
    document.body.innerHTML = ''
  })

  it('renders nothing when open=false', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(BugReportDialog, {
      props: { open: false },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    expect(
      document.body.querySelector('[data-testid="bug-report-submit"]'),
    ).toBeNull()

    wrapper.unmount()
    document.body.innerHTML = ''
  })
})

/**
 * Phase E.6 follow-up 2026-06-11 — bug-report dialog smoke test.
 *
 * The component's behaviour is covered end-to-end by manual smoke
 * (mail delivery + the success banner are easier to verify in compose
 * than to mock at the unit-test layer). The vitest pass here is a
 * mount + render smoke so that future template / i18n / Modal
 * refactors don't silently break the dialog.
 */
import { describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import BugReportDialog from '@/components/BugReportDialog.vue'
import { useBugReportsStore } from '@/stores/bugReports'
import { useClientLogsStore } from '@/stores/clientLogs'

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
        attach: {
          heading: 'Attach',
          pageUrl: 'Include current page ({url})',
          console: 'Include the last {n} console messages',
          preview: {
            toggle: 'Show preview',
            empty: '(no entries)',
          },
        },
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

  it('renders the page-URL label when attachPageUrl is true (default)', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(BugReportDialog, {
      props: { open: true },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    const checkbox = document.body.querySelector<HTMLInputElement>(
      '[data-testid="bug-report-attach-page-url"]',
    )
    expect(checkbox).not.toBeNull()
    expect(checkbox!.checked).toBe(true)

    const label = document.body.querySelector(
      '[data-testid="bug-report-attach-page-url-label"]',
    )
    expect(label).not.toBeNull()
    // jsdom defaults window.location.pathname to "/"; the label
    // interpolates it via the {url} placeholder.
    expect(label!.textContent).toContain('Include current page')
    expect(label!.textContent).toContain('/')

    wrapper.unmount()
    document.body.innerHTML = ''
  })

  it('omits consoleEntries from submit payload when attach toggle is unchecked', async () => {
    setActivePinia(createPinia())
    const logs = useClientLogsStore()
    logs.push({ level: 'error', message: 'sample error 1' })
    logs.push({ level: 'warn', message: 'sample warn 1' })

    const wrapper = mount(BugReportDialog, {
      props: { open: true },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    // Stub the store action — we only want to assert the payload shape.
    const store = useBugReportsStore()
    const submitSpy = vi
      .spyOn(store, 'submit')
      .mockResolvedValue('BUG-stub')

    // Fill required fields directly via the inputs. The title field is
    // wrapped in a TextInput component, so the data-testid lands on the
    // wrapper <div>; the actual <input> lives under the matching id.
    const titleEl = document.body.querySelector<HTMLInputElement>(
      'input#bug-report-title-input',
    )!
    titleEl.value = 'A title'
    titleEl.dispatchEvent(new Event('input', { bubbles: true }))

    const descEl = document.body.querySelector<HTMLTextAreaElement>(
      '[data-testid="bug-report-description"]',
    )!
    descEl.value = 'A description'
    descEl.dispatchEvent(new Event('input', { bubbles: true }))

    // Uncheck the console-attach toggle.
    const consoleCheckbox = document.body.querySelector<HTMLInputElement>(
      '[data-testid="bug-report-attach-console"]',
    )!
    consoleCheckbox.checked = false
    consoleCheckbox.dispatchEvent(new Event('change'))

    await flushPromises()

    const submitBtn = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="bug-report-submit"]',
    )!
    submitBtn.click()
    await flushPromises()

    expect(submitSpy).toHaveBeenCalledTimes(1)
    const payload = submitSpy.mock.calls[0][0]
    expect(payload.consoleEntries).toBeUndefined()
    // The page-URL toggle was left at its default (true).
    expect(payload.attachPageUrl).toBe(true)

    wrapper.unmount()
    document.body.innerHTML = ''
  })
})

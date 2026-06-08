/**
 * Phase E.6 — TransitionEyeDialog specs.
 *
 * <p>Pins the load-bearing contract for the per-eye cohort transition
 * affordance (iAMD → GA hand-off): visibility wiring, form-validity
 * gating on the confirm button, payload shape on emit, cancel paths
 * (button + Escape), and rendering of every candidate study the
 * parent supplies.
 *
 * <p>The dialog does NOT filter `availableStudies` against
 * `currentStudyOid` — that responsibility lives in the parent. The
 * fifth spec defends that boundary so a future copy-paste of the
 * filter into the dialog gets caught.
 *
 * <p>i18n keys are loaded with `missingWarn: false` so the parallel
 * i18n worktree's strings (which will land in en.json / de.json on
 * harmonization) don't generate console noise during local runs.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import TransitionEyeDialog from '@/components/TransitionEyeDialog.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

interface StudyOption {
  oid: string
  name: string
}

const STUDIES: StudyOption[] = [
  { oid: 'S_GA1', name: 'GA Observational 2026' },
  { oid: 'S_GA2', name: 'GA Treatment Arm B' },
  { oid: 'S_OTHER', name: 'Long-term Follow-up Registry' },
]

function mountDialog(
  overrides: Partial<{
    subjectLabel: string
    eye: 'OD' | 'OS'
    currentStudyOid: string
    availableStudies: StudyOption[]
    open: boolean
  }> = {},
) {
  return mount(TransitionEyeDialog, {
    props: {
      subjectLabel: 'M-001',
      eye: 'OD',
      currentStudyOid: 'S_IAMD',
      availableStudies: STUDIES,
      open: true,
      ...overrides,
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('TransitionEyeDialog', () => {
  beforeEach(() => {
    // Each spec mounts fresh; nothing to seed.
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the title and form when open=true, nothing when open=false', () => {
    const openWrapper = mountDialog({ open: true })
    expect(openWrapper.find('[data-testid="transition-eye-dialog"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-dialog-title"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-target-study"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-reason"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-submit"]').exists()).toBe(true)
    openWrapper.unmount()

    const closedWrapper = mountDialog({ open: false })
    expect(closedWrapper.find('[data-testid="transition-eye-dialog"]').exists()).toBe(false)
    expect(closedWrapper.find('[data-testid="transition-eye-submit"]').exists()).toBe(false)
    closedWrapper.unmount()
  })

  it('disables the submit button until both target study and reason are filled in', async () => {
    const wrapper = mountDialog()
    const submit = wrapper.find('[data-testid="transition-eye-submit"]')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    // Only a target study: still disabled.
    const select = wrapper.find<HTMLSelectElement>('#transition-eye-target-study')
    select.element.value = 'S_GA1'
    await select.trigger('change')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    // Only whitespace reason: still disabled.
    const reasonField = wrapper.find<HTMLTextAreaElement>('#transition-eye-reason')
    await reasonField.setValue('   ')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    // Non-blank reason + target → enabled.
    await reasonField.setValue('OCT zeigt GA-Schwelle überschritten')
    expect((submit.element as HTMLButtonElement).disabled).toBe(false)

    wrapper.unmount()
  })

  it('emits submit with the correct payload shape on confirm', async () => {
    const wrapper = mountDialog()

    const select = wrapper.find<HTMLSelectElement>('#transition-eye-target-study')
    select.element.value = 'S_GA2'
    await select.trigger('change')
    await wrapper.find<HTMLInputElement>('#transition-eye-target-label').setValue('  M-101  ')
    await wrapper.find<HTMLTextAreaElement>('#transition-eye-reason').setValue('  GA threshold crossed at visit 7  ')

    await wrapper.find('[data-testid="transition-eye-submit"]').trigger('click')

    const events = wrapper.emitted('submit')
    expect(events).toBeTruthy()
    expect(events).toHaveLength(1)
    // transitionedAt defaults to today (ISO YYYY-MM-DD) per the
    // retrospective-backfill rollout — assert the static fields plus
    // an ISO-date shape on transitionedAt so the test doesn't tie
    // itself to a wall-clock value.
    const payload = events![0][0] as {
      targetStudyOid: string
      targetLabel?: string
      reason: string
      transitionedAt?: string
    }
    expect(payload.targetStudyOid).toBe('S_GA2')
    expect(payload.targetLabel).toBe('M-101')
    expect(payload.reason).toBe('GA threshold crossed at visit 7')
    expect(payload.transitionedAt).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(wrapper.emitted('cancel')).toBeFalsy()

    // Confirms that an empty targetLabel is omitted from the payload.
    const wrapper2 = mountDialog()
    const select2 = wrapper2.find<HTMLSelectElement>('#transition-eye-target-study')
    select2.element.value = 'S_GA1'
    await select2.trigger('change')
    await wrapper2.find<HTMLTextAreaElement>('#transition-eye-reason').setValue('GA detected')
    await wrapper2.find('[data-testid="transition-eye-submit"]').trigger('click')
    const events2 = wrapper2.emitted('submit')
    expect(events2).toBeTruthy()
    const payload2 = events2![0][0] as {
      targetStudyOid: string
      targetLabel?: string
      reason: string
      transitionedAt?: string
    }
    expect(payload2.targetStudyOid).toBe('S_GA1')
    expect(payload2.reason).toBe('GA detected')
    expect(payload2.targetLabel).toBeUndefined()
    expect(payload2.transitionedAt).toMatch(/^\d{4}-\d{2}-\d{2}$/)

    wrapper.unmount()
    wrapper2.unmount()
  })

  it('emits cancel on the explicit cancel button and on Escape', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-testid="transition-eye-cancel"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('submit')).toBeFalsy()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    // Vue's async render isn't required for the emit itself — the
    // listener fires synchronously.
    expect(wrapper.emitted('cancel')).toHaveLength(2)
    expect(wrapper.emitted('submit')).toBeFalsy()

    wrapper.unmount()
  })

  it('renders every entry of availableStudies verbatim (parent owns the filter)', () => {
    // Include the "current" study in the list to confirm the dialog
    // does NOT silently drop it — the parent is responsible for that.
    const studiesIncludingCurrent: StudyOption[] = [
      { oid: 'S_IAMD', name: 'iAMD source cohort' },
      ...STUDIES,
    ]
    const wrapper = mountDialog({
      currentStudyOid: 'S_IAMD',
      availableStudies: studiesIncludingCurrent,
    })

    const select = wrapper.find<HTMLSelectElement>('#transition-eye-target-study')
    const optionValues = Array.from(select.element.options).map((o) => o.value)
    // First option is the placeholder ('') — the rest must match input order.
    expect(optionValues).toEqual([
      '',
      'S_IAMD',
      'S_GA1',
      'S_GA2',
      'S_OTHER',
    ])
    const optionLabels = Array.from(select.element.options).map((o) => o.textContent?.trim())
    expect(optionLabels).toContain('iAMD source cohort')
    expect(optionLabels).toContain('GA Observational 2026')
    expect(optionLabels).toContain('GA Treatment Arm B')
    expect(optionLabels).toContain('Long-term Follow-up Registry')

    wrapper.unmount()
  })
})

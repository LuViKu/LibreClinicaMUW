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
 * <p>2026-06-10 — extended with the alreadyEnrolled / new-enrollment
 * branched UI: picking a target study fires {@code transitionPreflight}
 * on the subjects store; the response drives whether the dialog shows
 * the new-enrollment panel (mandatory targetLabel + live uniqueness
 * check) or the already-enrolled info line. Tests stub the store via
 * the canonical Pinia testing helper.
 *
 * <p>i18n keys are loaded with `missingWarn: false` so the parallel
 * i18n worktree's strings (which will land in en.json / de.json on
 * harmonization) don't generate console noise during local runs.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'

import TransitionEyeDialog from '@/components/TransitionEyeDialog.vue'
import enMessages from '@/locales/en.json'
import { useSubjectsStore } from '@/stores/subjects'
import type { TransitionPreflight } from '@/types/subject'

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
  uniqueIdentifier?: string | null
}

const STUDIES: StudyOption[] = [
  { oid: 'S_GA1', name: 'GA Observational 2026', uniqueIdentifier: 'GA1' },
  { oid: 'S_GA2', name: 'GA Treatment Arm B', uniqueIdentifier: 'GA2' },
  { oid: 'S_OTHER', name: 'Long-term Follow-up Registry', uniqueIdentifier: null },
]

const NOT_ENROLLED: TransitionPreflight = {
  alreadyEnrolled: false,
  existingTargetOid: null,
  existingTargetLabel: null,
  labelAvailable: true,
  suggestedLabel: '',
}

const ALREADY_ENROLLED: TransitionPreflight = {
  alreadyEnrolled: true,
  existingTargetOid: 'SS_M001GA',
  existingTargetLabel: 'M-001',
  labelAvailable: true,
  suggestedLabel: '',
}

const LABEL_TAKEN: TransitionPreflight = {
  alreadyEnrolled: false,
  existingTargetOid: null,
  existingTargetLabel: null,
  labelAvailable: false,
  suggestedLabel: '',
}

function mountDialog(
  overrides: Partial<{
    subjectLabel: string
    eye: 'OD' | 'OS'
    currentStudyOid: string
    availableStudies: StudyOption[]
    open: boolean
  }> = {},
  preflightImpl?: (label: string, eye: 'OD' | 'OS', studyOid: string, targetLabel: string | null) =>
    Promise<TransitionPreflight>,
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  // Initialise the store + stub transitionPreflight BEFORE the component
  // mounts so the watcher fires the spy on first study pick. The store
  // is a singleton per Pinia instance, so the dialog gets the same one.
  const store = useSubjectsStore()
  const impl = preflightImpl ?? (async () => NOT_ENROLLED)
  store.transitionPreflight = vi.fn(impl)
  const wrapper = mount(TransitionEyeDialog, {
    props: {
      subjectLabel: 'M-001',
      eye: 'OD',
      currentStudyOid: 'S_IAMD',
      availableStudies: STUDIES,
      open: true,
      ...overrides,
    },
    global: { plugins: [i18n, pinia] },
    attachTo: document.body,
  })
  return { wrapper, store }
}

async function pickStudy(wrapper: ReturnType<typeof mount>, oid: string) {
  const select = wrapper.find<HTMLSelectElement>('#transition-eye-target-study')
  select.element.value = oid
  await select.trigger('change')
  // The watcher fires runPreflight asynchronously — let the microtask
  // queue drain so the preflight Promise resolves into local state.
  await flushPromises()
}

describe('TransitionEyeDialog', () => {
  beforeEach(() => {
    // Each spec mounts fresh; nothing to seed.
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the title and form when open=true, nothing when open=false', () => {
    const { wrapper: openWrapper } = mountDialog({ open: true })
    expect(openWrapper.find('[data-testid="transition-eye-dialog"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-dialog-title"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-target-study"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-reason"]').exists()).toBe(true)
    expect(openWrapper.find('[data-testid="transition-eye-submit"]').exists()).toBe(true)
    openWrapper.unmount()

    const { wrapper: closedWrapper } = mountDialog({ open: false })
    expect(closedWrapper.find('[data-testid="transition-eye-dialog"]').exists()).toBe(false)
    expect(closedWrapper.find('[data-testid="transition-eye-submit"]').exists()).toBe(false)
    closedWrapper.unmount()
  })

  it('disables submit until target study is picked + reason filled (already-enrolled branch)', async () => {
    // alreadyEnrolled=true → no targetLabel required → only study + reason gate submit.
    const { wrapper } = mountDialog({}, async () => ALREADY_ENROLLED)
    const submit = wrapper.find('[data-testid="transition-eye-submit"]')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    await pickStudy(wrapper, 'S_GA1')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    const reasonField = wrapper.find<HTMLTextAreaElement>('#transition-eye-reason')
    await reasonField.setValue('   ')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    await reasonField.setValue('OCT zeigt GA-Schwelle überschritten')
    expect((submit.element as HTMLButtonElement).disabled).toBe(false)

    wrapper.unmount()
  })

  it('reveals the new-enrollment panel when target study is not yet enrolled', async () => {
    const { wrapper } = mountDialog({}, async () => NOT_ENROLLED)
    // Panel is hidden until a target study is picked.
    expect(wrapper.find('[data-testid="transition-eye-new-enrollment-panel"]').exists()).toBe(false)

    await pickStudy(wrapper, 'S_GA1')
    expect(wrapper.find('[data-testid="transition-eye-new-enrollment-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="transition-eye-target-label"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('keeps submit disabled when targetLabel is empty in the new-enrollment panel', async () => {
    const { wrapper } = mountDialog({}, async () => NOT_ENROLLED)
    // Pick S_OTHER — its uniqueIdentifier is null, so no prefix prefill
    // fires and we can assert the empty-targetLabel branch directly.
    await pickStudy(wrapper, 'S_OTHER')

    await wrapper.find<HTMLTextAreaElement>('#transition-eye-reason').setValue('GA confirmed')
    const submit = wrapper.find('[data-testid="transition-eye-submit"]')
    // targetLabel empty → submit disabled even with study + reason.
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    wrapper.unmount()
  })

  it('keeps submit disabled and surfaces the "taken" error when targetLabel collides', async () => {
    // Preflight returns labelAvailable=false whenever a candidate label is sent.
    const impl = vi.fn(async (
      _label: string,
      _eye: 'OD' | 'OS',
      _studyOid: string,
      candidateLabel: string | null,
    ): Promise<TransitionPreflight> => {
      if (candidateLabel !== null && candidateLabel !== '') return LABEL_TAKEN
      return NOT_ENROLLED
    })
    const { wrapper } = mountDialog({}, impl)
    await pickStudy(wrapper, 'S_GA1')

    await wrapper.find<HTMLTextAreaElement>('#transition-eye-reason').setValue('GA confirmed')
    await wrapper.find<HTMLInputElement>('#transition-eye-target-label').setValue('M-X99')

    // The debounce window is 300ms — wait it out with real timers (fake
    // timers interfere with the promise microtask queue the preflight
    // uses inside its watcher). 400ms is comfortably above the 300ms
    // window and keeps the test fast.
    await new Promise<void>((resolve) => setTimeout(resolve, 400))
    await flushPromises()

    // Inline "taken" error surfaces and submit stays disabled.
    expect(wrapper.text()).toContain('already taken')
    const submit = wrapper.find('[data-testid="transition-eye-submit"]')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    wrapper.unmount()
  })

  it('hides the targetLabel input and shows the info line when already enrolled', async () => {
    const { wrapper } = mountDialog({}, async () => ALREADY_ENROLLED)
    await pickStudy(wrapper, 'S_GA1')

    expect(wrapper.find('[data-testid="transition-eye-new-enrollment-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="transition-eye-target-label"]').exists()).toBe(false)
    const info = wrapper.find('[data-testid="transition-eye-already-enrolled-info"]')
    expect(info.exists()).toBe(true)
    expect(info.text()).toContain('M-001')

    wrapper.unmount()
  })

  it('emits submit with targetLabel when the new-enrollment panel is active', async () => {
    // Preflight: not enrolled when no candidate, available when candidate is non-empty.
    const impl = vi.fn(async (
      _label: string,
      _eye: 'OD' | 'OS',
      _studyOid: string,
      _candidateLabel: string | null,
    ): Promise<TransitionPreflight> => NOT_ENROLLED)
    const { wrapper } = mountDialog({}, impl)
    await pickStudy(wrapper, 'S_GA2')
    await wrapper.find<HTMLInputElement>('#transition-eye-target-label').setValue('  M-101  ')
    await wrapper.find<HTMLTextAreaElement>('#transition-eye-reason')
      .setValue('  GA threshold crossed at visit 7  ')

    await new Promise<void>((resolve) => setTimeout(resolve, 400))
    await flushPromises()

    await wrapper.find('[data-testid="transition-eye-submit"]').trigger('click')

    const events = wrapper.emitted('submit')
    expect(events).toBeTruthy()
    expect(events).toHaveLength(1)
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

    wrapper.unmount()
  })

  it('emits submit WITHOUT targetLabel when subject is already enrolled', async () => {
    const { wrapper } = mountDialog({}, async () => ALREADY_ENROLLED)
    await pickStudy(wrapper, 'S_GA1')
    await wrapper.find<HTMLTextAreaElement>('#transition-eye-reason').setValue('Bilateral GA')

    await wrapper.find('[data-testid="transition-eye-submit"]').trigger('click')
    const events = wrapper.emitted('submit')
    expect(events).toBeTruthy()
    const payload = events![0][0] as {
      targetStudyOid: string
      targetLabel?: string
      reason: string
    }
    expect(payload.targetLabel).toBeUndefined()
    expect(payload.targetStudyOid).toBe('S_GA1')
    expect(payload.reason).toBe('Bilateral GA')

    wrapper.unmount()
  })

  it('prefills targetLabel with the target study uniqueIdentifier prefix on first reveal', async () => {
    const { wrapper } = mountDialog({}, async () => NOT_ENROLLED)
    await pickStudy(wrapper, 'S_GA1')

    const input = wrapper.find<HTMLInputElement>('#transition-eye-target-label')
    expect(input.element.value).toBe('GA1-')

    wrapper.unmount()
  })

  it('does NOT prefill when the target study has no uniqueIdentifier', async () => {
    const { wrapper } = mountDialog({}, async () => NOT_ENROLLED)
    await pickStudy(wrapper, 'S_OTHER')

    const input = wrapper.find<HTMLInputElement>('#transition-eye-target-label')
    expect(input.element.value).toBe('')

    wrapper.unmount()
  })

  it('preserves an operator edit when the target study is switched', async () => {
    const { wrapper } = mountDialog({}, async () => NOT_ENROLLED)
    await pickStudy(wrapper, 'S_GA1')

    const input = wrapper.find<HTMLInputElement>('#transition-eye-target-label')
    expect(input.element.value).toBe('GA1-')
    // Operator types over the prefill — triggers @input so userTouched=true.
    await input.setValue('CUSTOM-007')
    expect(input.element.value).toBe('CUSTOM-007')

    // Switch target study to GA2 — prefill must NOT clobber the operator's edit.
    await pickStudy(wrapper, 'S_GA2')
    expect(input.element.value).toBe('CUSTOM-007')

    wrapper.unmount()
  })

  it('emits cancel on the explicit cancel button and on Escape', async () => {
    const { wrapper } = mountDialog()
    await wrapper.find('[data-testid="transition-eye-cancel"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('submit')).toBeFalsy()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
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
    const { wrapper } = mountDialog({
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

/**
 * Phase E.6 discrepancy-full — NewNoteDialog spec.
 *
 * Pins six load-bearing behaviours:
 *
 *  1. Submit calls {@code notes.createNote} with the exact payload —
 *     subjectId + itemOid + eventCrfOid + description + type +
 *     assignedTo. Dropping eventCrfOid would post the note to the
 *     wrong repeating-event instance, which is the kind of silent
 *     data-quality bug a clinical-trial system can't ship.
 *  2. The type select hides {@code reason-for-change} for
 *     Investigator and shows it for Data Manager — defence-in-depth
 *     mirror of the backend's role-gate so non-DM users don't see a
 *     button the server would 403.
 *  3. Empty description disables Submit.
 *  4. {@code prefill} populates both type + description on open so the
 *     CrfItemWidget failed-validation shortcut can warm-start the form.
 *  5. Successful submit emits {@code created} with the returned note
 *     and emits {@code close}.
 *  6. When the store returns null + sets {@code error.value}, the
 *     dialog stays open and the error renders inline.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

import NewNoteDialog from '@/components/NewNoteDialog.vue'
import { useNotesStore } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import type { DiscrepancyNote } from '@/types/note'
import type { UserRole } from '@/types/auth'

// UserAutocomplete lives in agent A3's worktree. Stub it so this
// suite compiles in isolation; the real component is wired up at
// harmonization time.
vi.mock('@/components/UserAutocomplete.vue', () => ({
  default: {
    name: 'UserAutocomplete',
    props: ['id', 'modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input :id="id" data-testid="user-autocomplete" :value="modelValue" @input="$emit(\'update:modelValue\', ($event.target as HTMLInputElement).value)" />',
  },
}))

const messages = {
  en: {
    common: { cancel: 'Cancel' },
    crfEntry: {
      noteDialog: {
        heading: 'Add note on {itemLabel}',
        description: 'Description',
        descriptionPlaceholder: 'Describe the issue…',
        errorRequired: 'Description is required',
        errorTooLong: 'Description is too long (max 4000)',
        type: 'Type',
        typeOption: {
          query: 'Query',
          failedValidation: 'Failed validation',
          annotation: 'Annotation',
          reasonForChange: 'Reason for change',
        },
        assignTo: 'Assign to',
        submit: 'Create query',
      },
    },
  },
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages,
})

function seedAuth(role: UserRole): void {
  const auth = useAuthStore()
  auth.user = {
    username: 'monitor_demo',
    displayName: 'Monitor Demo',
    email: null,
    role,
    siteLabel: null,
    profileComplete: true,
    activeStudy: {
      id: 1,
      oid: 'S_DEFAULTS1',
      name: 'Default Study',
      site: null,
    },
  } as unknown as ReturnType<typeof useAuthStore>['user']
}

function makeNote(overrides: Partial<DiscrepancyNote> = {}): DiscrepancyNote {
  return {
    id: 'dn-1',
    subjectId: 'SUB-001',
    itemOid: 'I_HEIGHT_CM',
    description: 'Value looks off',
    type: 'query',
    status: 'new',
    assignedTo: null,
    thread: [],
    ...overrides,
  } as unknown as DiscrepancyNote
}

function mountDialog(props: Partial<Record<string, unknown>> = {}) {
  return mount(NewNoteDialog, {
    props: {
      open: true,
      subjectId: 'SUB-001',
      itemOid: 'I_HEIGHT_CM',
      eventCrfOid: 'EC-42',
      itemLabel: 'Height (cm)',
      ...props,
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

function getButton(label: string): HTMLButtonElement {
  const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
  const match = buttons.find(
    (b) => b.textContent?.trim() === label && b.getAttribute('aria-label') !== 'Close',
  )
  if (!match) throw new Error(`button "${label}" not found`)
  return match
}

describe('NewNoteDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedAuth('Monitor')
  })

  it('submits the exact payload the backend pins to a repeating-event instance', async () => {
    const notes = useNotesStore()
    const created = makeNote({ id: 'dn-99', type: 'query' })
    const createNote = vi.fn().mockResolvedValue(created)
    // Replace the store action with our spy.
    ;(notes as unknown as { createNote: typeof createNote }).createNote = createNote

    const wrapper = mountDialog()
    await flushPromises()

    const textarea = document.body.querySelector('#new-note-description') as HTMLTextAreaElement
    textarea.value = 'Value looks off'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))

    const assignedTo = document.body.querySelector('[data-testid="user-autocomplete"]') as HTMLInputElement
    assignedTo.value = 'dm_demo'
    assignedTo.dispatchEvent(new Event('input', { bubbles: true }))

    await nextTick()
    await flushPromises()

    getButton('Create query').click()
    await flushPromises()

    expect(createNote).toHaveBeenCalledTimes(1)
    expect(createNote).toHaveBeenCalledWith({
      subjectId: 'SUB-001',
      itemOid: 'I_HEIGHT_CM',
      eventCrfOid: 'EC-42',
      description: 'Value looks off',
      type: 'query',
      assignedTo: 'dm_demo',
    })

    wrapper.unmount()
  })

  it("hides 'reason-for-change' for Investigator and shows it for Data Manager", async () => {
    seedAuth('Investigator')
    const wrapperInv = mountDialog()
    await flushPromises()
    const invOptions = Array.from(
      document.body.querySelectorAll('#new-note-type option'),
    ).map((o) => (o as HTMLOptionElement).value)
    expect(invOptions).toContain('query')
    expect(invOptions).toContain('failed-validation')
    expect(invOptions).toContain('annotation')
    expect(invOptions).not.toContain('reason-for-change')
    wrapperInv.unmount()

    setActivePinia(createPinia())
    seedAuth('Data Manager')
    const wrapperDm = mountDialog()
    await flushPromises()
    const dmOptions = Array.from(
      document.body.querySelectorAll('#new-note-type option'),
    ).map((o) => (o as HTMLOptionElement).value)
    expect(dmOptions).toContain('reason-for-change')
    wrapperDm.unmount()
  })

  it('disables Submit while the description is empty', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    const submit = getButton('Create query')
    expect(submit.disabled).toBe(true)

    const textarea = document.body.querySelector('#new-note-description') as HTMLTextAreaElement
    textarea.value = 'Now there is text'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    expect(submit.disabled).toBe(false)

    wrapper.unmount()
  })

  it('hydrates type + description from prefill on open', async () => {
    const wrapper = mountDialog({
      prefill: { type: 'failed-validation', description: 'Auto: range 10-20' },
    })
    await flushPromises()

    const textarea = document.body.querySelector('#new-note-description') as HTMLTextAreaElement
    expect(textarea.value).toBe('Auto: range 10-20')

    const select = document.body.querySelector('#new-note-type') as HTMLSelectElement
    expect(select.value).toBe('failed-validation')

    wrapper.unmount()
  })

  it('emits created + close on successful submit', async () => {
    const notes = useNotesStore()
    const returned = makeNote({ id: 'dn-7', type: 'query' })
    ;(notes as unknown as { createNote: () => Promise<DiscrepancyNote> }).createNote = vi
      .fn()
      .mockResolvedValue(returned)

    const wrapper = mountDialog()
    await flushPromises()

    const textarea = document.body.querySelector('#new-note-description') as HTMLTextAreaElement
    textarea.value = 'something'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    getButton('Create query').click()
    await flushPromises()

    const createdEvents = wrapper.emitted('created')
    expect(createdEvents).toBeTruthy()
    expect(createdEvents?.[0][0]).toEqual(returned)
    expect(wrapper.emitted('close')).toBeTruthy()

    wrapper.unmount()
  })

  it('keeps the dialog open + renders store.error when the backend fails', async () => {
    const notes = useNotesStore()
    ;(notes as unknown as { createNote: () => Promise<DiscrepancyNote | null> }).createNote = vi
      .fn()
      .mockImplementation(async () => {
        notes.error = 'Backend not reachable'
        return null
      })

    const wrapper = mountDialog()
    await flushPromises()

    const textarea = document.body.querySelector('#new-note-description') as HTMLTextAreaElement
    textarea.value = 'something'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    getButton('Create query').click()
    await flushPromises()

    expect(wrapper.emitted('created')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
    // The dialog is still mounted — the error renders inside it.
    expect(document.body.textContent).toContain('Backend not reachable')

    wrapper.unmount()
  })
})

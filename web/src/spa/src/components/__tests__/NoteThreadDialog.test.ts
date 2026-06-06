/**
 * Phase E.6 DN — NoteThreadDialog spec.
 *
 * Pins the load-bearing wiring that makes the indicator → dialog →
 * thread-action loop work end-to-end:
 *
 *  1. On mount with a single parent id and an empty cache, the dialog
 *     calls {@code notes.loadThread} exactly once (re-mount won't
 *     refetch — the parent view caches across opens).
 *  2. ThreadTimeline receives the cached entries verbatim.
 *  3. The role × status matrix drives which action buttons render
 *     (defence-in-depth — same matrix the backend enforces).
 *  4. Submitting an action POSTs via {@code notes.appendThread} with
 *     the right {@code newStatus} and emits {@code updated} so the
 *     parent's summary map can refresh.
 *  5. Multi-parent rows render a selector; picking a different row
 *     triggers a thread load for the newly selected parent.
 *  6. A null return from {@code appendThread} keeps the dialog open
 *     with the store error visible — the user gets a chance to retry.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { defineComponent, nextTick } from 'vue'

import NoteThreadDialog from '@/components/NoteThreadDialog.vue'
import { useNotesStore } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'
import type { DiscrepancyNote, NoteStatus, ThreadEntry } from '@/types/note'
import type { UserRole } from '@/types/auth'

vi.mock('@/components/UserAutocomplete.vue', () => ({
  default: defineComponent({
    name: 'UserAutocomplete',
    props: { modelValue: { type: String, default: '' } },
    emits: ['update:modelValue'],
    template:
      '<input data-testid="user-autocomplete-stub" :value="modelValue" @input="$emit(\'update:modelValue\', ($event.target as HTMLInputElement).value)" />',
  }),
}))

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

const PARENT_1: DiscrepancyNote = {
  id: 'n1',
  type: 'query',
  status: 'new',
  subjectId: 'M-001',
  itemOid: 'I_AGE',
  description: 'Age looks low',
  assignedTo: null,
  daysOpen: 1,
  lastActivityAt: '2026-06-01T08:00:00Z',
  thread: [],
}

const PARENT_2: DiscrepancyNote = {
  id: 'n2',
  type: 'failed-validation',
  status: 'updated',
  subjectId: 'M-001',
  itemOid: 'I_AGE',
  description: 'Range check failed',
  assignedTo: null,
  daysOpen: 2,
  lastActivityAt: '2026-06-02T08:00:00Z',
  thread: [],
}

const THREAD_1: ThreadEntry[] = [
  {
    id: 'n1',
    status: 'new',
    description: 'Age looks low',
    author: 'monitor_demo',
    createdAt: '2026-06-01T08:00:00Z',
  },
]

function seedAuthUser(role: UserRole) {
  const auth = useAuthStore()
  auth.user = {
    username: role.toLowerCase(),
    displayName: role,
    email: null,
    role,
    siteLabel: null,
    profileComplete: true,
    activeStudy: null,
  } as unknown as ReturnType<typeof useAuthStore>['user']
}

function mountDialog(props: Partial<{
  parentNoteIds: string[]
  subjectId: string
  itemOid: string
  itemLabel: string
}> = {}) {
  return mount(NoteThreadDialog, {
    props: {
      parentNoteIds: props.parentNoteIds ?? ['n1'],
      subjectId: props.subjectId ?? 'M-001',
      itemOid: props.itemOid ?? 'I_AGE',
      itemLabel: props.itemLabel ?? 'Age',
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('NoteThreadDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
  })

  it('loads the thread for the auto-selected single parent exactly once', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT_1]
    const loadSpy = vi.spyOn(notes, 'loadThread').mockResolvedValue(null)
    seedAuthUser('Investigator')

    const wrapper = mountDialog({ parentNoteIds: ['n1'] })
    await flushPromises()

    expect(loadSpy).toHaveBeenCalledTimes(1)
    expect(loadSpy).toHaveBeenCalledWith('n1')

    wrapper.unmount()
  })

  it('renders ThreadTimeline with the cached entries', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT_1]
    notes.threadCache = { n1: THREAD_1 }
    // Spy returns immediately — even if the dialog still calls loadThread
    // it should bail out on the cache check inside the component.
    const loadSpy = vi.spyOn(notes, 'loadThread').mockResolvedValue(null)
    seedAuthUser('Investigator')

    const wrapper = mountDialog({ parentNoteIds: ['n1'] })
    await flushPromises()

    // The store cache is non-empty for n1 → the dialog does not refetch.
    expect(loadSpy).not.toHaveBeenCalled()
    // Entry's description appears in the rendered timeline.
    expect(document.body.textContent).toContain('Age looks low')

    wrapper.unmount()
  })

  it('shows only Respond for Investigator + new', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT_1]
    notes.threadCache = { n1: THREAD_1 }
    seedAuthUser('Investigator')

    const wrapper = mountDialog({ parentNoteIds: ['n1'] })
    await flushPromises()

    expect(document.body.querySelector('[data-testid="note-thread-action-respond"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="note-thread-action-propose"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="note-thread-action-close"]')).toBeNull()

    wrapper.unmount()
  })

  it('Respond → opens composer → submits with newStatus=updated', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT_1]
    notes.threadCache = { n1: THREAD_1 }
    const appendSpy = vi
      .spyOn(notes, 'appendThread')
      .mockResolvedValue({ ...PARENT_1, status: 'updated' as NoteStatus })
    seedAuthUser('Investigator')

    const wrapper = mountDialog({ parentNoteIds: ['n1'] })
    await flushPromises()

    const respondBtn = document.body.querySelector(
      '[data-testid="note-thread-action-respond"]',
    ) as HTMLButtonElement
    respondBtn.click()
    await nextTick()

    const composer = document.body.querySelector(
      '[data-testid="note-thread-composer"]',
    )
    expect(composer).not.toBeNull()

    const textarea = document.body.querySelector(
      '[data-testid="note-thread-composer-text"]',
    ) as HTMLTextAreaElement
    textarea.value = '  Re-checked source  '
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    const submitBtn = document.body.querySelector(
      '[data-testid="note-thread-composer-submit"]',
    ) as HTMLButtonElement
    submitBtn.click()
    await flushPromises()

    expect(appendSpy).toHaveBeenCalledTimes(1)
    const [parentId, payload] = appendSpy.mock.calls[0]
    expect(parentId).toBe('n1')
    expect(payload.newStatus).toBe('updated')
    expect(payload.description).toBe('Re-checked source')

    expect(wrapper.emitted('updated')).toBeTruthy()
    expect(wrapper.emitted('updated')?.[0]).toEqual(['n1'])

    wrapper.unmount()
  })

  it('shows only Close for Monitor + resolution-proposed → submits newStatus=closed', async () => {
    const notes = useNotesStore()
    const proposed: DiscrepancyNote = { ...PARENT_1, status: 'resolution-proposed' }
    notes.rows = [proposed]
    notes.threadCache = { n1: THREAD_1 }
    const appendSpy = vi
      .spyOn(notes, 'appendThread')
      .mockResolvedValue({ ...proposed, status: 'closed' as NoteStatus })
    seedAuthUser('Monitor')

    const wrapper = mountDialog({ parentNoteIds: ['n1'] })
    await flushPromises()

    expect(document.body.querySelector('[data-testid="note-thread-action-respond"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="note-thread-action-propose"]')).toBeNull()
    const closeBtn = document.body.querySelector(
      '[data-testid="note-thread-action-close"]',
    ) as HTMLButtonElement
    expect(closeBtn).not.toBeNull()
    closeBtn.click()
    await nextTick()

    // Close may submit without a description per the matrix.
    const submitBtn = document.body.querySelector(
      '[data-testid="note-thread-composer-submit"]',
    ) as HTMLButtonElement
    submitBtn.click()
    await flushPromises()

    expect(appendSpy).toHaveBeenCalledTimes(1)
    const [parentId, payload] = appendSpy.mock.calls[0]
    expect(parentId).toBe('n1')
    expect(payload.newStatus).toBe('closed')

    wrapper.unmount()
  })

  it('shows Respond AND Propose for Investigator + updated', async () => {
    const notes = useNotesStore()
    const updated: DiscrepancyNote = { ...PARENT_1, status: 'updated' }
    notes.rows = [updated]
    notes.threadCache = { n1: THREAD_1 }
    seedAuthUser('Investigator')

    const wrapper = mountDialog({ parentNoteIds: ['n1'] })
    await flushPromises()

    expect(document.body.querySelector('[data-testid="note-thread-action-respond"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="note-thread-action-propose"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="note-thread-action-close"]')).toBeNull()

    wrapper.unmount()
  })

  it('renders the selector for multi-parent input + picking a new one loads its thread', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT_1, PARENT_2]
    // Pre-cache n1 so the auto-load doesn't fire.
    notes.threadCache = { n1: THREAD_1 }
    const loadSpy = vi.spyOn(notes, 'loadThread').mockResolvedValue(null)
    seedAuthUser('Investigator')

    const wrapper = mountDialog({ parentNoteIds: ['n1', 'n2'] })
    await flushPromises()

    const selector = document.body.querySelector('[data-testid="note-thread-selector"]')
    expect(selector).not.toBeNull()

    // n1 was cached; loadThread should NOT have been called on mount.
    expect(loadSpy).not.toHaveBeenCalled()

    const pickN2 = document.body.querySelector(
      '[data-testid="note-thread-pick-n2"]',
    ) as HTMLButtonElement
    pickN2.click()
    await flushPromises()

    expect(loadSpy).toHaveBeenCalledTimes(1)
    expect(loadSpy).toHaveBeenCalledWith('n2')

    wrapper.unmount()
  })

  it('keeps the dialog open and renders the store error when appendThread returns null', async () => {
    const notes = useNotesStore()
    notes.rows = [PARENT_1]
    notes.threadCache = { n1: THREAD_1 }
    vi.spyOn(notes, 'appendThread').mockImplementation(async () => {
      notes.error = 'Backend nicht erreichbar — Zustandsänderung konnte nicht gespeichert werden.'
      return null
    })
    seedAuthUser('Investigator')

    const wrapper = mountDialog({ parentNoteIds: ['n1'] })
    await flushPromises()

    ;(document.body.querySelector(
      '[data-testid="note-thread-action-respond"]',
    ) as HTMLButtonElement).click()
    await nextTick()

    const textarea = document.body.querySelector(
      '[data-testid="note-thread-composer-text"]',
    ) as HTMLTextAreaElement
    textarea.value = 'Re-checked source'
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    ;(document.body.querySelector(
      '[data-testid="note-thread-composer-submit"]',
    ) as HTMLButtonElement).click()
    await flushPromises()

    // No 'updated' emission on failure — the parent must not refresh
    // its summary against a parent whose status didn't actually move.
    expect(wrapper.emitted('updated')).toBeFalsy()
    // Composer stays open and shows the store error inline.
    expect(document.body.querySelector('[data-testid="note-thread-composer"]')).not.toBeNull()
    expect(document.body.textContent).toContain('Backend nicht erreichbar')

    wrapper.unmount()
  })
})

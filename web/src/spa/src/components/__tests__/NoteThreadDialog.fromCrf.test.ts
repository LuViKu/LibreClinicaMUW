/**
 * The thread dialog opened from an item of the CRF, before the notes list
 * has been loaded anywhere: a Monitor who opens the read-only CRF straight
 * from the SDV table. The dialog learns the parent note from loading its
 * thread and offers the Monitor's actions.
 *
 * Before, it looked the parent up in the notes list only, found nothing, and
 * showed the thread without a status or a single action.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { defineComponent } from 'vue'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  ApiError: class ApiError extends Error {},
  ApiNetworkError: class ApiNetworkError extends Error {},
}))

vi.mock('@/components/UserAutocomplete.vue', () => ({
  default: defineComponent({ name: 'UserAutocomplete', template: '<input />' }),
}))

// eslint-disable-next-line import/first
import { apiGet } from '@/api/client'
// eslint-disable-next-line import/first
import NoteThreadDialog from '@/components/NoteThreadDialog.vue'
// eslint-disable-next-line import/first
import { useNotesStore } from '@/stores/notes'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'
// eslint-disable-next-line import/first
import type { DiscrepancyNote } from '@/types/note'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: enMessages },
})

const HYDRATED: DiscrepancyNote = {
  id: '6',
  type: 'query',
  status: 'new',
  subjectId: 'M-004',
  itemOid: 'I_HEIGHT_CM',
  description: 'Weight field empty',
  assignedTo: 'root',
  daysOpen: 2160,
  lastActivityAt: '2020-11-03T00:00:00Z',
  thread: [
    { id: '6', status: 'new', description: 'Weight field empty', author: 'root', createdAt: '2020-11-03T00:00:00Z' },
  ],
  itemLabel: 'Height (cm)',
  itemValue: '184',
  eventCrfOid: '9',
  eventName: 'V1 Inclusion',
}

describe('NoteThreadDialog opened from a CRF item', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
    vi.mocked(apiGet).mockReset()
  })

  it('takes the parent from its thread and offers the Monitor Respond and Close', async () => {
    useAuthStore().user = { username: 'manual_monitor', role: 'Monitor', activeStudy: null } as unknown as
      ReturnType<typeof useAuthStore>['user']
    const notes = useNotesStore()
    expect(notes.rows).toEqual([])
    vi.mocked(apiGet).mockResolvedValueOnce(HYDRATED)

    const wrapper = mount(NoteThreadDialog, {
      props: { parentNoteIds: ['6'], subjectId: 'M-004', itemOid: 'I_HEIGHT_CM', itemLabel: 'Height (cm)' },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await flushPromises()

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/discrepancies/6/thread')
    expect(document.body.textContent).toContain('Status: New')
    expect(document.body.querySelector('[data-testid="note-thread-action-respond"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="note-thread-action-close"]')).not.toBeNull()

    wrapper.unmount()
  })
})

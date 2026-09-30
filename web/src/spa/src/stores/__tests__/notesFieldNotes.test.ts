import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn() }
})

// eslint-disable-next-line import/first
import { apiGet, apiPost } from '@/api/client'
// eslint-disable-next-line import/first
import { useNotesStore } from '../notes'

/**
 * Queries on a field of the subject, a visit or a CRF header travel with
 * the field's entity type and column, as legacy notes them; an item query
 * keeps its old payload. One subject's notes load without touching the
 * notes list.
 */
describe('notes store — field notes', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
  })

  it('sends the field of a visit', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({ id: '9', subjectId: 'M-001' })
    await useNotesStore().add({
      subjectId: 'M-001',
      itemOid: '',
      description: 'Visit date differs from the source',
      field: { entityType: 'studyEvent', column: 'start_date', eventId: '1' },
    })

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/discrepancies', {
      subjectId: 'M-001',
      itemOid: '',
      eventCrfOid: null,
      description: 'Visit date differs from the source',
      assignedTo: null,
      type: 'query',
      entityType: 'studyEvent',
      column: 'start_date',
      eventId: '1',
    })
  })

  it('keeps the item payload as it was', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({ id: '10', subjectId: 'M-001' })
    await useNotesStore().add({ subjectId: 'M-001', itemOid: 'I_HEIGHT_CM', eventCrfOid: '1', description: 'Low' })

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/discrepancies', {
      subjectId: 'M-001',
      itemOid: 'I_HEIGHT_CM',
      eventCrfOid: '1',
      description: 'Low',
      assignedTo: null,
      type: 'query',
    })
  })

  it("loads one subject's notes without replacing the list", async () => {
    const store = useNotesStore()
    store.rows = [{ id: '1' } as never]
    vi.mocked(apiGet).mockResolvedValueOnce([{ id: '5', subjectId: 'M-001' }])

    const found = await store.notesForSubject('M-001')

    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/discrepancies?subjectId=M-001')
    expect(found.map((n) => n.id)).toEqual(['5'])
    expect(store.rows.map((n) => n.id)).toEqual(['1'])
  })
})

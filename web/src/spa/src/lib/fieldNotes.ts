import type { DiscrepancyNote, NoteField } from '@/types/note'
import type { ItemNoteSummary } from '@/types/crf'

/**
 * The notes on one field of a subject or a visit, from that subject's notes.
 *
 * @param entityId the visit (studyEvent) or CRF (eventCrf) the field
 *        belongs to; a subject's fields need none, the notes being the
 *        subject's already
 */
export function notesOnField(notes: DiscrepancyNote[], field: NoteField, entityId?: string): DiscrepancyNote[] {
  return notes.filter((n) =>
    n.entityType === field.entityType
    && n.column === field.column
    && (entityId === undefined || n.entityId === entityId))
}

/**
 * The indicator summary for a field's notes, as ItemNoteIndicator shows it
 * for an item: null when there are none, "open" while one is New, Updated
 * or Resolution Proposed.
 */
export function fieldNoteSummary(notes: DiscrepancyNote[]): ItemNoteSummary | null {
  if (notes.length === 0) return null
  const open = notes.filter((n) => n.status === 'new' || n.status === 'updated' || n.status === 'resolution-proposed')
  return {
    totalCount: notes.length,
    openCount: open.length,
    status: open.length > 0 ? 'open' : 'resolved',
    lastActivityAt: null,
    noteIds: notes.map((n) => n.id),
  }
}

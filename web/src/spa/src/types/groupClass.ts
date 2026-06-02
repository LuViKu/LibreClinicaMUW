/**
 * Phase E A8.6 — subject group class types.
 *
 * The legacy {@code study_group_class} table has no OID column; the
 * SPA references rows by numeric id (the legacy primary key).
 */

export type GroupClassType = 'Arm' | 'Family' | 'Demographic' | 'Other'
export type SubjectAssignment = 'REQUIRED' | 'OPTIONAL'

export interface Group {
  id: number
  name: string
  description: string
  status: string
}

export interface GroupClass {
  id: number
  name: string
  groupClassType: GroupClassType
  subjectAssignment: SubjectAssignment
  status: string
  groups: Group[]
}

export interface CreateGroupClassInput {
  name: string
  groupClassType: GroupClassType
  subjectAssignment: SubjectAssignment
  groups?: { name: string; description?: string }[]
}

/**
 * The list semantics: entries with `id == null` (or id missing) are
 * created; entries omitted from the list are soft-deleted; entries
 * with a present id are updated.
 */
export interface UpdateGroupClassInput {
  name?: string
  groupClassType?: GroupClassType
  subjectAssignment?: SubjectAssignment
  groups?: { id?: number | null; name: string; description?: string }[]
}

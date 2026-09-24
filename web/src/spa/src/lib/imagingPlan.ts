/**
 * DR-034 — the visit imaging plan as the editor holds it.
 *
 * The backend stores one row per (visit definition, modality) the visit
 * expects. The editor shows one row per catalogue modality instead, included
 * or not, so an administrator sees what the study can capture and ticks what
 * this visit needs. These helpers go between the two shapes and hold the
 * rules that do not belong in a template: which modalities can carry tasks,
 * and which tasks a study module insists on.
 */
import type { ImagingModality } from '@/types/imagingModality'
import type {
  ImagingLaterality,
  ImagingPlanEntry,
  ImagingPlanEntryWrite,
  ImagingRequirement,
} from '@/types/eventDefinition'

/** Tasks the runner registry recognises, in the order the chips are shown. */
export const RETINAL_TASK_OPTIONS: readonly string[] = ['fluid', 'ga', 'onl', 'pr', 'layers'] as const

export interface PlanRow {
  modalityId: number
  code: string
  labelDe: string
  labelEn: string
  device: string | null
  /** Only an OCT-volume modality can carry inference tasks. */
  acceptsE2e: boolean
  included: boolean
  requirement: ImagingRequirement
  laterality: ImagingLaterality | ''
  tasks: string[]
}

export function acceptsKind(kindsAccepted: string | null | undefined, kind: string): boolean {
  if (!kindsAccepted) return false
  return kindsAccepted
    .split(',')
    .map((k) => k.trim().toLowerCase())
    .includes(kind.toLowerCase())
}

/**
 * One editor row per active catalogue modality, carrying the plan's entry
 * for it when there is one. Retired modalities are shown only while the plan
 * still names them, so an old entry can be seen and removed.
 */
export function buildPlanRows(
  modalities: ImagingModality[],
  entries: ImagingPlanEntry[],
): PlanRow[] {
  const byId = new Map(entries.map((e) => [e.modalityId, e]))
  const rows: PlanRow[] = []
  for (const m of modalities) {
    const e = byId.get(m.id)
    if (m.statusId !== 1 && !e) continue
    rows.push({
      modalityId: m.id,
      code: m.code,
      labelDe: m.labelDe,
      labelEn: m.labelEn,
      device: m.device,
      acceptsE2e: acceptsKind(m.kindsAccepted, 'e2e'),
      included: !!e,
      requirement: e?.requirement ?? 'optional',
      laterality: e?.laterality ?? '',
      tasks: e ? [...e.tasks] : [],
    })
  }
  // A plan entry whose modality is not in the list (a race with a catalogue
  // edit) still deserves a row, or saving would silently drop it.
  for (const e of entries) {
    if (rows.some((r) => r.modalityId === e.modalityId)) continue
    rows.push({
      modalityId: e.modalityId,
      code: e.code,
      labelDe: e.labelDe,
      labelEn: e.labelEn,
      device: e.device,
      acceptsE2e: acceptsKind(e.kindsAccepted, 'e2e'),
      included: true,
      requirement: e.requirement,
      laterality: e.laterality ?? '',
      tasks: [...e.tasks],
    })
  }
  return rows
}

/**
 * The PUT body: included rows only, tasks only where they can run, and the
 * module-required tasks forced in on every OCT-volume row.
 */
export function toWriteEntries(rows: PlanRow[], requiredTasks: readonly string[] = []): ImagingPlanEntryWrite[] {
  return rows
    .filter((r) => r.included)
    .map((r) => ({
      modalityId: r.modalityId,
      requirement: r.requirement,
      laterality: r.laterality === '' ? null : r.laterality,
      tasks: r.acceptsE2e ? withRequired(r.tasks, requiredTasks) : [],
    }))
}

/** `tasks` plus every required one, in option order, without duplicates. */
export function withRequired(tasks: readonly string[], required: readonly string[]): string[] {
  const set = new Set([...tasks, ...required])
  return RETINAL_TASK_OPTIONS.filter((t) => set.has(t)).concat(
    [...set].filter((t) => !RETINAL_TASK_OPTIONS.includes(t)),
  )
}

/** Toggle one task on a row; a required task cannot be switched off. */
export function toggleTask(row: PlanRow, task: string, required: readonly string[] = []): void {
  if (required.includes(task)) {
    if (!row.tasks.includes(task)) row.tasks = withRequired(row.tasks, [task])
    return
  }
  row.tasks = row.tasks.includes(task)
    ? row.tasks.filter((t) => t !== task)
    : withRequired([...row.tasks, task], [])
}

/** The union of what every active module insists on, in option order. */
export function requiredTasksOf(modules: ReadonlyArray<{ requiredRetinalTasks?: readonly string[] }>): string[] {
  const all = modules.flatMap((m) => m.requiredRetinalTasks ?? [])
  return withRequired([], all)
}

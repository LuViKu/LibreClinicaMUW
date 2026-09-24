import { describe, expect, it } from 'vitest'

import {
  buildPlanRows,
  requiredTasksOf,
  toWriteEntries,
  toggleTask,
  withRequired,
  type PlanRow,
} from '../imagingPlan'
import type { ImagingModality } from '@/types/imagingModality'
import type { ImagingPlanEntry } from '@/types/eventDefinition'

/**
 * DR-034 — the editor's view of a visit imaging plan: one row per catalogue
 * modality, the plan's entry folded in, and the write shape the backend takes.
 */
function modality(overrides: Partial<ImagingModality>): ImagingModality {
  return {
    id: 1,
    code: 'OCT',
    labelDe: 'OCT',
    labelEn: 'OCT',
    device: 'spectralis',
    kindsAccepted: 'e2e',
    lateralityRequired: true,
    autoMatchAeTitle: null,
    ordinal: 1,
    statusId: 1,
    bindings: [],
    ...overrides,
  }
}

function entry(overrides: Partial<ImagingPlanEntry>): ImagingPlanEntry {
  return {
    modalityId: 1,
    code: 'OCT',
    labelDe: 'OCT',
    labelEn: 'OCT',
    device: 'spectralis',
    kindsAccepted: 'e2e',
    requirement: 'required',
    laterality: 'OU',
    tasks: ['fluid'],
    ...overrides,
  }
}

const OCT = modality({})
const CLARUS = modality({ id: 2, code: 'CLARUS', device: 'clarus', kindsAccepted: 'dicom,image', ordinal: 2 })
const RETIRED = modality({ id: 3, code: 'OLD', statusId: 5, ordinal: 3 })

describe('buildPlanRows', () => {
  it('lists every active modality, included only where the plan names it', () => {
    const rows = buildPlanRows([OCT, CLARUS, RETIRED], [entry({})])
    expect(rows.map((r) => r.code)).toEqual(['OCT', 'CLARUS'])
    expect(rows[0]).toMatchObject({ included: true, requirement: 'required', laterality: 'OU', tasks: ['fluid'], acceptsE2e: true })
    expect(rows[1]).toMatchObject({ included: false, requirement: 'optional', laterality: '', tasks: [], acceptsE2e: false })
  })

  it('keeps a retired modality visible while the plan still names it', () => {
    const rows = buildPlanRows([OCT, RETIRED], [entry({ modalityId: 3, code: 'OLD' })])
    expect(rows.map((r) => r.code)).toEqual(['OCT', 'OLD'])
    expect(rows[1].included).toBe(true)
  })

  it('appends an entry whose modality is missing from the catalogue list rather than dropping it', () => {
    const rows = buildPlanRows([OCT], [entry({ modalityId: 9, code: 'GHOST', kindsAccepted: '' })])
    expect(rows.map((r) => r.code)).toEqual(['OCT', 'GHOST'])
    expect(rows[1]).toMatchObject({ included: true, acceptsE2e: false })
  })
})

describe('toWriteEntries', () => {
  it('sends included rows only, strips tasks from non-OCT rows and forces required tasks onto OCT rows', () => {
    const rows = buildPlanRows([OCT, CLARUS], [
      entry({ tasks: ['layers'] }),
      entry({ modalityId: 2, code: 'CLARUS', kindsAccepted: 'dicom', laterality: null, requirement: 'optional', tasks: [] }),
    ])
    rows[1].tasks = ['fluid'] // nonsense the UI never allows; must not leak out
    expect(toWriteEntries(rows, ['fluid'])).toEqual([
      { modalityId: 1, requirement: 'required', laterality: 'OU', tasks: ['fluid', 'layers'] },
      { modalityId: 2, requirement: 'optional', laterality: null, tasks: [] },
    ])
  })

  it('omits a row that is not included', () => {
    const rows = buildPlanRows([OCT, CLARUS], [])
    rows[1].included = true
    expect(toWriteEntries(rows)).toEqual([
      { modalityId: 2, requirement: 'optional', laterality: null, tasks: [] },
    ])
  })
})

describe('withRequired / toggleTask', () => {
  it('orders tasks as the chips are shown and never duplicates', () => {
    expect(withRequired(['layers', 'fluid'], ['fluid'])).toEqual(['fluid', 'layers'])
    expect(withRequired([], ['pr', 'ga'])).toEqual(['ga', 'pr'])
  })

  it('a required task cannot be switched off, an ordinary one toggles', () => {
    const row: PlanRow = { ...buildPlanRows([OCT], [entry({ tasks: ['fluid'] })])[0] }
    toggleTask(row, 'fluid', ['fluid'])
    expect(row.tasks).toEqual(['fluid'])
    toggleTask(row, 'layers', ['fluid'])
    expect(row.tasks).toEqual(['fluid', 'layers'])
    toggleTask(row, 'layers', ['fluid'])
    expect(row.tasks).toEqual(['fluid'])
  })

  it('a required task that is missing from the row is put back on toggle', () => {
    const row: PlanRow = { ...buildPlanRows([OCT], [entry({ tasks: [] })])[0] }
    toggleTask(row, 'fluid', ['fluid'])
    expect(row.tasks).toEqual(['fluid'])
  })
})

describe('requiredTasksOf', () => {
  it('unions what every active module insists on', () => {
    expect(requiredTasksOf([{ requiredRetinalTasks: ['layers'] }, {}, { requiredRetinalTasks: ['fluid'] }]))
      .toEqual(['fluid', 'layers'])
    expect(requiredTasksOf([])).toEqual([])
  })
})

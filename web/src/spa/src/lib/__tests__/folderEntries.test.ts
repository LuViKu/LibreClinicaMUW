/**
 * DR-041 — folder picks and folder drops: files keep the path they were found at.
 */
import { describe, expect, it } from 'vitest'

import { filesFromDataTransfer, fromFileList, subjectFolderOf } from '@/lib/folderEntries'

function fileEntry(name: string, path: string) {
  return {
    isFile: true, isDirectory: false, name, fullPath: path,
    file: (ok: (f: File) => void) => ok(new File([name], name)),
  }
}

/** A directory whose reader hands out `children` in batches of `batch`, then an empty batch. */
function dirEntry(name: string, path: string, children: unknown[], batch = 2) {
  return {
    isFile: false, isDirectory: true, name, fullPath: path,
    createReader: () => {
      let at = 0
      return {
        readEntries: (ok: (e: unknown[]) => void) => {
          const next = children.slice(at, at + batch)
          at += batch
          ok(next)
        },
      }
    },
  }
}

function transfer(entries: unknown[]): DataTransfer {
  return {
    items: entries.map((e) => ({ webkitGetAsEntry: () => e })),
    files: [],
  } as unknown as DataTransfer
}

describe('subjectFolderOf', () => {
  it('takes the folder that directly contains the file', () => {
    expect(subjectFolderOf('iAMD/EIAMD005/EIAMD01D.E2E')).toBe('EIAMD005')
    expect(subjectFolderOf('EIAMD005/EIAMD01D.E2E')).toBe('EIAMD005')
  })
  it('has no folder for a file picked on its own', () => {
    expect(subjectFolderOf('EIAMD01D.E2E')).toBeNull()
    expect(subjectFolderOf('')).toBeNull()
    expect(subjectFolderOf(null)).toBeNull()
  })
})

describe('fromFileList', () => {
  it('keeps webkitRelativePath, and gives a plain pick no folder', () => {
    const inFolder = new File(['x'], 'a.E2E')
    Object.defineProperty(inFolder, 'webkitRelativePath', { value: 'P1/a.E2E' })
    const plain = new File(['x'], 'b.E2E')
    const out = fromFileList([inFolder, plain, new File(['x'], '.DS_Store')])
    expect(out.map((p) => p.relativePath)).toEqual(['P1/a.E2E', null])
  })
})

describe('filesFromDataTransfer', () => {
  it('walks folders recursively, reading every batch until readEntries answers empty', async () => {
    const patientA = dirEntry('EIAMD005', '/root/EIAMD005', [
      fileEntry('1.E2E', '/root/EIAMD005/1.E2E'),
      fileEntry('2.E2E', '/root/EIAMD005/2.E2E'),
      fileEntry('3.E2E', '/root/EIAMD005/3.E2E'), // the third file only comes with the second batch
      fileEntry('.DS_Store', '/root/EIAMD005/.DS_Store'),
    ])
    const patientB = dirEntry('EIAMD006', '/root/EIAMD006', [fileEntry('4.E2E', '/root/EIAMD006/4.E2E')])
    const root = dirEntry('root', '/root', [patientA, patientB], 1)

    const out = await filesFromDataTransfer(transfer([root]))

    expect(out.map((p) => p.relativePath)).toEqual([
      'root/EIAMD005/1.E2E', 'root/EIAMD005/2.E2E', 'root/EIAMD005/3.E2E', 'root/EIAMD006/4.E2E',
    ])
    expect(out.map((p) => subjectFolderOf(p.relativePath))).toEqual(['EIAMD005', 'EIAMD005', 'EIAMD005', 'EIAMD006'])
  })

  it('gives a file dropped on its own no folder', async () => {
    const out = await filesFromDataTransfer(transfer([fileEntry('x.E2E', '/x.E2E')]))
    expect(out).toHaveLength(1)
    expect(subjectFolderOf(out[0].relativePath)).toBeNull()
  })

  it('falls back to the plain file list where the entry API is missing', async () => {
    const f = new File(['x'], 'a.E2E')
    const dt = { items: [{}], files: [f] } as unknown as DataTransfer
    const out = await filesFromDataTransfer(dt)
    expect(out.map((p) => p.file)).toEqual([f])
  })
})

/**
 * Folders for the uploader: the folder picker and folder drag-and-drop.
 *
 * Both hand the store a list of {@link PickedFile}: the file and the path it
 * was found at, relative to what the person picked or dropped (for example
 * {@code iAMD/EIAMD005/EIAMD01D.E2E}). A file chosen on its own has no folder.
 * The subject of a Spectralis export is the folder that directly contains it
 * ({@link subjectFolderOf}).
 */

export interface PickedFile {
  file: File
  /** Path with '/' separators, ending in the file name; null for a file picked or dropped on its own. */
  relativePath: string | null
}

/** Files the operating system leaves in folders; they are never uploads. */
const NOISE = new Set(['.ds_store', 'thumbs.db', 'desktop.ini'])

function isNoise(name: string): boolean {
  const n = name.toLowerCase()
  return NOISE.has(n) || n.startsWith('._')
}

/**
 * The folder that directly contains the file, or null for a file with no
 * folder. {@code iAMD/EIAMD005/x.E2E} gives {@code EIAMD005}; so does
 * {@code EIAMD005/x.E2E}, which is what picking the patient's own folder gives.
 */
export function subjectFolderOf(relativePath: string | null | undefined): string | null {
  if (!relativePath) return null
  const parts = relativePath.split('/').filter((p) => p.length > 0)
  if (parts.length < 2) return null
  return parts[parts.length - 2]
}

/** A file list as the folder picker (or a plain picker) delivers it. */
export function fromFileList(files: FileList | File[] | null): PickedFile[] {
  if (!files) return []
  return Array.from(files)
    .filter((f) => !isNoise(f.name))
    .map((file) => ({ file, relativePath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || null }))
}

/* ---- drag and drop ---- */

interface FsEntry {
  isFile: boolean
  isDirectory: boolean
  name: string
  fullPath: string
}
interface FsFileEntry extends FsEntry {
  file(ok: (f: File) => void, fail?: (e: unknown) => void): void
}
interface FsDirectoryReader {
  readEntries(ok: (entries: FsEntry[]) => void, fail?: (e: unknown) => void): void
}
interface FsDirectoryEntry extends FsEntry {
  createReader(): FsDirectoryReader
}

/** readEntries returns a batch at a time (about 100 in Chromium); keep asking until it answers empty. */
async function readAllEntries(dir: FsDirectoryEntry): Promise<FsEntry[]> {
  const reader = dir.createReader()
  const all: FsEntry[] = []
  for (;;) {
    const batch = await new Promise<FsEntry[]>((resolve, reject) => reader.readEntries(resolve, reject))
    if (batch.length === 0) return all
    all.push(...batch)
  }
}

async function walk(entry: FsEntry, out: PickedFile[]): Promise<void> {
  if (entry.isFile) {
    if (isNoise(entry.name)) return
    const file = await new Promise<File>((resolve, reject) => (entry as FsFileEntry).file(resolve, reject))
    out.push({ file, relativePath: entry.fullPath.replace(/^\/+/, '') || null })
    return
  }
  if (entry.isDirectory) {
    for (const child of await readAllEntries(entry as FsDirectoryEntry)) await walk(child, out)
  }
}

/**
 * Everything in a drop, folders included.
 *
 * {@code webkitGetAsEntry} must be called while the drop event is still being
 * handled (the browser empties the transfer afterwards), so the entries are
 * taken first and only then walked. A browser without the API gives the plain
 * file list.
 */
export async function filesFromDataTransfer(dt: DataTransfer | null): Promise<PickedFile[]> {
  if (!dt) return []
  const items = Array.from(dt.items ?? [])
  const plain = Array.from(dt.files ?? [])
  const entries: FsEntry[] = []
  let supported = items.length > 0
  for (const item of items) {
    const getter = (item as DataTransferItem & { webkitGetAsEntry?: () => FsEntry | null }).webkitGetAsEntry
    if (typeof getter !== 'function') {
      supported = false
      break
    }
    const entry = getter.call(item)
    if (entry) entries.push(entry)
  }
  if (!supported) return fromFileList(plain)
  const out: PickedFile[] = []
  for (const entry of entries) await walk(entry, out)
  return out
}

/**
 * The programs that run on the acquisition PCs and report as uploaders.
 *
 * Their names are product names: the same in every language, so they are
 * not in the locale files. A kind this list does not know (a newer program)
 * is shown as the server named it.
 */
const PROGRAM_NAMES: Record<string, string> = {
  'export-watcher': 'Export Watcher',
  'optomed-bridge': 'Optomed Bridge',
}

export function programName(kind: string): string {
  return PROGRAM_NAMES[kind] ?? kind
}

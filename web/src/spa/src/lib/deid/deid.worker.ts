/**
 * Web Worker entry for the de-identification pipeline: keeps a several-hundred
 * MB {@code .e2e} off the UI thread. One request per worker; the client
 * terminates the worker afterwards so the buffers are returned to the OS.
 */
import { analyzeFile, deidentifyFile, type DeidKind } from './pipeline'
import { isDeidError } from './errors'

export type DeidRequest =
  | { op: 'analyze'; file: File; kind: DeidKind }
  | { op: 'deidentify'; file: File; kind: DeidKind; label: string }

export type DeidResponse =
  | { ok: true; result: unknown }
  | { ok: false; code: string }

const ctx = self as unknown as {
  onmessage: ((e: MessageEvent<DeidRequest>) => void) | null
  postMessage(msg: DeidResponse): void
}

ctx.onmessage = async (e: MessageEvent<DeidRequest>) => {
  const req = e.data
  try {
    const result =
      req.op === 'analyze'
        ? await analyzeFile(req.file, req.kind)
        : await deidentifyFile(req.file, req.kind, req.label)
    ctx.postMessage({ ok: true, result })
  } catch (err) {
    // The code only: an unexpected error's text could quote the file.
    ctx.postMessage({ ok: false, code: isDeidError(err) ? err.code : 'parse' })
  }
}

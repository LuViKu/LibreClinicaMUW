/**
 * Browser de-identification — the main-thread side. Runs the pipeline in a Web
 * Worker where the runtime has one (the browser), inline where it has not
 * (unit tests). The worker is created per call and terminated afterwards.
 */
import { DeidError, type DeidErrorCode } from './errors'
import type { AnalyzeResult, DeidKind, DeidResult } from './pipeline'
import type { DeidRequest, DeidResponse } from './deid.worker'

function inWorker<T>(req: DeidRequest): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const worker = new Worker(new URL('./deid.worker.ts', import.meta.url), { type: 'module' })
    const done = () => worker.terminate()
    worker.onmessage = (e: MessageEvent<DeidResponse>) => {
      done()
      if (e.data.ok) resolve(e.data.result as T)
      else reject(new DeidError(e.data.code as DeidErrorCode))
    }
    worker.onerror = () => {
      done()
      reject(new DeidError('parse', 'worker'))
    }
    worker.postMessage(req)
  })
}

export async function analyzeInBackground(file: File, kind: DeidKind): Promise<AnalyzeResult> {
  if (typeof Worker === 'undefined') {
    const { analyzeFile } = await import('./pipeline')
    return analyzeFile(file, kind)
  }
  return inWorker<AnalyzeResult>({ op: 'analyze', file, kind })
}

export async function deidentifyInBackground(file: File, kind: DeidKind, label: string): Promise<DeidResult> {
  if (typeof Worker === 'undefined') {
    const { deidentifyFile } = await import('./pipeline')
    return deidentifyFile(file, kind, label)
  }
  return inWorker<DeidResult>({ op: 'deidentify', file, kind, label })
}

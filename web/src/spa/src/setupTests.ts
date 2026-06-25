/**
 * Vitest setup file. Runs once before every test file.
 * Add global mocks (fetch stubs, IntersectionObserver shim, etc.) here.
 */

// jsdom 25's File shim does NOT implement `.arrayBuffer()` (it predates
// the WHATWG addition). Real browsers all have it, and the `parseE2e`
// SPA helper relies on it. Polyfill once at setup time so tests can
// pass plain `File` objects and the parser stays browser-realistic.
//
// IMPORTANT: only patches File.prototype, not Blob.prototype — the
// notesExport test relies on `new Response(new Blob(...))` working
// through Node's native Response code path, and adding arrayBuffer to
// Blob changes that path in a way that hits jsdom's missing
// Blob.stream() shim.
if (typeof File !== 'undefined' && typeof File.prototype.arrayBuffer !== 'function') {
  File.prototype.arrayBuffer = function arrayBuffer(this: File): Promise<ArrayBuffer> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => {
        const result = reader.result
        if (result instanceof ArrayBuffer) {
          resolve(result)
        } else {
          reject(new Error('FileReader returned a non-ArrayBuffer result'))
        }
      }
      reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'))
      reader.readAsArrayBuffer(this)
    })
  }
}

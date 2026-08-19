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
// jsdom does not implement HTMLCanvasElement.getContext (it throws "Not
// implemented" without the native `canvas` package). Chart.js + the retinal
// overlay/heatmap components (FundusOverlay, BscanViewer, NamdFluidTrendChart)
// paint to a 2D context on mount, so their test files errored at the FILE
// level even though every assertion passed. Install a minimal 2D-context stub
// so those renders are inert no-ops. Methods that must return a value do;
// everything else (and any unlisted API) is a no-op via the Proxy fallback.
if (typeof HTMLCanvasElement !== 'undefined') {
  const noop = () => {}
  HTMLCanvasElement.prototype.getContext = function getContext(this: HTMLCanvasElement, kind: string) {
    if (kind !== '2d') return null
    const gradient = { addColorStop: noop }
    const base: Record<string, unknown> = {
      canvas: this,
      save: noop, restore: noop, beginPath: noop, closePath: noop,
      moveTo: noop, lineTo: noop, bezierCurveTo: noop, quadraticCurveTo: noop,
      arc: noop, arcTo: noop, ellipse: noop, rect: noop, roundRect: noop,
      fill: noop, stroke: noop, clip: noop,
      fillRect: noop, strokeRect: noop, clearRect: noop,
      fillText: noop, strokeText: noop, drawImage: noop,
      translate: noop, scale: noop, rotate: noop, transform: noop,
      setTransform: noop, resetTransform: noop,
      setLineDash: noop, getLineDash: () => [] as number[],
      measureText: () => ({ width: 0 }),
      createLinearGradient: () => gradient,
      createRadialGradient: () => gradient,
      createConicGradient: () => gradient,
      createPattern: () => null,
      createImageData: (w: number, h: number) => ({ data: new Uint8ClampedArray(Math.max(1, w) * Math.max(1, h) * 4), width: w, height: h }),
      getImageData: (_x: number, _y: number, w: number, h: number) => ({ data: new Uint8ClampedArray(Math.max(1, w) * Math.max(1, h) * 4), width: w, height: h }),
      putImageData: noop,
      getContextAttributes: () => ({}),
    }
    // Proxy so any property Chart.js sets (fillStyle, font, …) round-trips,
    // and any unlisted method call is a harmless no-op.
    return new Proxy(base, {
      get(target, prop: string) {
        if (prop in target) return target[prop]
        return noop
      },
      set(target, prop: string, value) {
        target[prop] = value
        return true
      },
    })
  } as unknown as typeof HTMLCanvasElement.prototype.getContext
  HTMLCanvasElement.prototype.toDataURL = () => 'data:image/png;base64,'
}

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

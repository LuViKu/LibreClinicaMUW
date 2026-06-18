/**
 * Wave 2A — Unit tests for {@link useJobStatusStream}.
 *
 * Stubs the global {@code EventSource} with a tiny test double so the
 * test can drive the open / status / heartbeat / error transitions
 * deterministically without a real network. The double also lets the
 * test introspect the constructed URL + the {@code withCredentials}
 * flag so the contract with the backend stays pinned.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

import { useJobStatusStream } from '../useJobStatusStream'

/* ----------------------------------------------------------------------- */
/* EventSource test double                                                 */
/* ----------------------------------------------------------------------- */

interface FakeEventSourceInit {
  withCredentials?: boolean
}

class FakeEventSource {
  static instances: FakeEventSource[] = []

  /** Captured constructor args. */
  url: string
  withCredentials: boolean

  /** Standard event handlers (assignment form). */
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null

  /** Named-event listener map: event-name → handler list. */
  private listeners = new Map<string, Array<(e: MessageEvent) => void>>()

  closed = false

  constructor(url: string, init: FakeEventSourceInit = {}) {
    this.url = url
    this.withCredentials = init.withCredentials ?? false
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, handler: (e: MessageEvent) => void): void {
    const list = this.listeners.get(type) ?? []
    list.push(handler)
    this.listeners.set(type, list)
  }

  close(): void {
    this.closed = true
  }

  /* ---- Test helpers ---- */

  emitOpen(): void {
    if (this.onopen) this.onopen(new Event('open'))
  }

  emitStatus(data: string): void {
    const event = new MessageEvent('status', { data })
    for (const h of this.listeners.get('status') ?? []) {
      h(event)
    }
  }

  emitHeartbeat(): void {
    const event = new MessageEvent('heartbeat', { data: '' })
    for (const h of this.listeners.get('heartbeat') ?? []) {
      h(event)
    }
  }

  emitError(): void {
    if (this.onerror) this.onerror(new Event('error'))
  }

  static reset(): void {
    FakeEventSource.instances = []
  }

  static latest(): FakeEventSource {
    if (FakeEventSource.instances.length === 0) {
      throw new Error('No FakeEventSource instances yet')
    }
    return FakeEventSource.instances[FakeEventSource.instances.length - 1]
  }
}

/**
 * Mount a host component so the {@code onBeforeUnmount} hook actually
 * fires when the wrapper is unmounted. Composables can't run outside a
 * setup() context, so the indirection is necessary.
 */
function mountWithJobId(jobId: number | null) {
  const Host = defineComponent({
    setup() {
      const id = ref<number | null>(jobId)
      const stream = useJobStatusStream(id)
      return { id, stream }
    },
    template: '<div />',
  })
  return mount(Host)
}

/* ----------------------------------------------------------------------- */
/* Tests                                                                   */
/* ----------------------------------------------------------------------- */

describe('useJobStatusStream', () => {
  beforeEach(() => {
    FakeEventSource.reset()
    vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource)
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('opens an EventSource against the SSE endpoint with credentials', async () => {
    mountWithJobId(42)
    await Promise.resolve()
    expect(FakeEventSource.instances).toHaveLength(1)
    const es = FakeEventSource.latest()
    expect(es.url).toBe('/LibreClinica/pages/api/v1/retinal-jobs/42/status/stream')
    expect(es.withCredentials).toBe(true)
  })

  it('updates status + connected when a status event arrives', async () => {
    const w = mountWithJobId(7)
    await Promise.resolve()
    const es = FakeEventSource.latest()
    es.emitOpen()
    expect((w.vm as unknown as { stream: { connected: { value: boolean } } }).stream.connected.value)
      .toBe(true)
    es.emitStatus('segmenting')
    expect((w.vm as unknown as { stream: { status: { value: string | null } } }).stream.status.value)
      .toBe('segmenting')
    es.emitStatus('done')
    expect((w.vm as unknown as { stream: { status: { value: string | null } } }).stream.status.value)
      .toBe('done')
  })

  it('treats heartbeat as a liveness signal but does NOT update status', async () => {
    const w = mountWithJobId(7)
    await Promise.resolve()
    const es = FakeEventSource.latest()
    es.emitOpen()
    es.emitStatus('queued')
    es.emitHeartbeat()
    const stream = (w.vm as unknown as { stream: { connected: { value: boolean }, status: { value: string | null } } }).stream
    expect(stream.connected.value).toBe(true)
    expect(stream.status.value).toBe('queued')
  })

  it('flips connected to false on error and schedules a reconnect with exponential backoff', async () => {
    const w = mountWithJobId(7)
    await Promise.resolve()
    const es = FakeEventSource.latest()
    es.emitOpen()
    es.emitError()
    const stream = (w.vm as unknown as { stream: { connected: { value: boolean }, error: { value: string | null } } }).stream
    expect(stream.connected.value).toBe(false)
    expect(stream.error.value).not.toBeNull()

    // First reconnect after ~1s
    expect(FakeEventSource.instances).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1_100)
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('resets backoff after a successful open', async () => {
    const w = mountWithJobId(7)
    await Promise.resolve()
    let es = FakeEventSource.latest()
    // First failure
    es.emitError()
    await vi.advanceTimersByTimeAsync(1_100)
    es = FakeEventSource.latest()
    // Open succeeds
    es.emitOpen()
    // Second failure — backoff should restart at 1s, not continue from
    // the previous step.
    es.emitError()
    expect(FakeEventSource.instances).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1_100)
    expect(FakeEventSource.instances).toHaveLength(3)
    void w
  })

  it('closes the stream on unmount', async () => {
    const w = mountWithJobId(9)
    await Promise.resolve()
    const es = FakeEventSource.latest()
    expect(es.closed).toBe(false)
    w.unmount()
    expect(es.closed).toBe(true)
  })

  it('does NOT open when jobId is null', async () => {
    mountWithJobId(null)
    await Promise.resolve()
    expect(FakeEventSource.instances).toHaveLength(0)
  })

  it('invokes onStatus callback per status event', async () => {
    const calls: string[] = []
    const Host = defineComponent({
      setup() {
        const id = ref<number | null>(11)
        const stream = useJobStatusStream(id, {
          onStatus: (e) => calls.push(e.status),
        })
        return { id, stream }
      },
      template: '<div />',
    })
    mount(Host)
    await Promise.resolve()
    const es = FakeEventSource.latest()
    es.emitStatus('queued')
    es.emitStatus('segmenting')
    es.emitStatus('done')
    expect(calls).toEqual(['queued', 'segmenting', 'done'])
  })
})

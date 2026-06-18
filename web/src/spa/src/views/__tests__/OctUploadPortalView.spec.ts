/**
 * Phase E retinal-inference (Wave C) — OctUploadPortalView integration spec.
 *
 * End-to-end-ish: drives the view through the three-artboard state
 * machine (ready → parsing → review) via the real store, with the
 * /resolve and /commit API calls stubbed at the {@code @/api/octPortal}
 * module boundary.
 *
 * Reuses Wave B's `single-scan.e2e` fixture so the parser path stays
 * realistic — we exercise both the .e2e header read AND the store's
 * resolve → confirm → commit chain without ever touching `fetch`.
 *
 * Covers:
 *  - ready state when no files dropped
 *  - drop → parsing → review transition
 *  - suggested row renders Bestätigen + click commits with eventCrfId
 *  - park flow (novisit row → Später zuordnen → commit with park=true)
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// Stub the OCT-portal API module at the import boundary. The view +
// store both import from '@/api/octPortal'; this mock replaces the
// network layer with deterministic test doubles.
vi.mock('@/api/octPortal', () => {
  return {
    resolveScans: vi.fn(),
    commitScan: vi.fn(),
    undoCommit: vi.fn(),
    OctPortalError: class OctPortalError extends Error {
      readonly status: number
      readonly body: unknown
      constructor(status: number, message: string, body: unknown = null) {
        super(message)
        this.status = status
        this.body = body
      }
    },
  }
})

import OctUploadPortalView from '@/views/OctUploadPortalView.vue'
import {
  resolveScans,
  commitScan,
  undoCommit,
  type ResolveResponse,
  type CommitResponse,
} from '@/api/octPortal'

const SINGLE_SCAN_FIXTURE_PATH = resolve(
  process.cwd(),
  'src/lib/__tests__/fixtures/single-scan.e2e',
)

function loadFixtureAsFile(name = 'single-scan.e2e'): File {
  const bytes = readFileSync(SINGLE_SCAN_FIXTURE_PATH)
  return new File([new Uint8Array(bytes)], name, {
    type: 'application/octet-stream',
  })
}

/**
 * Wait until the predicate is true or we hit max-iter. The store's
 * pipeline involves FileReader (microtask-edge), parseE2e (async),
 * and a single resolveScans call (mocked, sync-ish) — so chaining
 * 5-10 flushPromises rounds is enough in practice but we keep a
 * generous ceiling to absorb jsdom timing variance.
 */
async function flushUntil(
  predicate: () => boolean,
  maxRounds = 50,
): Promise<void> {
  for (let i = 0; i < maxRounds; i++) {
    if (predicate()) return
    await flushPromises()
    // Yield to the event loop so FileReader's load event fires.
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  // Last chance — let the assertion downstream surface the failure.
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(OctUploadPortalView, {
    global: { plugins: [pinia] },
    attachTo: document.body,
  })
}

/** Helper: synthesize the resolve response for a single scan with a
 *  matching event. Mirrors the controller's "suggested" path. */
function resolveResponseWithEvent(patientId: string): ResolveResponse {
  return {
    scans: [
      {
        patientId,
        candidates: [
          {
            studyId: 7,
            studyName: 'GA-Studie',
            studyOid: 'S_GA',
            studySubjectId: 42,
            subjectLabel: patientId,
            siteName: null,
            matchingEvent: {
              eventCrfId: 1234,
              definitionLabel: 'V1 · Follow-up',
              dateStart: '2024-01-15',
              matchPolicy: 'same-day',
            },
          },
        ],
        state: 'suggested',
      },
    ],
  }
}

function resolveResponseWithoutEvent(patientId: string): ResolveResponse {
  return {
    scans: [
      {
        patientId,
        candidates: [
          {
            studyId: 7,
            studyName: 'GA-Studie',
            studyOid: 'S_GA',
            studySubjectId: 42,
            subjectLabel: patientId,
            siteName: null,
            matchingEvent: null,
          },
        ],
        state: 'novisit',
      },
    ],
  }
}

const COMMIT_RESPONSE: CommitResponse = { jobId: 999, status: 'QUEUED' }

beforeEach(() => {
  vi.mocked(resolveScans).mockReset()
  vi.mocked(commitScan).mockReset()
  vi.mocked(undoCommit).mockReset()
})

describe('OctUploadPortalView — state machine + store wiring', () => {
  it('renders the ready state with the hero dropzone when no files have been dropped', () => {
    const w = mountView()
    expect(w.find('[data-testid="e2e-dropzone-hero"]').exists()).toBe(true)
    expect(w.text()).toContain('OCT-Scans hochladen')
    expect(w.text()).toContain('Studienübergreifend')
    expect(w.find('[data-testid="parse-queue"]').exists()).toBe(false)
    expect(w.find('[data-testid="review-queue"]').exists()).toBe(false)
  })

  it('drops a single .e2e and transitions parsing → review with a suggested row', async () => {
    vi.mocked(resolveScans).mockResolvedValue(resolveResponseWithEvent('TEST-001'))
    const w = mountView()

    // Drive the store directly via the dropzone emit — using the real
    // <E2eDropzone> here would require a synthetic DragEvent dance
    // (the dedicated E2eDropzone.spec covers that path).
    const dropzone = w.findComponent({ name: 'E2eDropzone' })
    dropzone.vm.$emit('files-added', [loadFixtureAsFile()])

    // Allow the addFiles → parseE2e (FileReader callback)
    // → resolveScans chain to settle. The parser bounces through
    // a FileReader onload event, so a single flushPromises isn't
    // enough — we need to yield to the event loop a few times.
    await flushUntil(() => w.find('[data-testid="review-queue"]').exists())
    await w.vm.$nextTick()

    // Review queue should be visible with one suggested row.
    expect(w.find('[data-testid="review-queue"]').exists()).toBe(true)
    expect(w.find('[data-testid="parse-queue"]').exists()).toBe(false)
    expect(w.text()).toContain('Bestätigen')
    expect(w.text()).toContain('Patient gefunden')
    expect(vi.mocked(resolveScans)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(resolveScans).mock.calls[0][0][0]).toMatchObject({
      patientId: 'TEST-001',
      laterality: 'OD',
    })
  })

  it('clicking Bestätigen commits with eventCrfId + flips the row to committed', async () => {
    vi.mocked(resolveScans).mockResolvedValue(resolveResponseWithEvent('TEST-001'))
    vi.mocked(commitScan).mockResolvedValue(COMMIT_RESPONSE)

    const w = mountView()
    const dropzone = w.findComponent({ name: 'E2eDropzone' })
    dropzone.vm.$emit('files-added', [loadFixtureAsFile()])
    await flushUntil(() => !!w.find('[data-testid^="action-confirm-"]').exists())

    const confirmBtn = w.find('[data-testid^="action-confirm-"]')
    expect(confirmBtn.exists()).toBe(true)
    await confirmBtn.trigger('click')
    await flushUntil(() => vi.mocked(commitScan).mock.calls.length > 0)

    // commitScan was called with the resolved event_crf_id + park=false.
    expect(vi.mocked(commitScan)).toHaveBeenCalledTimes(1)
    const payload = vi.mocked(commitScan).mock.calls[0][0]
    expect(payload.patientId).toBe('TEST-001')
    expect(payload.laterality).toBe('OD')
    expect(payload.eventCrfId).toBe(1234)
    expect(payload.park).toBe(false)
    expect(payload.file).toBeInstanceOf(File)

    // Row should now render the Rückgängig affordance.
    await flushUntil(() => w.text().includes('Rückgängig'))
    expect(w.text()).toContain('Rückgängig')
    expect(w.find('[data-testid^="action-undo-"]').exists()).toBe(true)
  })

  it('park flow — novisit row → Später zuordnen → commit with park=true', async () => {
    vi.mocked(resolveScans).mockResolvedValue(resolveResponseWithoutEvent('TEST-001'))
    vi.mocked(commitScan).mockResolvedValue({ jobId: 1001, status: 'PARKED' })

    const w = mountView()
    const dropzone = w.findComponent({ name: 'E2eDropzone' })
    dropzone.vm.$emit('files-added', [loadFixtureAsFile()])
    await flushUntil(() => w.text().includes('Später zuordnen'))

    // No matching event → row should be in `novisit`, surfacing
    // Später zuordnen.
    expect(w.text()).toContain('Später zuordnen')
    expect(w.text()).toContain('Kein Termin')

    const parkBtn = w.find('[data-testid^="action-park-"]')
    expect(parkBtn.exists()).toBe(true)
    await parkBtn.trigger('click')
    await flushUntil(() => vi.mocked(commitScan).mock.calls.length > 0)

    expect(vi.mocked(commitScan)).toHaveBeenCalledTimes(1)
    const payload = vi.mocked(commitScan).mock.calls[0][0]
    expect(payload.park).toBe(true)
    expect(payload.eventCrfId).toBeNull()
  })
})

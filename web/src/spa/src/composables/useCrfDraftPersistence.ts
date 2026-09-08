/**
 * CRF-builder draft autosave + restore (2026-08).
 *
 * <p>The authoring draft lives only in the {@link useCrfAuthoringStore}
 * in-memory ref, and the canvas view calls {@code store.reset()} on every
 * mount. So an idle logout (or a reload / tab close / crash) followed by
 * re-entry silently discards all unsaved sections and items — the loss this
 * composable prevents.
 *
 * <p>It mirrors the SPA's hand-rolled persistence convention (a module-level
 * key + a {@code typeof localStorage} guard + try/catch, as in
 * FundusOverlay / bcvaPortal) rather than pulling in a persistence plugin.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link start} begins a debounced deep-watch that writes the draft to
 *       {@code localStorage} under a per-CRF key — but only once the draft is
 *       "meaningful" (has real content), so the empty draft that {@code reset()}
 *       produces on mount never clobbers a previously-saved copy.</li>
 *   <li>{@link savedDraft} exposes what was on disk at {@link start} time, so
 *       the view can offer a non-destructive restore banner.</li>
 *   <li>{@link restore} hydrates the store from that copy; {@link discard} and
 *       {@link clear} remove the key (discard = user rejected; clear = saved
 *       successfully, so the draft is now persisted server-side).</li>
 * </ul>
 */
import { getCurrentInstance, onUnmounted, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useCrfAuthoringStore, type AuthoringDraft } from '@/stores/crfAuthoring'

/** Bumped if the persisted shape ever changes, so stale copies are ignored. */
const KEY_PREFIX = 'crfDraft:v1:'

/** Debounce window for autosave writes — long enough to coalesce a burst of
 *  edits, short enough that a sudden logout rarely loses more than a keystroke. */
const AUTOSAVE_DEBOUNCE_MS = 800

interface StoredDraft {
  savedAt: string
  draft: AuthoringDraft
}

function storageAvailable(): boolean {
  return typeof localStorage !== 'undefined'
}

function keyFor(crfOid: string): string {
  return `${KEY_PREFIX}${crfOid}`
}

/**
 * A draft is worth persisting once it carries operator intent: any item in any
 * section, a version name/description/notes, or more than the single default
 * section. The pristine {@code emptyDraft()} fails all of these, so the
 * mount-time {@code reset()} produces no write.
 */
export function isMeaningfulDraft(d: AuthoringDraft): boolean {
  if (d.versionName.trim() || d.versionDescription.trim() || d.revisionNotes.trim()) return true
  if (d.sections.length > 1) return true
  return d.sections.some((s) => s.items.length > 0)
}

export function useCrfDraftPersistence(crfOid: Ref<string> | ComputedRef<string>) {
  const store = useCrfAuthoringStore()

  /** The on-disk copy captured at start() — drives the restore banner. */
  const savedDraft = ref<StoredDraft | null>(null)

  function read(): StoredDraft | null {
    if (!storageAvailable() || !crfOid.value) return null
    try {
      const raw = localStorage.getItem(keyFor(crfOid.value))
      if (!raw) return null
      const parsed = JSON.parse(raw) as StoredDraft
      if (!parsed || typeof parsed !== 'object' || !parsed.draft) return null
      return parsed
    } catch {
      return null
    }
  }

  function write(draft: AuthoringDraft): void {
    if (!storageAvailable() || !crfOid.value) return
    try {
      const payload: StoredDraft = { savedAt: new Date().toISOString(), draft }
      localStorage.setItem(keyFor(crfOid.value), JSON.stringify(payload))
    } catch {
      // Quota / private-mode / serialization failure — autosave is best-effort;
      // never let it break editing.
    }
  }

  /** Remove any persisted copy for this CRF. */
  function clear(): void {
    if (!storageAvailable() || !crfOid.value) return
    try {
      localStorage.removeItem(keyFor(crfOid.value))
    } catch {
      /* ignore */
    }
    savedDraft.value = null
  }

  /** User rejected the restore — same as clear(), named for intent at the call site. */
  function discard(): void {
    clear()
  }

  /** Replace the live draft with the persisted copy. No-op if none. */
  function restore(): boolean {
    const stored = savedDraft.value ?? read()
    if (!stored) return false
    store.hydrateDraft(stored.draft)
    savedDraft.value = null
    return true
  }

  let debounceHandle: ReturnType<typeof setTimeout> | null = null
  let stopWatch: (() => void) | null = null

  /**
   * Snapshot any existing on-disk draft (for the banner), then begin autosaving.
   * Call AFTER the view's mount-time reset()/loadFromVersion() so the snapshot
   * reflects the previous session, not the freshly-reset draft.
   */
  function start(): void {
    savedDraft.value = read()
    if (stopWatch) return // idempotent
    stopWatch = watch(
      () => store.draft,
      (d) => {
        if (!isMeaningfulDraft(d)) return
        if (debounceHandle) clearTimeout(debounceHandle)
        // Snapshot now (deep-clone via JSON) so a later mutation can't mutate
        // what we're about to write mid-serialization.
        const snapshot = JSON.parse(JSON.stringify(d)) as AuthoringDraft
        debounceHandle = setTimeout(() => write(snapshot), AUTOSAVE_DEBOUNCE_MS)
      },
      { deep: true },
    )
  }

  function stop(): void {
    if (debounceHandle) {
      clearTimeout(debounceHandle)
      debounceHandle = null
    }
    if (stopWatch) {
      stopWatch()
      stopWatch = null
    }
  }

  // Only register the lifecycle hook when used inside a component setup;
  // called bare (e.g. in a unit test) the consumer calls stop() itself.
  if (getCurrentInstance()) onUnmounted(stop)

  return { savedDraft, start, stop, restore, discard, clear, read }
}

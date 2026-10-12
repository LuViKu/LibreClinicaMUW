import { vi } from 'vitest'

/**
 * A working Web Storage for tests.
 *
 * jsdom's storage is not functional here (its opaque origin, and Node's own
 * experimental `localStorage` global shadowing it: `clear` is not even a
 * function), so tests that care where a value is kept install these two on the
 * global object. They are separate instances, so a test can say a value went
 * to one and not the other.
 */
export class MemoryStorage implements Storage {
  private readonly entries = new Map<string, string>()

  get length(): number {
    return this.entries.size
  }

  key(index: number): string | null {
    return [...this.entries.keys()][index] ?? null
  }

  getItem(key: string): string | null {
    return this.entries.get(String(key)) ?? null
  }

  setItem(key: string, value: string): void {
    this.entries.set(String(key), String(value))
  }

  removeItem(key: string): void {
    this.entries.delete(String(key))
  }

  clear(): void {
    this.entries.clear()
  }

  /** Everything held, as one string, for "this value is nowhere in here" assertions. */
  dump(): string {
    return JSON.stringify([...this.entries.entries()])
  }
}

/** Installs fresh, empty `sessionStorage` and `localStorage`; `vi.unstubAllGlobals()` puts the originals back. */
export function stubWebStorage(): { session: MemoryStorage; local: MemoryStorage } {
  const session = new MemoryStorage()
  const local = new MemoryStorage()
  vi.stubGlobal('sessionStorage', session)
  vi.stubGlobal('localStorage', local)
  return { session, local }
}

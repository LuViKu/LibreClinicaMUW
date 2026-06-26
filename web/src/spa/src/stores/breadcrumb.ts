/**
 * Breadcrumb store — views publish the full nested trail; clear() on unmount.
 *
 * <p>A leaf view publishes the full trail it knows (parents + leaf +
 * dynamic IDs) and {@code App.vue}'s top-bar consumes it. The view
 * calls {@code set([...])} when its data resolves and {@code clear()}
 * on unmount so the next view's fallback (the static activeStudy-only
 * chain) wins.
 *
 * <p>Each crumb's {@code label} is already translated by the view; the
 * store stays i18n-agnostic. {@code to} is a Vue Router target;
 * {@code null} marks the active leaf.
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface BreadcrumbItem {
  /** Display string — already translated by the publishing view. */
  label: string
  /**
   * Router target for the crumb. Provide a string to make the crumb
   * clickable; provide {@code null} (or omit) for the active leaf
   * which renders as a non-link.
   */
  to?: string | null
}

export const useBreadcrumbStore = defineStore('breadcrumb', () => {
  /**
   * The published trail. {@code null} signals "fall back to the
   * App.vue default" (activeStudy + route.meta.title).
   */
  const items = ref<BreadcrumbItem[] | null>(null)

  function set(next: BreadcrumbItem[]): void {
    items.value = next
  }

  function clear(): void {
    items.value = null
  }

  return { items, set, clear }
})

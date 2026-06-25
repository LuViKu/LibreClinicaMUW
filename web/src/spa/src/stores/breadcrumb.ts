/**
 * Breadcrumb store — per-view nested-navigation trail.
 *
 * <p>2026-06-23 user-feedback round — the original breadcrumb in
 * {@code App.vue} surfaced two crumbs ({@code activeStudy} +
 * {@code route.meta.title}) and never reflected the nested
 * navigation that brought the operator to a leaf view. Opening a
 * subject from the matrix showed {@code RIS > Subject} instead of
 * {@code RIS > Subjects > EIAMD150}; opening a CRF from a visit
 * showed {@code RIS > CRF Entry} instead of
 * {@code RIS > Subjects > EIAMD150 > V03 > CRF Name}.
 *
 * <p>This store lets a leaf view publish the full trail it knows
 * about (parents + leaf + dynamic IDs) and have {@code App.vue}'s
 * top-bar consume it. The view is responsible for:
 *   1. Calling {@code set([...])} when its data resolves (subject
 *      label, event label, etc.).
 *   2. Calling {@code clear()} on unmount so the next view's
 *      fallback (the static activeStudy-only chain) wins.
 *
 * <p>Each crumb's {@code label} is already translated (the view has
 * {@code useI18n()}); the store stays i18n-agnostic so non-translated
 * dynamic data (subject ids, visit labels) can sit next to translated
 * statics. {@code to} is a Vue Router target ({@code /subjects},
 * {@code `/subjects/${oid}`}); {@code null} marks the active leaf.
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

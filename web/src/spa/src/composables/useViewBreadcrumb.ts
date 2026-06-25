/**
 * Composable wrapper around {@link useBreadcrumbStore} for views.
 *
 * <p>Pushes the supplied trail to the store on mount + whenever the
 * reactive source changes, then clears the trail on unmount so the
 * next view's fallback wins. Views call this once per `<script setup>`;
 * the lifecycle book-keeping stays out of the consumer.
 */
import { watch, onMounted, onUnmounted, type Ref, type ComputedRef } from 'vue'
import { useBreadcrumbStore, type BreadcrumbItem } from '@/stores/breadcrumb'

/**
 * Publish a per-view breadcrumb trail.
 *
 * @param source  Reactive trail. Returning {@code null} signals "no
 *                trail yet" (typically while async data is loading) —
 *                the store stays empty and App.vue's static fallback
 *                shows the route-meta title in the meantime.
 */
export function useViewBreadcrumb(
  source: Ref<BreadcrumbItem[] | null> | ComputedRef<BreadcrumbItem[] | null>,
): void {
  const store = useBreadcrumbStore()
  onMounted(() => {
    if (source.value) store.set(source.value)
  })
  watch(source, (next) => {
    if (next) store.set(next)
    else store.clear()
  })
  onUnmounted(() => {
    store.clear()
  })
}

import { defineStore, storeToRefs } from 'pinia'
import { computed, ref, watch } from 'vue'
import type { ComputedRef } from 'vue'
import { useAuthStore } from './auth'
import { i18n } from '@/i18n'
import { findModule } from '@/studyModules/registry'
import type { InjectionEntry, InjectionSlotId, StudyModuleManifest } from '@/studyModules/types'

/**
 * Active study-module store.
 *
 * <p>Tracks which {@link StudyModuleManifest} (if any) matches the
 * currently-bound study's {@code protocol_type}. The match is derived
 * from {@link useAuthStore} — switching studies via the picker (or
 * activating one for the first time) re-derives the active module
 * automatically.
 *
 * <p>Side effect: on activation, if the manifest declares a
 * {@code loadI18n()}, the store resolves it once and merges the
 * returned {@code de} / {@code en} bundles into vue-i18n via
 * {@code i18n.global.mergeLocaleMessage}. Subsequent activations of
 * the same module skip the load — deactivating does NOT unmerge the
 * messages (cheap to keep around; reactivating later costs zero).
 *
 * <p>Host views consume the store via {@link injectionsFor} — they
 * pass an {@link InjectionSlotId} and render whatever entries the
 * active module advertises for that slot. The slot id is opaque to
 * the host.
 *
 * <p>The i18n instance is dynamically imported when needed so this
 * store survives unit tests that don't boot {@code main.ts} (the
 * spec stubs the merge calls via {@code vi.mock}).
 */
export const useStudyModuleStore = defineStore('studyModules', () => {
  const auth = useAuthStore()
  const loadedModuleIds = ref<Set<string>>(new Set<string>())

  const activeModule = computed<StudyModuleManifest | null>(() => {
    const pt = auth.user?.activeStudy?.protocolType ?? null
    return findModule(pt)
  })

  function injectionsFor(slotId: InjectionSlotId): InjectionEntry[] {
    const m = activeModule.value
    if (!m) return []
    return m.injections?.[slotId] ?? []
  }

  /**
   * Lazy i18n merge — runs once per module per session. The i18n
   * instance comes from the dedicated {@code @/i18n} module so unit
   * tests can mock it via {@code vi.mock('@/i18n')}. Watcher is
   * {@code immediate: true} so refresh-into-a-bound-study activates
   * the manifest without waiting for the next study switch.
   */
  watch(
    activeModule,
    async (m, prev) => {
      if (!m || m === prev) return
      if (loadedModuleIds.value.has(m.protocolType)) return
      if (!m.loadI18n) {
        // No lazy bundle declared — flag as loaded so we don't keep
        // re-checking on every activation flip.
        loadedModuleIds.value.add(m.protocolType)
        return
      }
      try {
        const payload = await m.loadI18n()
        i18n.global.mergeLocaleMessage('de', payload.de)
        i18n.global.mergeLocaleMessage('de-AT', payload.de)
        i18n.global.mergeLocaleMessage('en', payload.en)
        loadedModuleIds.value.add(m.protocolType)
      } catch (e) {
        // Swallow the failure — losing a translation bundle should
        // not break the app boot. The keys fall back to the i18n
        // missing-key handler, which logs in dev and silently renders
        // the key in prod.
        // eslint-disable-next-line no-console
        console.warn('[studyModules] loadI18n failed for', m.protocolType, e)
      }
    },
    { immediate: true },
  )

  return {
    activeModule,
    loadedModuleIds,
    injectionsFor,
  }
})

/**
 * Thin composable wrapper around {@link useStudyModuleStore} for views
 * that only care about the active manifest. Returns a reactive ref so
 * {@code <template>}-side access stays reactive across study switches.
 */
export function useActiveStudyModule(): { activeModule: ComputedRef<StudyModuleManifest | null> } {
  const store = useStudyModuleStore()
  const { activeModule } = storeToRefs(store) as unknown as {
    activeModule: ComputedRef<StudyModuleManifest | null>
  }
  return { activeModule }
}

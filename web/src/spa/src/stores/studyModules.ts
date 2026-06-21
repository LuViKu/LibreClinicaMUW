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
/**
 * Walk an incoming i18n bundle against the locale messages already
 * loaded into vue-i18n; emit a {@code console.warn} for every leaf key
 * that the incoming bundle would overwrite with a different value.
 *
 * <p>Detects the silent "last-loaded-wins" failure mode where two
 * modules independently define {@code studyModules.foo.bar} — vue-i18n
 * has no collision warning of its own. Only fires in dev to keep
 * production builds quiet.
 *
 * <p>Recursion stays object-only; arrays + primitives are treated as
 * leaves. Values that match (same string) are not warnings — only
 * actual overwrites are surfaced.
 */
export function detectI18nCollisions(
  moduleId: string,
  locale: string,
  existing: Record<string, unknown> | null | undefined,
  incoming: Record<string, unknown>,
  prefix = '',
): void {
  if (!existing) return
  for (const [k, v] of Object.entries(incoming)) {
    const path = prefix ? `${prefix}.${k}` : k
    const prior = (existing as Record<string, unknown>)[k]
    if (
      v !== null &&
      typeof v === 'object' &&
      !Array.isArray(v) &&
      prior !== null &&
      typeof prior === 'object' &&
      !Array.isArray(prior)
    ) {
      detectI18nCollisions(
        moduleId,
        locale,
        prior as Record<string, unknown>,
        v as Record<string, unknown>,
        path,
      )
    } else if (prior !== undefined && prior !== v) {
      // eslint-disable-next-line no-console
      console.warn(
        `[studyModules] i18n collision on "${locale}.${path}" — ` +
          `module "${moduleId}" overwrites a value previously set by another module.`,
      )
    }
  }
}

export const useStudyModuleStore = defineStore('studyModules', () => {
  const auth = useAuthStore()
  const loadedModuleIds = ref<Set<string>>(new Set<string>())

  const activeModule = computed<StudyModuleManifest | null>(() => {
    const pt = auth.user?.activeStudy?.protocolType ?? null
    return findModule(pt)
  })

  function injectionsFor<S extends InjectionSlotId>(slotId: S): InjectionEntry<S>[] {
    const m = activeModule.value
    if (!m) return []
    return (m.injections?.[slotId] as InjectionEntry<S>[] | undefined) ?? []
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
        if (import.meta.env.DEV) {
          detectI18nCollisions(
            m.protocolType,
            'de',
            i18n.global.getLocaleMessage('de') as Record<string, unknown>,
            payload.de,
          )
          detectI18nCollisions(
            m.protocolType,
            'en',
            i18n.global.getLocaleMessage('en') as Record<string, unknown>,
            payload.en,
          )
        }
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

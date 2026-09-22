import { defineStore, storeToRefs } from 'pinia'
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import type { Component, ComputedRef } from 'vue'
import { useAuthStore } from './auth'
import { i18n } from '@/i18n'
import { findModule } from '@/studyModules/registry'
import type { InjectionEntry, InjectionSlotId, StudyModuleManifest } from '@/studyModules/types'

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

/**
 * If {@code c} is a lazy-component thunk ({@code () => import(...)})
 * wrap it in {@code defineAsyncComponent} so {@code <component :is>}
 * resolves it correctly. Without this wrap the thunk is rendered as
 * the literal string "[object Promise]" (Vue treats the function as
 * a render function, calls it, and stringifies the resulting Promise).
 *
 * <p>If it's already a component object (or an already-wrapped async
 * component), pass it through untouched.
 *
 * <p>Cached identity-stable per thunk via a {@link WeakMap} so repeated
 * calls (re-renders, study switches that re-derive the activeModule)
 * don't churn through new {@code defineAsyncComponent} wrappers — Vue
 * would otherwise see a "new" component each tick and tear-down +
 * remount the slot, costing observable jank + breaking transitions.
 */
const asyncWrapCache = new WeakMap<object, Component>()
function wrapAsync(c: Component | (() => Promise<unknown>)): Component {
  if (typeof c !== 'function') return c as Component
  const cached = asyncWrapCache.get(c)
  if (cached) return cached
  const wrapped = defineAsyncComponent(c as () => Promise<{ default: Component }>)
  asyncWrapCache.set(c, wrapped)
  return wrapped
}

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

  /**
   * Activation discriminator.
   *
   * <p>2026-06-23 — decoupled from {@code study.protocol_type}. The
   * legacy CDISC field carries the
   * {observational, interventional} distinction and is gated by the
   * Build-Study UI to those two values, so it can't double as a
   * module discriminator without bypassing that validator. Activation
   * is now driven SOLELY by {@code study.enabledModules}, which the
   * admin toggles via the Study-Parameters → Module enrollment panel
   * ({@code StudyModuleEnrollmentApiController}).
   *
   * <p>The manifest's {@code protocolType} field name is kept for
   * back-compat — semantically it is now the "module id" used in
   * enrollment, no longer a protocol-type discriminator.
   *
   * <p>P3.0 — every enrolled module that resolves to a registered
   * manifest is active, not just the first. A study running both
   * imaging ingest and the nAMD decision aid needs both, and under the
   * old rule whichever the backend happened to list second was
   * silently inert: its routes loaded, its slots never rendered, and
   * nothing said why.
   */
  const activeModules = computed<StudyModuleManifest[]>(() => {
    const study = auth.user?.activeStudy
    if (!study) return []
    const enrolledRaw =
      (study as unknown as { enabledModules?: string[] }).enabledModules ?? []
    const out: StudyModuleManifest[] = []
    for (const moduleId of enrolledRaw) {
      const candidate = findModule(moduleId)
      // Enrollment rows can repeat once a site inherits its parent's.
      if (candidate && !out.includes(candidate)) out.push(candidate)
    }
    return out
  })

  /**
   * The first active module, for the callers that genuinely want one —
   * chiefly tests and the {@link useActiveStudyModule} shorthand.
   * Prefer {@link activeModules}; a host view that renders "the" module
   * will drop the others.
   */
  const activeModule = computed<StudyModuleManifest | null>(
    () => activeModules.value[0] ?? null,
  )

  /**
   * Every entry advertised for a slot, across all active modules, in
   * enrollment order.
   *
   * <p>Entry keys are unique within a module, not across them, so two
   * modules may both advertise {@code key: 'open-workspace'}. The keys
   * are namespaced here rather than de-duplicated: dropping one would
   * make a module invisible for a reason no author could see from
   * their own file.
   */
  function injectionsFor<S extends InjectionSlotId>(slotId: S): InjectionEntry<S>[] {
    const out: InjectionEntry<S>[] = []
    for (const m of activeModules.value) {
      const raw = (m.injections?.[slotId] as InjectionEntry<S>[] | undefined) ?? []
      for (const entry of raw) {
        out.push({
          ...entry,
          key: `${m.protocolType}:${entry.key}`,
          // Wrap lazy-component thunks here (see {@link wrapAsync}) so
          // host views can just do <component :is="entry.component" />.
          component: wrapAsync(entry.component as Component | (() => Promise<unknown>)),
        })
      }
    }
    return out
  }

  /**
   * Lazy i18n merge — runs once per module per session. The i18n
   * instance comes from the dedicated {@code @/i18n} module so unit
   * tests can mock it via {@code vi.mock('@/i18n')}. Watcher is
   * {@code immediate: true} so refresh-into-a-bound-study activates
   * the manifest without waiting for the next study switch.
   */
  async function loadI18nFor(m: StudyModuleManifest): Promise<void> {
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
  }

  // P3.0 — every active module's bundle, not just the first one's. A
  // module whose slots render but whose labels resolve to raw i18n keys
  // looks broken in a way that points at the wrong file.
  watch(
    activeModules,
    (mods) => {
      for (const m of mods) void loadI18nFor(m)
    },
    { immediate: true, deep: false },
  )

  return {
    activeModule,
    activeModules,
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

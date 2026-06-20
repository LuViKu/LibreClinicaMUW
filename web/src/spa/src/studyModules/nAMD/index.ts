/**
 * nAMD treat-and-extend — study module manifest.
 *
 * First consumer of the pluggable {@link StudyModuleManifest} SPI. Adds:
 *   - One route — {@code /studies/:studyOid/modules/namd} → the workspace.
 *   - One injection — a "Open nAMD workspace" CTA on SubjectDetailView
 *     (slot {@code subject-detail.workspace}).
 *   - Lazy German + English i18n bundles merged into vue-i18n on
 *     activation.
 *
 * <p>Activation discriminator — {@link StudyModuleManifest.protocolType}
 * matches {@code study.protocol_type} case-insensitively. The framework
 * normalises both sides via {@code toUpperCase()} at lookup.
 *
 * <h2>Temporary inline {@code StudyModuleManifest} declaration</h2>
 *
 * The canonical type lives in {@code studyModules/types.ts} — owned by
 * the framework agent on the parallel {@code wt-mod-framework} worktree
 * and merged in via the harmonize step. Until that file exists on this
 * branch, the type is inlined here. Harmonize replaces the inline block
 * with {@code import type \{ StudyModuleManifest \} from '../types'} —
 * the shape is identical so call-sites compile through unchanged.
 */
import type { RouteRecordRaw } from 'vue-router'
import type { Component } from 'vue'

// ----- temporary inline declaration — see file comment above. -----
type StudyModuleId = string
type InjectionSlotId =
  | 'subject-detail.tabs'
  | 'subject-detail.workspace'
  | 'event-detail.panels'
  | 'event-detail.actions'
  | 'crf-entry.banner'
  | 'nav.modules'

interface InjectionEntry {
  key: string
  labelKey: string
  component: Component | (() => Promise<Component | { default: Component }>)
  predicate?: (ctx: unknown) => boolean
}

interface VisitSchedulerContext {
  currentVisitOid: string
  lastFluidResult?: { irf: number; srf: number; ped: number }
  defaultIntervalDays: number
}
interface VisitSchedulerHint {
  intervalDays: number
  rationale: string
}

interface StudyModuleManifest {
  protocolType: StudyModuleId
  labelKey: string
  routes: RouteRecordRaw[]
  injections?: Partial<Record<InjectionSlotId, InjectionEntry[]>>
  loadI18n?: () => Promise<{ de: Record<string, unknown>; en: Record<string, unknown> }>
  visitScheduler?: (ctx: VisitSchedulerContext) => VisitSchedulerHint | null
}
// ----- end inline declaration -----

const manifest: StudyModuleManifest = {
  protocolType: 'NAMD',
  labelKey: 'studyModules.namd.label',
  routes: [
    {
      path: '',
      name: 'namd-workspace',
      component: () => import('./views/NamdWorkspaceView.vue'),
      meta: { role: ['Investigator', 'Data Manager', 'Administrator', 'CRC'] as const },
    },
  ],
  injections: {
    'subject-detail.workspace': [
      {
        key: 'open-workspace',
        labelKey: 'studyModules.namd.open',
        component: () => import('./components/NamdWorkspaceCta.vue'),
      },
    ],
  },
  loadI18n: async () => ({
    de: ((await import('./locales/de.json')) as { default: Record<string, unknown> }).default,
    en: ((await import('./locales/en.json')) as { default: Record<string, unknown> }).default,
  }),
}

export default manifest

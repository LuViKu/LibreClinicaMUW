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
 */
import type { StudyModuleManifest } from '../types'

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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'

/**
 * Spec for {@code useStudyModuleStore}.
 *
 * Strategy: mock {@code @/i18n} so we can assert that activation
 * calls {@code mergeLocaleMessage('de', payload.de)} +
 * {@code mergeLocaleMessage('en', payload.en)} exactly once per
 * module-id, without booting the full vue-i18n instance.
 *
 * The registry is mutated in-place via push/splice rather than via
 * {@code vi.mock} so the store exercises the live findModule()
 * helper. Auth state is driven by writing the fixture user directly
 * onto the auth store — the store derives activeModule from
 * {@code auth.user?.activeStudy?.protocolType} so the watcher fires
 * on each assignment.
 */

const mergeLocaleMessage = vi.fn()
vi.mock('@/i18n', () => ({
  i18n: {
    global: {
      mergeLocaleMessage,
    },
  },
}))

import { STUDY_MODULES } from '@/studyModules/registry'
import type { StudyModuleManifest } from '@/studyModules/types'
import { useStudyModuleStore, useActiveStudyModule } from '../studyModules'
import { useAuthStore } from '../auth'
import type { AuthenticatedUser } from '@/types/auth'

function namdManifest(opts: { loadI18n?: () => Promise<{ de: Record<string, unknown>; en: Record<string, unknown> }> } = {}): StudyModuleManifest {
  return {
    protocolType: 'NAMD',
    labelKey: 'studyModules.namd.label',
    routes: [
      {
        path: '',
        name: 'namd-workspace',
        component: defineComponent({ template: '<div />' }),
      },
    ],
    injections: {
      'subject-detail.workspace': [
        {
          key: 'open-workspace',
          labelKey: 'studyModules.namd.open',
          component: defineComponent({ template: '<div />' }),
        },
      ],
    },
    loadI18n: opts.loadI18n,
  }
}

function userWithProtocol(pt: string | null): AuthenticatedUser {
  return {
    username: 'root',
    displayName: 'Root',
    email: null,
    role: 'Investigator',
    roles: ['Investigator'],
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    locale: 'de-AT',
    timezone: 'Europe/Vienna',
    mustChangePassword: false,
    passwordChangeReason: null,
    activeStudy: pt === null
      ? null
      : { id: 1, oid: 'S_DEFAULTS1', name: 'Default', uniqueIdentifier: 'D', isSite: false, roles: ['Investigator'], protocolType: pt },
  }
}

describe('useStudyModuleStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mergeLocaleMessage.mockReset()
    STUDY_MODULES.splice(0, STUDY_MODULES.length)
  })

  afterEach(() => {
    STUDY_MODULES.splice(0, STUDY_MODULES.length)
  })

  it('activeModule is null when the registry is empty', () => {
    const auth = useAuthStore()
    auth.user = userWithProtocol('NAMD')
    auth.state = 'authenticated'

    const store = useStudyModuleStore()
    expect(store.activeModule).toBeNull()
  })

  it('activeModule is null when the user has no active study', () => {
    STUDY_MODULES.push(namdManifest())
    const auth = useAuthStore()
    auth.user = userWithProtocol(null)
    auth.state = 'authenticated'

    const store = useStudyModuleStore()
    expect(store.activeModule).toBeNull()
  })

  it('resolves the manifest when protocolType matches (case-insensitive)', () => {
    const namd = namdManifest()
    STUDY_MODULES.push(namd)

    const auth = useAuthStore()
    auth.user = userWithProtocol('nAMD')
    auth.state = 'authenticated'

    const store = useStudyModuleStore()
    expect(store.activeModule).toBe(namd)
  })

  it('injectionsFor() returns the active module entries; empty array otherwise', () => {
    const namd = namdManifest()
    STUDY_MODULES.push(namd)
    const auth = useAuthStore()
    auth.user = userWithProtocol('NAMD')
    auth.state = 'authenticated'

    const store = useStudyModuleStore()
    expect(store.injectionsFor('subject-detail.workspace')).toHaveLength(1)
    expect(store.injectionsFor('event-detail.panels')).toEqual([])
  })

  it('merges i18n on first activation and remembers the load', async () => {
    const dePayload = { namd: { workspace: 'Arbeitsbereich' } }
    const enPayload = { namd: { workspace: 'Workspace' } }
    const loadI18n = vi.fn().mockResolvedValue({ de: dePayload, en: enPayload })
    STUDY_MODULES.push(namdManifest({ loadI18n }))

    const auth = useAuthStore()
    const store = useStudyModuleStore()
    // Watcher fires immediately:true — drive activation by assigning
    // the authenticated user after the store has been instantiated so
    // we observe the activation transition rather than the bootstrap
    // no-op.
    auth.user = userWithProtocol('NAMD')
    auth.state = 'authenticated'
    await nextTick()
    // Wait one microtask for the async loadI18n() to resolve.
    await loadI18n.mock.results[0]?.value

    expect(loadI18n).toHaveBeenCalledTimes(1)
    expect(mergeLocaleMessage).toHaveBeenCalledWith('de', dePayload)
    expect(mergeLocaleMessage).toHaveBeenCalledWith('en', enPayload)
    expect(store.loadedModuleIds.has('NAMD')).toBe(true)
  })

  it('does NOT re-merge i18n when the same module activates a second time', async () => {
    const loadI18n = vi.fn().mockResolvedValue({ de: {}, en: {} })
    STUDY_MODULES.push(namdManifest({ loadI18n }))

    const auth = useAuthStore()
    useStudyModuleStore()
    auth.user = userWithProtocol('NAMD')
    auth.state = 'authenticated'
    await nextTick()
    await loadI18n.mock.results[0]?.value

    expect(loadI18n).toHaveBeenCalledTimes(1)

    // Switch away and back; loadI18n must NOT fire again because the
    // store caches the load per module id.
    auth.user = userWithProtocol(null)
    await nextTick()
    auth.user = userWithProtocol('NAMD')
    await nextTick()

    expect(loadI18n).toHaveBeenCalledTimes(1)
  })

  it('useActiveStudyModule() returns a reactive ref to the manifest', () => {
    const namd = namdManifest()
    STUDY_MODULES.push(namd)
    const auth = useAuthStore()
    auth.user = userWithProtocol('NAMD')
    auth.state = 'authenticated'

    const { activeModule } = useActiveStudyModule()
    expect(activeModule.value).toBe(namd)
  })
})

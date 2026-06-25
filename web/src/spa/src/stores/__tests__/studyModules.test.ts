import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'

/** Run every queued microtask + Vue tick until the watcher chain settles. */
async function flushAll(): Promise<void> {
  // Two nextTicks + a macrotask drain are enough for: watcher-fire →
  // loadI18n() Promise resolve → mergeLocaleMessage calls → state update.
  await nextTick()
  await Promise.resolve()
  await Promise.resolve()
  await nextTick()
}

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

// vi.mock factories are hoisted to the top of the file, so any
// references they capture must be created via vi.hoisted (also hoisted)
// rather than as plain top-level consts.
const { mergeLocaleMessage, getLocaleMessage } = vi.hoisted(() => ({
  mergeLocaleMessage: vi.fn(),
  // Returning an empty object models "first module loading" — the
  // collision detector (fix #7) walks the result against the
  // incoming payload; a non-overlapping baseline produces zero
  // warnings, which matches the existing happy-path tests.
  getLocaleMessage: vi.fn(() => ({})),
}))
vi.mock('@/i18n', () => ({
  i18n: {
    global: {
      mergeLocaleMessage,
      getLocaleMessage,
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

/**
 * 2026-06-23 — activation now keys on activeStudy.enabledModules
 * (the admin-toggled enrollment), not on study.protocol_type. The
 * helper name + signature are retained so existing tests keep
 * reading as "give me a user whose active study has module X
 * enrolled"; the value is now propagated into enabledModules.
 */
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
      : {
          id: 1,
          oid: 'S_DEFAULTS1',
          name: 'Default',
          uniqueIdentifier: 'D',
          isSite: false,
          roles: ['Investigator'],
          protocolType: 'observational',
          enabledModules: [pt],
        } as AuthenticatedUser['activeStudy'] & { enabledModules: string[] },
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
    await flushAll()

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
    await flushAll()

    expect(loadI18n).toHaveBeenCalledTimes(1)

    // Switch away and back; loadI18n must NOT fire again because the
    // store caches the load per module id.
    auth.user = userWithProtocol(null)
    await flushAll()
    auth.user = userWithProtocol('NAMD')
    await flushAll()

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

/* ---------------------------------------------------------------- */
/* PR #245 hardening — fix #7 — i18n collision detector             */
/* ---------------------------------------------------------------- */

describe('detectI18nCollisions()', () => {
  it('logs nothing when keys do not overlap', async () => {
    const { detectI18nCollisions } = await import('../studyModules')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    detectI18nCollisions(
      'NAMD',
      'de',
      { studyModules: { other: { label: 'X' } } },
      { studyModules: { namd: { label: 'Y' } } },
    )
    expect(warn).not.toHaveBeenCalled()
    warn.mockRestore()
  })

  it('logs once per overlapping leaf key with the locale + dotted path', async () => {
    const { detectI18nCollisions } = await import('../studyModules')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    detectI18nCollisions(
      'NAMD',
      'de',
      { studyModules: { namd: { label: 'A', other: 'keep' } } },
      { studyModules: { namd: { label: 'B' } } },
    )
    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn.mock.calls[0][0]).toContain('de.studyModules.namd.label')
    expect(warn.mock.calls[0][0]).toContain('NAMD')
    warn.mockRestore()
  })

  it('stays silent when the incoming value matches the existing one (same string)', async () => {
    const { detectI18nCollisions } = await import('../studyModules')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    detectI18nCollisions(
      'NAMD',
      'de',
      { foo: 'X' },
      { foo: 'X' },
    )
    expect(warn).not.toHaveBeenCalled()
    warn.mockRestore()
  })

  it('is a no-op when the existing tree is null/undefined (first module loading)', async () => {
    const { detectI18nCollisions } = await import('../studyModules')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    detectI18nCollisions('NAMD', 'de', null, { studyModules: { namd: { label: 'A' } } })
    detectI18nCollisions('NAMD', 'de', undefined, { studyModules: { namd: { label: 'A' } } })
    expect(warn).not.toHaveBeenCalled()
    warn.mockRestore()
  })
})

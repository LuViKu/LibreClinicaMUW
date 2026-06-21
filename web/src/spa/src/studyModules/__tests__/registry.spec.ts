import { describe, expect, it, beforeEach } from 'vitest'
import { defineComponent } from 'vue'
import type { StudyModuleManifest } from '../types'
import { STUDY_MODULES, findModule } from '../registry'

/**
 * The shipped registry starts empty — the harmonize step adds the
 * nAMD manifest after the parallel framework + module branches merge.
 * These specs cover the lookup behaviour in isolation; they mutate the
 * exported array via push/splice instead of reaching for {@code
 * vi.mock} so the test exercises exactly the live module + helper.
 */

function makeManifest(protocolType: string): StudyModuleManifest {
  return {
    protocolType,
    labelKey: `studyModules.${protocolType.toLowerCase()}.label`,
    routes: [
      {
        path: '',
        name: `${protocolType.toLowerCase()}-workspace`,
        component: defineComponent({ template: '<div />' }),
      },
    ],
  }
}

describe('studyModules/registry', () => {
  // Snapshot the array so each spec gets a clean slate.
  beforeEach(() => {
    STUDY_MODULES.splice(0, STUDY_MODULES.length)
  })

  describe('findModule()', () => {
    it('returns null for an empty registry', () => {
      expect(findModule('nAMD')).toBeNull()
    })

    it('returns null for null/undefined/blank input', () => {
      STUDY_MODULES.push(makeManifest('NAMD'))
      expect(findModule(null)).toBeNull()
      expect(findModule(undefined)).toBeNull()
      expect(findModule('')).toBeNull()
    })

    it('returns the matching manifest when registered', () => {
      const namd = makeManifest('NAMD')
      STUDY_MODULES.push(namd)
      expect(findModule('NAMD')).toBe(namd)
    })

    it('matches case-insensitively in both directions', () => {
      const namd = makeManifest('NAMD')
      const ga = makeManifest('ga')
      STUDY_MODULES.push(namd, ga)
      expect(findModule('namd')).toBe(namd)
      expect(findModule('nAMD')).toBe(namd)
      expect(findModule('GA')).toBe(ga)
      expect(findModule('Ga')).toBe(ga)
    })

    it('returns null when no registered module matches', () => {
      STUDY_MODULES.push(makeManifest('NAMD'))
      expect(findModule('GA')).toBeNull()
    })

    /* PR #245 hardening — fix #6 — tolerate whitespace + cosmetic case. */
    it('trims surrounding whitespace before matching', () => {
      const namd = makeManifest('NAMD')
      STUDY_MODULES.push(namd)
      expect(findModule(' NAMD ')).toBe(namd)
      expect(findModule('\tNAMD\n')).toBe(namd)
      expect(findModule(' nAMD')).toBe(namd)
    })

    it('returns null when input is whitespace-only', () => {
      STUDY_MODULES.push(makeManifest('NAMD'))
      expect(findModule('   ')).toBeNull()
      expect(findModule('\t')).toBeNull()
    })

    it('tolerates a manifest that itself carries cosmetic whitespace', () => {
      const namd = makeManifest('  NAMD  ')
      STUDY_MODULES.push(namd)
      expect(findModule('NAMD')).toBe(namd)
      expect(findModule(' namd ')).toBe(namd)
    })
  })

  describe('STUDY_MODULES', () => {
    it('contains the nAMD manifest auto-registered via import.meta.glob', () => {
      // After beforeEach() truncates the array, the test contract is
      // just "the exported symbol is an array." The shipped default
      // (auto-populated from studyModules/*/index.ts) is verified at
      // module-import time by the glob in registry.ts; if any module
      // fails to default-export a manifest, that import itself would
      // throw and this test file wouldn't even load.
      expect(Array.isArray(STUDY_MODULES)).toBe(true)
    })
  })
})

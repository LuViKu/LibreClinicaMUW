import type { StudyModuleManifest } from './types'

/**
 * Registered study modules. The harmonize step adds the nAMD import
 * after the two parallel branches merge — for now this array stays
 * empty so the framework can be tested in isolation.
 */
export const STUDY_MODULES: StudyModuleManifest[] = []

/**
 * Look up a manifest by {@code study.protocol_type}. Match is
 * case-insensitive (the DB column is free-form text; cosmetic case
 * is not part of the identity). Returns {@code null} when the value
 * is null/blank or no registered module advertises that protocol type.
 */
export function findModule(protocolType: string | null | undefined): StudyModuleManifest | null {
  if (!protocolType) return null
  const needle = protocolType.toUpperCase()
  return STUDY_MODULES.find((m) => m.protocolType.toUpperCase() === needle) ?? null
}

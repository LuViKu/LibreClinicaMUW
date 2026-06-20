import type { StudyModuleManifest } from './types'
import namdManifest from './nAMD'

/**
 * Registered study modules. Order is presentation order in nav surfaces
 * (the framework currently has no nav slot consumer, but the order
 * matters for {@code injectionsFor} stability).
 */
export const STUDY_MODULES: StudyModuleManifest[] = [namdManifest]

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

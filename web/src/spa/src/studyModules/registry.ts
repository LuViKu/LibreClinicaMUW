import type { StudyModuleManifest } from './types'

/**
 * Convention-based study-module registry.
 *
 * Every sibling directory under {@code studyModules/} with an
 * {@code index.ts} that default-exports a {@link StudyModuleManifest}
 * is auto-registered at boot. Adding a new module is therefore a pure
 * additive change — no shared-code edit, no merge conflict against
 * parallel module work.
 *
 * The glob is {@code eager: true} so {@link STUDY_MODULES} is fully
 * populated by the time {@code main.ts} iterates it to call
 * {@code router.addRoute(...)}.
 */
const modules = import.meta.glob<{ default: StudyModuleManifest }>(
  './*/index.ts',
  { eager: true },
)

export const STUDY_MODULES: StudyModuleManifest[] = Object.values(modules).map(
  (m) => m.default,
)

/*
 * Boot-time uniqueness assertion. Two modules sharing the same
 * (normalised) protocolType is a copy-paste typo whose silent failure
 * mode is "second module never matches" — surface it loudly so the
 * author catches it on first save instead of after a release. Match
 * semantics mirror {@link findModule} below.
 */
{
  const seen = new Map<string, string>()
  for (const [path, m] of Object.entries(modules)) {
    const key = m.default.protocolType.trim().toUpperCase()
    if (seen.has(key)) {
      // eslint-disable-next-line no-console
      console.warn(
        `[studyModules] Duplicate protocolType "${m.default.protocolType}" — ` +
          `${path} collides with ${seen.get(key)}. ` +
          `findModule() will only return the first match (registration order).`,
      )
    } else {
      seen.set(key, path)
    }
  }
}

/**
 * Look up a manifest by {@code study.protocol_type}.
 *
 * <p>Match is case-insensitive AND whitespace-tolerant — the DB column
 * is free-form text; cosmetic case + a trailing space are not part of
 * the identity. Returns {@code null} when the value is null, blank,
 * whitespace-only, or no registered module advertises that protocol
 * type.
 */
export function findModule(
  protocolType: string | null | undefined,
): StudyModuleManifest | null {
  if (!protocolType) return null
  const needle = protocolType.trim().toUpperCase()
  if (!needle) return null
  return (
    STUDY_MODULES.find((m) => m.protocolType.trim().toUpperCase() === needle) ??
    null
  )
}

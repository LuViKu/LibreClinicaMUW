/**
 * nAMD treat-and-extend Slice 6 (2026-06-20) — small composable
 * helpers for the honour-system AI panel gate.
 *
 * The arm itself is computed server-side and embedded on
 * `RetinalJobDetail.subjectArm`; this composable just normalises
 * the cases the views care about so v-if checks stay readable
 * ("is the AI hidden for this subject?" vs threading an enum
 * comparison through every template).
 *
 * Keep this UI-only: any data-layer decision (which fields to
 * fetch, which artifacts to serve) belongs upstream on the
 * backend, where the audit trail can prove the data the SPA
 * session did or didn't see.
 */
import { computed, type ComputedRef } from 'vue'

export type StudyArm = 'AI_SHOWN' | 'AI_HIDDEN' | null

export interface ArmGate {
  /** Raw arm value (null when the subject isn't in either arm). */
  arm: ComputedRef<StudyArm>
  hideAi: ComputedRef<boolean>
  /** True when the badge component should render in place of the panel. */
  showBadge: ComputedRef<boolean>
}

export function useStudyArm(source: ComputedRef<StudyArm> | (() => StudyArm)): ArmGate {
  const armRef: ComputedRef<StudyArm> = computed(() =>
    typeof source === 'function' ? source() : source.value,
  )
  const hideAi = computed<boolean>(() => armRef.value === 'AI_HIDDEN')
  const showBadge = computed<boolean>(() => armRef.value === 'AI_HIDDEN')
  return { arm: armRef, hideAi, showBadge }
}

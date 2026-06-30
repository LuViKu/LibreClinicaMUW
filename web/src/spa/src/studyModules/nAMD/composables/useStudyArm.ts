/**
 * nAMD workspace — cohort/arm gate.
 *
 * Wraps the {@code subjectArm} field on {@link NamdWorkspaceData} —
 * itself sourced from the backend's
 * {@code RetinalResultsApiController.resolveSubjectArm} that joins
 * {@code event_crf → study_event → subject_group_map (active) → study_group}
 * looking for a group whose name matches {@code "AI_SHOWN"} or
 * {@code "AI_HIDDEN"}. Components import {@code aiVisible} as the
 * single source of truth for "should the AI panel render".
 *
 * <p>Defensive fallback: when the subject isn't in either arm (the
 * group_class hasn't been assigned yet on a fresh subject) the
 * composable returns {@code isStudyArm=false} + {@code aiVisible=false}.
 * The workspace gracefully degrades to the control-arm presentation
 * — operator can still capture the decision; no AI panels surface
 * until enrollment lands.
 *
 * <p>Reactive: the underlying ref drives the v-if gate; switching arms
 * via the study-admin UI flows through the {@link useNamdVisitData}
 * refetch, which lands a new {@code subjectArm} value and tears down
 * the AI panels in one tick.
 */

import { computed, type ComputedRef } from 'vue'
import type { NamdSubjectArm } from '../types'

export interface UseStudyArmResult {
  isStudyArm: ComputedRef<boolean>
  isControlArm: ComputedRef<boolean>
  /**
   * Single boolean every AI-panel gate consumes. True iff the subject
   * is in the study arm. False on control-arm + on the defensive
   * unassigned fallback.
   */
  aiVisible: ComputedRef<boolean>
}

export function useStudyArm(arm: { value: NamdSubjectArm } | ComputedRef<NamdSubjectArm>): UseStudyArmResult {
  const isStudyArm = computed(() => arm.value === 'study')
  const isControlArm = computed(() => arm.value === 'control')
  const aiVisible = computed(() => arm.value === 'study')
  return { isStudyArm, isControlArm, aiVisible }
}

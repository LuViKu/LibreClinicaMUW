/**
 * P2-7 — the trial-blinding gate, as a truth table.
 *
 * A subject in the control arm must not see AI-derived numbers, because the
 * study is measuring whether seeing them changes what clinicians do. Showing
 * them to the wrong arm does not produce a wrong value; it invalidates the
 * result the study exists to produce, and nothing on the screen would say so.
 *
 * The server strips the payload for a control-arm subject regardless of what
 * the client asks for — that is the enforcement. This composable is what keeps
 * the panels from rendering an empty shell, and it must never answer "show" for
 * anything other than the study arm, including for a subject whose arm has not
 * been assigned yet.
 */
import { describe, it, expect } from 'vitest'
import { computed, ref } from 'vue'
import { useStudyArm } from '../useStudyArm'
import type { NamdSubjectArm } from '../../types'

function armOf(value: NamdSubjectArm) {
  return useStudyArm(computed(() => value))
}

describe('useStudyArm', () => {
  it('shows AI panels for the study arm', () => {
    const { isStudyArm, isControlArm, aiVisible } = armOf('study')
    expect(isStudyArm.value).toBe(true)
    expect(isControlArm.value).toBe(false)
    expect(aiVisible.value).toBe(true)
  })

  it('hides AI panels for the control arm', () => {
    const { isStudyArm, isControlArm, aiVisible } = armOf('control')
    expect(isStudyArm.value).toBe(false)
    expect(isControlArm.value).toBe(true)
    expect(aiVisible.value).toBe(false)
  })

  /**
   * An unassigned subject is the dangerous case: a fresh enrolment has no
   * group yet, and defaulting to "show" would unblind them silently.
   */
  it('hides AI panels when no arm has been assigned', () => {
    const { isStudyArm, isControlArm, aiVisible } = armOf(null)
    expect(isStudyArm.value).toBe(false)
    expect(isControlArm.value).toBe(false)
    expect(aiVisible.value).toBe(false)
  })

  it('tears the panels down when the arm changes under it', () => {
    const arm = ref<NamdSubjectArm>('study')
    const { aiVisible } = useStudyArm(arm)
    expect(aiVisible.value).toBe(true)

    arm.value = 'control'
    expect(aiVisible.value).toBe(false)

    arm.value = null
    expect(aiVisible.value).toBe(false)
  })

  /** aiVisible is the single gate: it must never disagree with isStudyArm. */
  it('never shows AI outside the study arm', () => {
    for (const value of ['study', 'control', null] as NamdSubjectArm[]) {
      const { isStudyArm, aiVisible } = armOf(value)
      expect(aiVisible.value).toBe(isStudyArm.value)
    }
  })
})

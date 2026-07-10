/**
 * 2026-06-30 — Rationale-required matrix for the nAMD decision
 * panel. Validates the conditional-render rules for the rationale
 * block:
 *
 * | arm     | aiRec        | doctor agrees | rationale required? |
 * | ------- | ------------ | ------------- | ------------------- |
 * | study   | null         | n/a           | hidden              |
 * | study   | TREAT/8wk    | yes (TREAT/8) | hidden              |
 * | study   | TREAT/8wk    | no (TREAT/4)  | required            |
 * | control | n/a          | n/a           | required            |
 * | null    | n/a          | n/a           | required (defensive)|
 *
 * Also checks: picking OTHER reveals the free-text input.
 */
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, it, expect, beforeEach } from 'vitest'
import NamdDecisionPanel from '../NamdDecisionPanel.vue'
import type { NamdAiRecommendation, NamdSubjectArm } from '../../types'

const i18n = createI18n({
  legacy: false,
  locale: 'de',
  messages: { de: {}, en: {} },
  missingWarn: false,
  fallbackWarn: false,
})

function makeRec(rec: NamdAiRecommendation['rec'] = 'EXTEND', weeks = 8): NamdAiRecommendation {
  return {
    rec,
    intervalWeeks: weeks,
    rationale: 'stub',
    triggersFired: [],
  }
}

function mountPanel(props: {
  eventCrfId?: number | null
  // `subjectArm` is intentionally NOT defaulted via `??` — the
  // unassigned-arm test needs to pass null through. Use a sentinel
  // marker key to distinguish "omitted" from "explicitly null".
  subjectArm?: NamdSubjectArm
  aiRec?: NamdAiRecommendation | null
}) {
  return mount(NamdDecisionPanel, {
    global: { plugins: [i18n] },
    props: {
      eventCrfId: props.eventCrfId ?? 9001,
      subjectArm: 'subjectArm' in props ? props.subjectArm! : 'study',
      aiRec: props.aiRec ?? null,
    },
  })
}

beforeEach(() => {
  // Stub fetch — the panel POSTs on confirm; the visibility tests
  // don't reach that path but keep the global clean.
  globalThis.fetch = (async () => new Response('{}', { status: 200 })) as typeof fetch
})

describe('NamdDecisionPanel — rationale-required matrix', () => {
  it('study arm + no AI rec → rationale hidden', () => {
    const w = mountPanel({ subjectArm: 'study', aiRec: null })
    expect(w.find('[data-testid="namd-decision-rationale-block"]').exists()).toBe(false)
  })

  it('study arm + agrees with AI rec → rationale hidden', async () => {
    const w = mountPanel({ subjectArm: 'study', aiRec: makeRec('EXTEND', 8) })
    await w.find('[data-testid="namd-decision-action-TREAT"]').trigger('click')
    await w.find('[data-testid="namd-decision-interval-8"]').trigger('click')
    expect(w.find('[data-testid="namd-decision-rationale-block"]').exists()).toBe(false)
  })

  it('study arm + disagrees with AI rec → rationale required', async () => {
    const w = mountPanel({ subjectArm: 'study', aiRec: makeRec('EXTEND', 12) })
    await w.find('[data-testid="namd-decision-action-TREAT"]').trigger('click')
    await w.find('[data-testid="namd-decision-interval-4"]').trigger('click')
    expect(w.find('[data-testid="namd-decision-rationale-block"]').exists()).toBe(true)
  })

  it('control arm → rationale always required', () => {
    const w = mountPanel({ subjectArm: 'control', aiRec: null })
    expect(w.find('[data-testid="namd-decision-rationale-block"]').exists()).toBe(true)
  })

  it('unassigned arm (null) → rationale required (defensive)', () => {
    const w = mountPanel({ subjectArm: null, aiRec: null })
    expect(w.find('[data-testid="namd-decision-rationale-block"]').exists()).toBe(true)
  })
})

describe('NamdDecisionPanel — preset filtering', () => {
  it('study arm shows the override preset (CLINICAL_JUDGMENT first option)', async () => {
    const w = mountPanel({ subjectArm: 'study', aiRec: makeRec('EXTEND', 12) })
    await w.find('[data-testid="namd-decision-action-TREAT"]').trigger('click')
    await w.find('[data-testid="namd-decision-interval-4"]').trigger('click')
    expect(w.find('[data-testid="namd-decision-rationale-CLINICAL_JUDGMENT"]').exists()).toBe(true)
    // Control-arm-specific code does NOT appear.
    expect(w.find('[data-testid="namd-decision-rationale-STABLE_NO_TREAT"]').exists()).toBe(false)
  })

  it('control arm shows the control preset (STABLE_NO_TREAT first option)', () => {
    const w = mountPanel({ subjectArm: 'control', aiRec: null })
    expect(w.find('[data-testid="namd-decision-rationale-STABLE_NO_TREAT"]').exists()).toBe(true)
    expect(w.find('[data-testid="namd-decision-rationale-CLINICAL_JUDGMENT"]').exists()).toBe(false)
  })
})

describe('NamdDecisionPanel — OTHER reveals free-text', () => {
  it('OTHER selection reveals the rationaleOther textarea', async () => {
    const w = mountPanel({ subjectArm: 'control', aiRec: null })
    expect(w.find('[data-testid="namd-decision-rationale-other"]').exists()).toBe(false)
    await w.find('[data-testid="namd-decision-rationale-OTHER"] input').setValue('OTHER')
    expect(w.find('[data-testid="namd-decision-rationale-other"]').exists()).toBe(true)
  })
})

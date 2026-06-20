/**
 * nAMD workspace — AI recommendation derivation.
 *
 * Derives the {@code AI.rec / .intervalWeeks / .rationale} fields used
 * by {@link NamdDecisionPanel} from the current-vs-previous fluid delta.
 * v1 lives client-side; the rule set is intentionally simple so the
 * physician sees an obvious recommendation, NOT an opaque "the AI said
 * so" black box.
 *
 * <p>Rules (mirrors the design's static {@code AI} constant):
 *   - First visit (no {@code prev}) → "TREAT" at 4 weeks ("Loading-Phase").
 *   - Rising fluid (current > prev) → "TREAT" + shorten interval by 2.
 *   - Steady fluid (|delta| < 5 nL) → "EXTEND" + lengthen interval by 2.
 *   - Falling fluid (current < prev) → "EXTEND" + lengthen by 2 (cap 16).
 *   - Dry baseline + no prior activity → "OBSERVE" at 12 weeks.
 *
 * Once clinical sign-off lands on the production rule set, this hook
 * moves to the backend ({@code RetinalResultsApiController}). The
 * manifest's {@code visitScheduler} extension point is the planned
 * landing pad for the backend-derived hint.
 */

import { computed, type ComputedRef } from 'vue'
import type { NamdAiRecommendation, NamdVisit } from '../types'
import { activeFluid, ACTIVITY_THRESHOLD_NL } from '../fluid'

/** Δ-magnitude (nL) under which fluid is considered "steady". */
const STEADY_THRESHOLD_NL = 5
/** Default interval shift when extending or shortening (weeks). */
const SHIFT_WEEKS = 2
/** Hard cap on the extension ladder (weeks). */
const MAX_EXTEND_WEEKS = 16
/** Loading-phase fallback interval (weeks). */
const LOADING_INTERVAL_WEEKS = 4
/** Observation interval for dry, stable patients (weeks). */
const OBSERVE_INTERVAL_WEEKS = 12

export function useNamdAiRecommendation(args: {
  current: ComputedRef<NamdVisit | null>
  prev: ComputedRef<NamdVisit | null>
}): ComputedRef<NamdAiRecommendation | null> {
  const { current, prev } = args
  return computed<NamdAiRecommendation | null>(() => {
    if (!current.value) return null
    const cur = current.value
    const baseInterval = prev.value?.interval ?? LOADING_INTERVAL_WEEKS

    // First visit — straight to loading-phase Treat.
    if (!prev.value) {
      return {
        rec: 'TREAT',
        intervalWeeks: LOADING_INTERVAL_WEEKS,
        rationale: 'Erstvisite — Loading-Phase mit monatlicher Injektion.',
      }
    }

    const delta = activeFluid(cur) - activeFluid(prev.value)
    const dry = activeFluid(cur) <= ACTIVITY_THRESHOLD_NL
    const wasDry = activeFluid(prev.value) <= ACTIVITY_THRESHOLD_NL

    if (delta > STEADY_THRESHOLD_NL) {
      const next = Math.max(baseInterval - SHIFT_WEEKS, LOADING_INTERVAL_WEEKS)
      return {
        rec: 'SHORTEN',
        intervalWeeks: next,
        rationale: `Anstieg der Gesamtflüssigkeit um ${Math.round(delta)} nL — Intervall verkürzen.`,
      }
    }
    if (Math.abs(delta) <= STEADY_THRESHOLD_NL) {
      const next = Math.min(baseInterval + SHIFT_WEEKS, MAX_EXTEND_WEEKS)
      if (dry && wasDry) {
        return {
          rec: 'OBSERVE',
          intervalWeeks: OBSERVE_INTERVAL_WEEKS,
          rationale: 'Stabil trocken über zwei Visiten — Beobachtungsintervall möglich.',
        }
      }
      return {
        rec: 'EXTEND',
        intervalWeeks: next,
        rationale: 'Stabile Befundlage — Intervall verlängern.',
      }
    }
    // Falling fluid → continue extending.
    const next = Math.min(baseInterval + SHIFT_WEEKS, MAX_EXTEND_WEEKS)
    return {
      rec: 'EXTEND',
      intervalWeeks: next,
      rationale: `Rückgang der Gesamtflüssigkeit um ${Math.abs(Math.round(delta))} nL — Intervall verlängern.`,
    }
  })
}

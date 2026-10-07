/**
 * nAMD workspace — AI recommendation derivation.
 *
 * Derives the {@link NamdAiRecommendation} surfaced on the Overview
 * tab from the current vs. previous visit's measured biomarkers + the
 * per-eye clinical flags (hemorrhage / BCVA-loss attribution) + the
 * cross-visit reference + nadir-SRF-1-3 tracking. Implements the
 * protocol's interval-shortening / -keeping / -extending rule set
 * (see {@code /Users/lukas/.claude/plans/robust-jumping-eich.md}).
 *
 * <h2>Trigger taxonomy</h2>
 *
 * <ul>
 *   <li><b>SHORTEN</b> (8 triggers, any one wins):
 *     {@code DE_NOVO_IRF}, {@code IRF_INCREASE},
 *     {@code IRF_DECREASE_INSUFFICIENT},
 *     {@code DE_NOVO_CENTRAL_SRF}, {@code CENTRAL_SRF_INCREASE},
 *     {@code SRF_RING_1_3_INCREASE}, {@code NEW_HEMORRHAGE},
 *     {@code BCVA_LOSS_5_LETTERS}.</li>
 *   <li><b>KEEP</b> (4 triggers; surface when SHORTEN doesn't fire
 *     but residual activity is present):
 *     {@code RESIDUAL_IRF_HALVED}, {@code RESIDUAL_IRF_STABLE},
 *     {@code CENTRAL_SRF_IMPROVING}, {@code ACTIVITY_IMPROVING}.</li>
 *   <li><b>EXTEND</b> (4 eligibility conditions; all 4 must hold):
 *     {@code IRF_ABSENT}, {@code CENTRAL_SRF_ABSENT},
 *     {@code NO_HEMORRHAGE_OR_BCVA_LOSS},
 *     {@code SRF_ISOLATED_1_3_STABLE}.</li>
 * </ul>
 *
 * <p>Precedence: any SHORTEN trigger fires → {@code rec=SHORTEN}.
 * Otherwise, if any KEEP condition holds → {@code rec=KEEP}.
 * Otherwise, if all 4 EXTEND conditions hold → {@code rec=EXTEND}.
 * The full {@code triggersFired[]} is always populated so the UI can
 * explain the rec to the operator.
 *
 * <p>Thresholds at the top of the file are tunable; they were chosen
 * pragmatically and should be revisited with the clinical lead
 * before production cut-over.
 *
 * <h2>Fail-closed on unknown data (2026-10)</h2>
 *
 * <p>Every input is either a measured value or {@code null} = unknown (never
 * recorded, or its fetch failed). Unknown is NOT zero and NOT "no disease":
 * the engine used to read it that way, so a visit with no biomarkers, no BCVA
 * or no flags could be told to EXTEND. Now:
 *
 * <ul>
 *   <li>A SHORTEN trigger that is positively established from KNOWN data still
 *     fires even when other inputs are missing. Conservative by construction:
 *     SHORTEN is the safe direction, and a known worsening is not cancelled by
 *     an unrelated gap. Each trigger is only evaluated on the inputs it needs.</li>
 *   <li>Otherwise, if ANY input the rules need is unknown, the result is
 *     {@code rec: null, reason: 'INSUFFICIENT_DATA', missing: [...]}. KEEP and
 *     EXTEND are never reachable from unknown inputs (KEEP is a "no worsening
 *     found" verdict and is as unsafe on unknown data as EXTEND).</li>
 *   <li>A visit whose job came from a placeholder model (fake deterministic
 *     volumes) never yields a recommendation, SHORTEN included.</li>
 * </ul>
 *
 * <p>First visit (no {@code prev}): the rule engine has nothing to
 * compare against. Returns null — the Overview tab falls through to a
 * "Loading-Phase — monatliche Injektion" copy block instead of a rec
 * card.
 */

// Rule specification, thresholds in both units, and the sign-off record:
// docs/development/study-modules/namd-treat-and-extend-rules.md
import { computed, type ComputedRef } from 'vue'
import type { NamdAiRecommendation, NamdFetchFailure, NamdMissingInput, NamdTriggerHit, NamdVisit } from '../types'

// ─── Tunable thresholds ────────────────────────────────────────────
//
// 2026-09-18 — every threshold below was multiplied by ten when the mm³ → nL
// conversion in useNamdVisitData was corrected (it multiplied by 100 instead
// of 1000). The two changes cancel: the volumes these fire at are exactly the
// volumes they fired at before, so no recommendation changes. What changed is
// that the numbers are now stated in real nanolitres.
//
// These are therefore the values the rules have always used, not a clinical
// decision. The literal figures in the rule specification are ten times
// smaller. Lowering them to the specification is a clinical call for the study
// lead; when that happens, bump NAMD_THRESHOLDS_VERSION and record the
// decision in the rules document, because every stored recommendation has to
// stay interpretable against the thresholds that produced it.
//
// See docs/development/study-modules/namd-treat-and-extend-rules.md.

/**
 * Identifies the threshold set a recommendation was produced under. Shown on
 * the recommendation card and written into the decision audit, so a decision
 * made last year can still be read against the rules of last year.
 */
export const NAMD_THRESHOLDS_VERSION = 'v1-2026-09'

/** SHORTEN: total IRF increase (nL) above this is "above threshold". */
export const IRF_INCREASE_NL = 200
/** SHORTEN: central-1mm SRF increase (nL) above this is "above strict threshold". */
export const CENTRAL_SRF_STRICT_INCREASE_NL = 100
/** SHORTEN: SRF in the 1–3 mm ring rises by ≥ this many nL vs prev OR cumulatively vs reference/nadir. */
export const SRF_RING_1_3_INCREASE_NL = 100
/** SHORTEN: IRF dropped vs prev but by < this fraction (i.e. < 50 %). */
export const IRF_DECREASE_SUFFICIENT_PCT = 0.5
/** SHORTEN: ≥ this BCVA-letters drop vs prev (combined with the attribution flag) triggers BCVA_LOSS. */
export const BCVA_LOSS_LETTERS = 5
/** KEEP / EXTEND: max activity (nL) considered "absent" — covers measurement noise around 0. */
export const ABSENT_NL = 10
/** Default interval shift when shortening / extending (weeks). */
export const SHIFT_WEEKS = 2
/** Loading-phase interval (weeks). */
export const LOADING_INTERVAL_WEEKS = 4
/** Hard cap on the extension ladder (weeks). */
export const MAX_EXTEND_WEEKS = 16

// ─── Helpers ───────────────────────────────────────────────────────

/** Central-1mm SRF (nL), or null when the per-ring breakdown is unknown. */
function central1mmSrf(v: NamdVisit): number | null {
  return v.fluidByRegion?.c1?.srf ?? null
}
/** SRF inside the 1–3 mm ring annulus (central_3mm − central_1mm), or null when unknown. */
function ring1to3Srf(v: NamdVisit): number | null {
  if (!v.fluidByRegion) return null
  return Math.max(0, v.fluidByRegion.c3.srf - v.fluidByRegion.c1.srf)
}

/**
 * True for the model version the inference sidecar's placeholder adapter
 * stamps on its fake deterministic volumes ("placeholder-v1", ...). Exported so
 * the workspace can warn about it too.
 */
export function isPlaceholderModel(modelVersion: string | null | undefined): boolean {
  return typeof modelVersion === 'string' && modelVersion.trim().toLowerCase().startsWith('placeholder')
}

/** Rule inputs of one visit that are unknown. */
function missingFluidAndBcva(v: NamdVisit, scope: 'current' | 'previous'): NamdMissingInput[] {
  const m: NamdMissingInput[] = []
  if (v.irf == null) m.push(`${scope}.irf`)
  if (v.srf == null) m.push(`${scope}.srf`)
  if (v.ped == null) m.push(`${scope}.ped`)
  if (v.fluidByRegion == null) m.push(`${scope}.fluidByRegion`)
  if (v.bcva == null) m.push(`${scope}.bcva`)
  return m
}

function insufficient(
  missing: NamdMissingInput[],
  placeholderModel: boolean,
  fetchFailures: NamdFetchFailure[],
): NamdAiRecommendation {
  return {
    rec: null,
    reason: 'INSUFFICIENT_DATA',
    missing,
    placeholderModel,
    fetchFailures,
    intervalWeeks: null,
    rationale: null,
    triggersFired: [],
  }
}

function hit(
  key: NamdTriggerHit['key'],
  bucket: NamdTriggerHit['bucket'],
  value: number | null = null,
  threshold: number | null = null,
): NamdTriggerHit {
  return { key, bucket, value, threshold }
}

/**
 * The rationale line for the top-priority fired trigger, as a translation key
 * and its parameters.
 *
 * Returns the structure rather than a sentence so the card can render it in
 * the reader's language. Values are rounded here, where the unit is known, so
 * the translation never has to do arithmetic.
 */
function rationaleFor(
  top: NamdTriggerHit | undefined,
): { key: string; params: Record<string, string | number> } | null {
  const base = 'studyModules.namd.recommendation.rationale.'
  if (!top) return { key: base + 'STABLE', params: {} }

  const params: Record<string, string | number> = {
    value: top.value != null ? Math.round(top.value) : 0,
    threshold: top.threshold != null ? Math.round(top.threshold) : 0,
  }

  switch (top.key) {
    // The four EXTEND-eligibility triggers share one sentence: none of them is
    // a finding, they are the absence of findings.
    case 'IRF_ABSENT':
    case 'CENTRAL_SRF_ABSENT':
    case 'NO_HEMORRHAGE_OR_BCVA_LOSS':
    case 'SRF_ISOLATED_1_3_STABLE':
      return { key: base + 'DRY_STABLE', params }
    default:
      return { key: base + top.key, params }
  }
}

// ─── Public API ───────────────────────────────────────────────────

export interface UseNamdAiRecommendationArgs {
  current: ComputedRef<NamdVisit | null>
  prev: ComputedRef<NamdVisit | null>
  /**
   * Cross-visit context — the rule engine needs both to evaluate
   * triggers like "SRF in 1–3 mm ring increases vs reference/nadir".
   * Reference = the baseline visit (V01). Nadir = the lowest
   * SRF-in-1-to-3mm observed so far. {@link useNamdVisitData}
   * surfaces both from the visit timeline.
   */
  reference?: ComputedRef<NamdVisit | null>
  nadirSrfRing1to3Nl?: ComputedRef<number | null>
}

export function useNamdAiRecommendation(
  args: UseNamdAiRecommendationArgs,
): ComputedRef<NamdAiRecommendation | null> {
  const { current, prev, reference, nadirSrfRing1to3Nl } = args
  return computed<NamdAiRecommendation | null>(() => {
    const cur = current.value
    const prevV = prev.value
    if (!cur) return null
    if (!prevV) {
      // First visit — engine has nothing to compare against; the
      // workspace shows the loading-phase copy instead of a rec card.
      return null
    }

    // The interval a SHORTEN proposes steps down from; shared by both exits.
    // 2026-07-06 — derive the "last applied interval" from the week
    // gap between prev and current. `prevV.interval` is set from the
    // NAMD_DECISION_INTERVAL_WEEKS CRF item (when present) — but the
    // composable currently leaves it null for real subjects because
    // no decision-timeline reader has shipped yet. Falling straight
    // through to LOADING_INTERVAL_WEEKS made KEEP always propose 4 w
    // even when the visit's actual gap was 12 w.
    const spanWeeks = cur.week - prevV.week
    const baseInterval = prevV.interval
      ?? (spanWeeks > 0 ? spanWeeks : LOADING_INTERVAL_WEEKS)

    const fetchFailures = [...new Set<NamdFetchFailure>([
      ...(cur.fetchFailures ?? []),
      ...(prevV.fetchFailures ?? []),
    ])]

    // A placeholder model's volumes are fake: refuse outright, SHORTEN included.
    const placeholderModel = isPlaceholderModel(cur.modelVersion) || isPlaceholderModel(prevV.modelVersion)
    if (placeholderModel) return insufficient([], true, fetchFailures)

    // ─── What do we not know? ───
    const missing: NamdMissingInput[] = [
      ...missingFluidAndBcva(prevV, 'previous'),
      ...missingFluidAndBcva(cur, 'current'),
    ]
    if (cur.hemorrhage == null) missing.push('current.hemorrhage')
    if (cur.bcvaAttributableToNamd == null) missing.push('current.bcvaAttributableToNamd')
    if (reference?.value && reference.value.fluidByRegion == null) missing.push('reference.fluidByRegion')

    const fired: NamdTriggerHit[] = []

    // ─── SHORTEN bucket — each trigger only on the inputs it needs ───
    const irfPrev = prevV.irf
    const irfCur = cur.irf
    if (irfPrev != null && irfCur != null) {
      const dIrf = irfCur - irfPrev
      if (irfPrev <= ABSENT_NL && irfCur > ABSENT_NL) {
        fired.push(hit('DE_NOVO_IRF', 'SHORTEN', irfCur, ABSENT_NL))
      }
      if (dIrf > IRF_INCREASE_NL) {
        fired.push(hit('IRF_INCREASE', 'SHORTEN', dIrf, IRF_INCREASE_NL))
      }
      if (irfPrev > ABSENT_NL && irfCur > ABSENT_NL && irfCur < irfPrev) {
        const dropFraction = (irfPrev - irfCur) / irfPrev
        if (dropFraction < IRF_DECREASE_SUFFICIENT_PCT) {
          fired.push(hit('IRF_DECREASE_INSUFFICIENT', 'SHORTEN',
            dropFraction, IRF_DECREASE_SUFFICIENT_PCT))
        }
      }
    }

    const srfC1Prev = central1mmSrf(prevV)
    const srfC1Cur = central1mmSrf(cur)
    if (srfC1Prev != null && srfC1Cur != null) {
      if (srfC1Prev <= ABSENT_NL && srfC1Cur > ABSENT_NL) {
        fired.push(hit('DE_NOVO_CENTRAL_SRF', 'SHORTEN', srfC1Cur, ABSENT_NL))
      }
      const dCentralSrf = srfC1Cur - srfC1Prev
      if (dCentralSrf > CENTRAL_SRF_STRICT_INCREASE_NL) {
        fired.push(hit('CENTRAL_SRF_INCREASE', 'SHORTEN',
          dCentralSrf, CENTRAL_SRF_STRICT_INCREASE_NL))
      }
    }

    const srfRingPrev = ring1to3Srf(prevV)
    const srfRingCur = ring1to3Srf(cur)
    if (srfRingPrev != null && srfRingCur != null) {
      const dRing = srfRingCur - srfRingPrev
      let ringIncrease = dRing >= SRF_RING_1_3_INCREASE_NL
      if (!ringIncrease && reference?.value) {
        const refRing = ring1to3Srf(reference.value)
        if (refRing != null && srfRingCur - refRing >= SRF_RING_1_3_INCREASE_NL) ringIncrease = true
      }
      if (!ringIncrease && nadirSrfRing1to3Nl?.value != null) {
        const cum = srfRingCur - nadirSrfRing1to3Nl.value
        if (cum >= SRF_RING_1_3_INCREASE_NL) ringIncrease = true
      }
      if (ringIncrease) {
        fired.push(hit('SRF_RING_1_3_INCREASE', 'SHORTEN', dRing, SRF_RING_1_3_INCREASE_NL))
      }
    }

    // Only a RECORDED true counts; null (unknown) is handled by the gate below.
    if (cur.hemorrhage === true) {
      fired.push(hit('NEW_HEMORRHAGE', 'SHORTEN'))
    }
    if (cur.bcva != null && prevV.bcva != null && cur.bcvaAttributableToNamd === true) {
      const dBcva = cur.bcva - prevV.bcva
      if (dBcva <= -BCVA_LOSS_LETTERS) {
        fired.push(hit('BCVA_LOSS_5_LETTERS', 'SHORTEN', dBcva, -BCVA_LOSS_LETTERS))
      }
    }

    const shortens = fired.filter((t) => t.bucket === 'SHORTEN')

    // ─── Fail-closed gate ───
    // Unknown inputs: a SHORTEN established from known data still stands; KEEP
    // and EXTEND are not reachable, so anything else is insufficient data.
    if (missing.length > 0) {
      if (shortens.length === 0) return insufficient(missing, false, fetchFailures)
      return {
        rec: 'SHORTEN',
        reason: null,
        missing,
        placeholderModel: false,
        fetchFailures,
        intervalWeeks: Math.max(baseInterval - SHIFT_WEEKS, LOADING_INTERVAL_WEEKS),
        rationale: rationaleFor(shortens[0]),
        triggersFired: shortens,
      }
    }

    // Past the gate every input is known; the assertions below only restate that.
    const irfPrevK = irfPrev as number
    const irfCurK = irfCur as number
    const dIrf = irfCurK - irfPrevK
    const srfC1PrevK = srfC1Prev as number
    const srfC1CurK = srfC1Cur as number
    const dCentralSrf = srfC1CurK - srfC1PrevK
    const srfRingPrevK = srfRingPrev as number
    const srfRingCurK = srfRingCur as number
    const dRing = srfRingCurK - srfRingPrevK
    const dBcva = (cur.bcva as number) - (prevV.bcva as number)
    const totalPrev = irfPrevK + (prevV.srf as number) + (prevV.ped as number)
    const totalCur = irfCurK + (cur.srf as number) + (cur.ped as number)

    // ─── KEEP bucket — only relevant when no SHORTEN fired ───
    if (shortens.length === 0) {
      if (irfPrevK > ABSENT_NL && irfCurK > ABSENT_NL) {
        const dropFraction = (irfPrevK - irfCurK) / irfPrevK
        if (dropFraction >= IRF_DECREASE_SUFFICIENT_PCT) {
          fired.push(hit('RESIDUAL_IRF_HALVED', 'KEEP', dropFraction, IRF_DECREASE_SUFFICIENT_PCT))
        } else if (Math.abs(dIrf) <= IRF_INCREASE_NL) {
          fired.push(hit('RESIDUAL_IRF_STABLE', 'KEEP', dIrf, IRF_INCREASE_NL))
        }
      }
      if (srfC1CurK > ABSENT_NL && srfC1CurK <= srfC1PrevK) {
        fired.push(hit('CENTRAL_SRF_IMPROVING', 'KEEP', dCentralSrf, 0))
      }
      const dActivity = totalCur - totalPrev
      if (dActivity < 0 && totalCur > ABSENT_NL) {
        fired.push(hit('ACTIVITY_IMPROVING', 'KEEP', dActivity, 0))
      }
    }

    // ─── EXTEND eligibility — all 4 conditions must hold ───
    const irfAbsent = irfCurK <= ABSENT_NL
    const centralSrfAbsent = srfC1CurK <= ABSENT_NL
    // Both flags are RECORDED here (the gate above rules out null), so
    // `=== false` is a physician's "no", not a default.
    const noHemorrhageOrBcvaLoss = cur.hemorrhage === false
      && !(dBcva <= -BCVA_LOSS_LETTERS && cur.bcvaAttributableToNamd === true)
    // "no SRF anywhere" OR "only isolated stable SRF in the 1–3 mm ring"
    const ringStableOrAbsent = srfRingCurK <= ABSENT_NL
      || (Math.abs(dRing) <= SRF_RING_1_3_INCREASE_NL && srfRingCurK <= srfRingPrevK + ABSENT_NL)

    if (irfAbsent) fired.push(hit('IRF_ABSENT', 'EXTEND', irfCurK, ABSENT_NL))
    if (centralSrfAbsent) fired.push(hit('CENTRAL_SRF_ABSENT', 'EXTEND', srfC1CurK, ABSENT_NL))
    if (noHemorrhageOrBcvaLoss) fired.push(hit('NO_HEMORRHAGE_OR_BCVA_LOSS', 'EXTEND'))
    if (ringStableOrAbsent) fired.push(hit('SRF_ISOLATED_1_3_STABLE', 'EXTEND', srfRingCurK, ABSENT_NL))

    const allExtendOk = irfAbsent && centralSrfAbsent
      && noHemorrhageOrBcvaLoss && ringStableOrAbsent

    // ─── Pick the rec ───
    let rec: 'SHORTEN' | 'KEEP' | 'EXTEND'
    let next: number
    if (shortens.length > 0) {
      rec = 'SHORTEN'
      next = Math.max(baseInterval - SHIFT_WEEKS, LOADING_INTERVAL_WEEKS)
    } else if (fired.some((t) => t.bucket === 'KEEP')) {
      rec = 'KEEP'
      next = baseInterval
    } else if (allExtendOk) {
      rec = 'EXTEND'
      next = Math.min(baseInterval + SHIFT_WEEKS, MAX_EXTEND_WEEKS)
    } else {
      // Defensive — nothing fired (shouldn't really happen given the
      // EXTEND-eligibility booleans always evaluate). Keep is the safe
      // default.
      rec = 'KEEP'
      next = baseInterval
    }

    // Stable ordering for display: SHORTEN > KEEP > EXTEND.
    const orderRank: Record<NamdTriggerHit['bucket'], number> = { SHORTEN: 0, KEEP: 1, EXTEND: 2 }
    fired.sort((a, b) => orderRank[a.bucket] - orderRank[b.bucket])

    const topBucket: NamdTriggerHit['bucket'] = rec === 'SHORTEN' ? 'SHORTEN'
      : rec === 'KEEP' ? 'KEEP'
        : 'EXTEND'
    const top = fired.find((t) => t.bucket === topBucket)
    return {
      rec,
      reason: null,
      missing: [],
      placeholderModel: false,
      fetchFailures,
      intervalWeeks: next,
      rationale: rationaleFor(top),
      triggersFired: fired,
    }
  })
}

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
 * <p>First visit (no {@code prev}): the rule engine has nothing to
 * compare against. Returns null — the Overview tab falls through to a
 * "Loading-Phase — monatliche Injektion" copy block instead of a rec
 * card.
 */

import { computed, type ComputedRef } from 'vue'
import type { NamdAiRecommendation, NamdTriggerHit, NamdVisit } from '../types'

// ─── Tunable thresholds ────────────────────────────────────────────
/** SHORTEN: total IRF increase (nL) above this is "above threshold". */
export const IRF_INCREASE_NL = 20
/** SHORTEN: central-1mm SRF increase (nL) above this is "above strict threshold". */
export const CENTRAL_SRF_STRICT_INCREASE_NL = 10
/** SHORTEN: SRF in the 1–3 mm ring rises by ≥ this many nL vs prev OR cumulatively vs reference/nadir. */
export const SRF_RING_1_3_INCREASE_NL = 10
/** SHORTEN: IRF dropped vs prev but by < this fraction (i.e. < 50 %). */
export const IRF_DECREASE_SUFFICIENT_PCT = 0.5
/** SHORTEN: ≥ this BCVA-letters drop vs prev (combined with the attribution flag) triggers BCVA_LOSS. */
export const BCVA_LOSS_LETTERS = 5
/** KEEP / EXTEND: max activity (nL) considered "absent" — covers measurement noise around 0. */
export const ABSENT_NL = 1
/** Default interval shift when shortening / extending (weeks). */
export const SHIFT_WEEKS = 2
/** Loading-phase interval (weeks). */
export const LOADING_INTERVAL_WEEKS = 4
/** Hard cap on the extension ladder (weeks). */
export const MAX_EXTEND_WEEKS = 16

// ─── Helpers ───────────────────────────────────────────────────────

function central1mmSrf(v: NamdVisit | null): number {
  return v?.fluidByRegion?.c1?.srf ?? 0
}
/** SRF inside the 1–3 mm ring annulus (central_3mm − central_1mm). */
function ring1to3Srf(v: NamdVisit | null): number {
  if (!v?.fluidByRegion) return 0
  return Math.max(0, v.fluidByRegion.c3.srf - v.fluidByRegion.c1.srf)
}

function hit(
  key: NamdTriggerHit['key'],
  bucket: NamdTriggerHit['bucket'],
  value: number | null = null,
  threshold: number | null = null,
): NamdTriggerHit {
  return { key, bucket, value, threshold }
}

/** Build the rationale line from the top-priority fired trigger. */
function rationaleFor(top: NamdTriggerHit | undefined): string {
  if (!top) return 'Stabile Befundlage.'
  switch (top.key) {
    case 'DE_NOVO_IRF':
      return 'Neu aufgetretene intraretinale Flüssigkeit — Intervall verkürzen.'
    case 'IRF_INCREASE':
      return `IRF um ${Math.round(top.value ?? 0)} nL gestiegen (Schwelle ${top.threshold} nL).`
    case 'IRF_DECREASE_INSUFFICIENT':
      return 'IRF rückläufig um weniger als 50 % vs. Vorbesuch — Intervall verkürzen.'
    case 'DE_NOVO_CENTRAL_SRF':
      return 'Neu aufgetretene zentrale subretinale Flüssigkeit (1 mm).'
    case 'CENTRAL_SRF_INCREASE':
      return `Zentrale SRF um ${Math.round(top.value ?? 0)} nL gestiegen (Schwelle ${top.threshold} nL).`
    case 'SRF_RING_1_3_INCREASE':
      return `SRF im 1–3 mm Ring um ${Math.round(top.value ?? 0)} nL gestiegen.`
    case 'NEW_HEMORRHAGE':
      return 'Neue retinale Blutung — Intervall verkürzen.'
    case 'BCVA_LOSS_5_LETTERS':
      return 'Visusverlust ≥ 5 Buchstaben, nAMD-attribuiert.'
    case 'RESIDUAL_IRF_HALVED':
      return 'IRF um ≥ 50 % zurückgegangen, jedoch noch vorhanden — Intervall halten.'
    case 'RESIDUAL_IRF_STABLE':
      return 'Stabile residuelle IRF — Intervall halten.'
    case 'CENTRAL_SRF_IMPROVING':
      return 'Zentrale SRF stabil oder rückläufig, jedoch noch vorhanden.'
    case 'ACTIVITY_IMPROVING':
      return 'Aktivität insgesamt rückläufig — Intervall halten.'
    case 'IRF_ABSENT':
    case 'CENTRAL_SRF_ABSENT':
    case 'NO_HEMORRHAGE_OR_BCVA_LOSS':
    case 'SRF_ISOLATED_1_3_STABLE':
      return 'Trockener, stabiler Befund — Intervall verlängern.'
  }
  return ''
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

    const fired: NamdTriggerHit[] = []

    // ─── SHORTEN bucket ───
    const irfPrev = prevV.irf
    const irfCur = cur.irf
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

    const srfC1Prev = central1mmSrf(prevV)
    const srfC1Cur = central1mmSrf(cur)
    if (srfC1Prev <= ABSENT_NL && srfC1Cur > ABSENT_NL) {
      fired.push(hit('DE_NOVO_CENTRAL_SRF', 'SHORTEN', srfC1Cur, ABSENT_NL))
    }
    const dCentralSrf = srfC1Cur - srfC1Prev
    if (dCentralSrf > CENTRAL_SRF_STRICT_INCREASE_NL) {
      fired.push(hit('CENTRAL_SRF_INCREASE', 'SHORTEN',
        dCentralSrf, CENTRAL_SRF_STRICT_INCREASE_NL))
    }

    const srfRingPrev = ring1to3Srf(prevV)
    const srfRingCur = ring1to3Srf(cur)
    const dRing = srfRingCur - srfRingPrev
    let ringIncrease = dRing >= SRF_RING_1_3_INCREASE_NL
    if (!ringIncrease && reference?.value) {
      const cum = srfRingCur - ring1to3Srf(reference.value)
      if (cum >= SRF_RING_1_3_INCREASE_NL) ringIncrease = true
    }
    if (!ringIncrease && nadirSrfRing1to3Nl?.value != null) {
      const cum = srfRingCur - nadirSrfRing1to3Nl.value
      if (cum >= SRF_RING_1_3_INCREASE_NL) ringIncrease = true
    }
    if (ringIncrease) {
      fired.push(hit('SRF_RING_1_3_INCREASE', 'SHORTEN', dRing, SRF_RING_1_3_INCREASE_NL))
    }

    if (cur.hemorrhage) {
      fired.push(hit('NEW_HEMORRHAGE', 'SHORTEN'))
    }
    const dBcva = cur.bcva - prevV.bcva
    if (dBcva <= -BCVA_LOSS_LETTERS && cur.bcvaAttributableToNamd) {
      fired.push(hit('BCVA_LOSS_5_LETTERS', 'SHORTEN', dBcva, -BCVA_LOSS_LETTERS))
    }

    const shortens = fired.filter((t) => t.bucket === 'SHORTEN')

    // ─── KEEP bucket — only relevant when no SHORTEN fired ───
    if (shortens.length === 0) {
      if (irfPrev > ABSENT_NL && irfCur > ABSENT_NL) {
        const dropFraction = (irfPrev - irfCur) / irfPrev
        if (dropFraction >= IRF_DECREASE_SUFFICIENT_PCT) {
          fired.push(hit('RESIDUAL_IRF_HALVED', 'KEEP', dropFraction, IRF_DECREASE_SUFFICIENT_PCT))
        } else if (Math.abs(dIrf) <= IRF_INCREASE_NL) {
          fired.push(hit('RESIDUAL_IRF_STABLE', 'KEEP', dIrf, IRF_INCREASE_NL))
        }
      }
      if (srfC1Cur > ABSENT_NL && srfC1Cur <= srfC1Prev) {
        fired.push(hit('CENTRAL_SRF_IMPROVING', 'KEEP', dCentralSrf, 0))
      }
      const dActivity = (cur.irf + cur.srf + cur.ped) - (prevV.irf + prevV.srf + prevV.ped)
      if (dActivity < 0 && (cur.irf + cur.srf + cur.ped) > ABSENT_NL) {
        fired.push(hit('ACTIVITY_IMPROVING', 'KEEP', dActivity, 0))
      }
    }

    // ─── EXTEND eligibility — all 4 conditions must hold ───
    const irfAbsent = irfCur <= ABSENT_NL
    const centralSrfAbsent = srfC1Cur <= ABSENT_NL
    const noHemorrhageOrBcvaLoss = !cur.hemorrhage
      && !(dBcva <= -BCVA_LOSS_LETTERS && cur.bcvaAttributableToNamd)
    // "no SRF anywhere" OR "only isolated stable SRF in the 1–3 mm ring"
    const ringStableOrAbsent = srfRingCur <= ABSENT_NL
      || (Math.abs(dRing) <= SRF_RING_1_3_INCREASE_NL && srfRingCur <= srfRingPrev + ABSENT_NL)

    if (irfAbsent) fired.push(hit('IRF_ABSENT', 'EXTEND', irfCur, ABSENT_NL))
    if (centralSrfAbsent) fired.push(hit('CENTRAL_SRF_ABSENT', 'EXTEND', srfC1Cur, ABSENT_NL))
    if (noHemorrhageOrBcvaLoss) fired.push(hit('NO_HEMORRHAGE_OR_BCVA_LOSS', 'EXTEND'))
    if (ringStableOrAbsent) fired.push(hit('SRF_ISOLATED_1_3_STABLE', 'EXTEND', srfRingCur, ABSENT_NL))

    const allExtendOk = irfAbsent && centralSrfAbsent
      && noHemorrhageOrBcvaLoss && ringStableOrAbsent

    // ─── Pick the rec ───
    let rec: NamdAiRecommendation['rec']
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
      intervalWeeks: next,
      rationale: rationaleFor(top),
      triggersFired: fired,
    }
  })
}

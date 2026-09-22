# nAMD treat-and-extend — recommendation rules

Status: **v1, awaiting clinical sign-off.** Threshold set `v1-2026-09`.

The nAMD workspace shows a treatment recommendation next to each visit. This
document is what that recommendation is computed from. It exists because a
recommendation a clinician can read but not check is not a decision aid.

The recommendation is advisory. The physician records their own decision in the
visit CRF, and whether they agreed with the recommendation is captured
alongside it.

Implementation:
[`useNamdAiRecommendation.ts`](../../../web/src/spa/src/studyModules/nAMD/composables/useNamdAiRecommendation.ts).

---

## Units

**Fluid volumes are in nanolitres. 1 mm³ = 1 µL = 1000 nL.**

This sentence is the reason the document exists. Until 2026-09-18 the workspace
converted mm³ to "nL" by multiplying by 100, a factor chosen so the numbers
matched a design mockup. Every figure labelled nL on the screen was therefore
ten times too small, and every threshold written in nL fired at ten times the
volume it named.

The conversion is now correct and the thresholds were multiplied by ten in the
same change, so **no recommendation changed**. What changed is that the numbers
mean what their unit says.

The consequence for this document: the threshold table below states the volumes
the rules have always acted on, in both units. The literal figures in the
original rule specification were ten times smaller in nL. Whether to lower the
thresholds to those figures is a clinical decision, not a correction — see
[Open decision](#open-decision-threshold-level).

Other units on the workspace: BCVA in ETDRS letters, central retinal thickness
in micrometres, intervals in weeks. None of those were affected.

---

## Thresholds — set `v1-2026-09`

| Constant | nL | mm³ | Used by |
|---|---:|---:|---|
| `IRF_INCREASE_NL` | 200 | 0.2 | IRF_INCREASE, RESIDUAL_IRF_STABLE |
| `CENTRAL_SRF_STRICT_INCREASE_NL` | 100 | 0.1 | CENTRAL_SRF_INCREASE |
| `SRF_RING_1_3_INCREASE_NL` | 100 | 0.1 | SRF_RING_1_3_INCREASE |
| `ABSENT_NL` | 10 | 0.01 | every "de novo" and "absent" test |
| `ACTIVITY_THRESHOLD_NL` | 200 | 0.2 | the activity pill on the patient banner |

Not volumes:

| Constant | Value | Meaning |
|---|---:|---|
| `IRF_DECREASE_SUFFICIENT_PCT` | 0.5 | a drop of less than half is "insufficient" |
| `BCVA_LOSS_LETTERS` | 5 | letters lost versus the previous visit |
| `SHIFT_WEEKS` | 2 | how far an interval moves when shortened or extended |
| `LOADING_INTERVAL_WEEKS` | 4 | interval during the loading phase |
| `MAX_EXTEND_WEEKS` | 16 | ceiling on the extension ladder |

`NAMD_THRESHOLDS_VERSION` names this set. It is shown on the recommendation
card and stored in the decision's audit snapshot, so a decision read back in a
year can be interpreted against the rules that produced it. **Changing any
value in the tables above requires a new version string and a new row in the
changelog at the end of this document.**

---

## Rules

Evaluated against the current visit and the one before it. The first visit of
an eye produces no recommendation — there is nothing to compare against, and
the workspace shows the loading-phase text instead.

Precedence: any SHORTEN trigger wins. Otherwise any KEEP trigger wins.
Otherwise, if every EXTEND condition holds, EXTEND. This ordering is
deliberate — a reason to treat sooner is never outvoted by reasons to wait.

### SHORTEN — treat sooner

| Trigger | Fires when |
|---|---|
| `DE_NOVO_IRF` | IRF was absent and is now present |
| `IRF_INCREASE` | total IRF rose by more than 0.2 mm³ |
| `IRF_DECREASE_INSUFFICIENT` | IRF fell, but by less than half |
| `DE_NOVO_CENTRAL_SRF` | central 1 mm SRF was absent and is now present |
| `CENTRAL_SRF_INCREASE` | central 1 mm SRF rose by more than 0.1 mm³ |
| `SRF_RING_1_3_INCREASE` | SRF in the 1–3 mm ring rose by at least 0.1 mm³, versus the previous visit or cumulatively versus the reference or nadir |
| `NEW_HEMORRHAGE` | a new retinal haemorrhage was recorded |
| `BCVA_LOSS_5_LETTERS` | 5 or more letters lost **and** the loss was attributed to nAMD |

The attribution flag on the last one matters: vision drops for reasons that
have nothing to do with exudation, and treating those sooner helps no one.

### KEEP — same interval

| Trigger | Fires when |
|---|---|
| `RESIDUAL_IRF_HALVED` | IRF fell by at least half but is still present |
| `RESIDUAL_IRF_STABLE` | IRF present at both visits, moving by less than the increase threshold |
| `RESIDUAL_CENTRAL_SRF_STABLE` | central SRF present and not rising |
| `ACTIVITY_DECREASING` | total activity falling but not yet absent |

### EXTEND — treat later

Requires all of:

- IRF absent (at or below 0.01 mm³)
- central 1 mm SRF absent
- no new haemorrhage and no attributed BCVA loss
- isolated 1–3 mm ring SRF stable, if present

Extension moves the interval by `SHIFT_WEEKS`, capped at `MAX_EXTEND_WEEKS`.

---

## Open decision: threshold level

The figures in the original specification, read as literal nanolitres, are ten
times smaller than the set above: IRF 20 nL, central SRF 10 nL, ring SRF 10 nL,
absent 1 nL, activity 20 nL.

Shipping those would change recommendations for real patients, in the direction
of treating more often. That is a clinical judgement about what volume of fluid
warrants acting, not a bug, so the behaviour-preserving set ships first and the
question is put to the study's clinical lead.

To adopt a different level:

1. Edit the constants in `useNamdAiRecommendation.ts` and `fluid.ts`.
2. Bump `NAMD_THRESHOLDS_VERSION`.
3. Update the threshold table above and add a changelog row naming who decided.
4. Update the fixtures in `useNamdAiRecommendation.test.ts`, which state
   volumes in nL.

Decisions already stored carry the version they were made under, so earlier
recommendations stay interpretable.

**Sign-off:**

| Role | Name | Date | Threshold set |
|---|---|---|---|
| Clinical lead | _pending_ | | `v1-2026-09` |

---

## Changelog

| Version | Date | Change |
|---|---|---|
| `v1-2026-09` | 2026-09-18 | First recorded set. Corrects the mm³ → nL conversion (was ×100, now ×1000) and multiplies every volume threshold by ten so behaviour is unchanged. |

# Phase E.10 — Usability-test results template

One per role; filename `results-YYYY-MM-DD-<role>.md`. Authored by the analyst after the panel for that role completes.

Each report is the **only** artefact the Phase E exit gate reads — keep it tight, decision-grade, and unambiguous.

---

## Headline

Single sentence that closes the four [DR-019](../../decision-record.md#dr-019--phase-e-usability-acceptance-bar) dimensions for this role.

> Example: "Investigator panel **passes** the four-dimension acceptance bar with 87 % task success, SUS median 76, 0 critical errors, 2 severe findings (Modal focus trap + CRF Entry weight-range copy)."

> ____________________________________________________________________

## Panel composition

| Participant | Years studied | Last EDC | Notes |
|---|---|---|---|
| P-01 | | | |
| P-02 | | | |
| P-03 | | | |
| P-04 | | | |
| P-05 | | | |

## Aggregate scores

| Dimension | Bar | Observed | Pass / Fail |
|---|---|---|---|
| Task success rate | ≥ 80 % | __ % | □ Pass □ Fail |
| SUS median | ≥ 70 | __ | □ Pass □ Fail |
| Critical errors | 0 | __ | □ Pass □ Fail |
| Severe findings | ≤ 2 | __ | □ Pass □ Fail |

Per-scenario success table:

| Scenario | Complete | Complete-with-help | Fail | Effective rate |
|---|---|---|---|---|
| | | | | |
| | | | | |
| | | | | |

Per-participant SUS scores:

| Participant | SUS | |
|---|---|---|
| P-01 | __ | |
| P-02 | __ | |
| P-03 | __ | |
| P-04 | __ | |
| P-05 | __ | |
| **Median** | __ | |

## Severe-finding catalogue

One paragraph per **distinct** severe finding (de-duplicated across the panel). Each paragraph carries:

- **Pattern** — what consistently went wrong, in user language.
- **Frequency** — how many of the N participants hit it.
- **Severity rationale** — why it's severe (would prevent a non-trivial subset of clinicians).
- **Fix proposal** — concrete change, owner, target sub-phase / follow-up PR.

### 1. (Pattern title)

> ____________________________________________________________________
> ____________________________________________________________________

**Frequency:** __ / N
**Severity rationale:** ____________________________________________________________________
**Fix proposal:** ____________________________________________________________________

### 2. (Pattern title)

> ____________________________________________________________________

**Frequency:** __ / N
**Severity rationale:** ____________________________________________________________________
**Fix proposal:** ____________________________________________________________________

## Critical-error catalogue (only if any)

Per-occurrence detail. **The gate fails on the first critical error.** The analyst writes this section to brief the fix team on what to test before re-running the role.

### 1. (Title)

| Field | Value |
|---|---|
| Session id | __________ |
| Time in session | __:__ |
| Scenario | __________ |
| Description | ____________________________________________________________________ |
| Root cause hypothesis | ____________________________________________________________________ |
| Re-test gate | ____________________________________________________________________ |

## Minor findings — backlog

Terse triage list. Each line carries `[priority] description` where priority ∈ `{P0, P1, P2}`. P0 = "we should fix this before clinical use even though it didn't gate-block here"; P2 = polish.

> - [P0] __________
> - [P1] __________
> - [P2] __________

## Headline quotes

Two to four verbatim think-aloud quotes that best capture the panel's reaction. Use participant id, scenario, timestamp.

> 1. (P-03, I-2, 04:21) "____________________________________________________________________"
> 2. (P-05, M-1, 02:48) "____________________________________________________________________"

## Recordings index

| Participant | Recording file | SHA-256 |
|---|---|---|
| P-01 | `recordings/2026-06-15-INV-01.mp4` | _____ |
| P-02 | `recordings/2026-06-15-INV-02.mp4` | _____ |
| | | |

Retention: 90 days post the closure decision per the institutional data-handling SOP. Re-confirm the SHA-256 of every recording before secure-delete.

## Analyst sign-off

| | |
|---|---|
| Analyst name | __________ |
| Reviewer (independent) | __________ |
| Date | __________ |

> The reviewer is a second pair of eyes from the DM/QM side who re-runs the aggregate math + spot-checks two participants' raw observer notes + recording for scoring consistency.

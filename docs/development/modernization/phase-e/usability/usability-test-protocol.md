# Phase E.10 — Usability-test protocol

**Date:** 2026-05-30
**Status:** Draft v1 — locks the operational shape of the Phase E.10 sessions. Updates land in this document as the first round reveals what needs sharpening.
**Owner:** Lead Developer (Lukas Kuchernig)
**Related:** [DR-019 — Phase E usability-acceptance bar](../../decision-record.md#dr-019--phase-e-usability-acceptance-bar), Phase E execution playbook §E.10.

## Goal

Verify against [DR-019](../../decision-record.md#dr-019--phase-e-usability-acceptance-bar)'s four-dimension acceptance bar that the Phase E SPA is ready for clinical use at MedUni Wien Ophthalmology. Three roles × ≥5 clinicians = ≥15 sessions, ≥45 task attempts.

## Panel composition

| Role | Required N | Recruit from | Notes |
|---|---|---|---|
| Investigator | ≥ 5 | Augenklinik clinical-trial team + visiting Prüfärzte | Mix of trial-experienced (≥2) and new-to-EDC (≥2). At least one resident-physician seat. |
| Monitor | ≥ 5 | MUW central monitoring + ≥ 1 sponsor-side external | One CRO monitor included so the SDV walkthrough exercises a non-MUW perspective. |
| Data Manager | ≥ 5 | MUW Studienzentrale + ≥ 1 Augenklinik Studienkoordinator | Trial-setup experience required for ≥3. |

Each participant runs **one session of ≈ 60 minutes** at a workstation matched to the production target (1440 × 900 desktop, Firefox or Chrome). German-language UI primary, English available as a fallback.

## Session format

| Block | Time | Notes |
|---|---|---|
| Welcome + consent + warm-up demographic questions | 5 min | Use [consent-template.md](consent-template.md) verbatim |
| Pre-test SUS demographics + "what's your role" | 3 min | Captures study-trial experience years + last EDC used |
| 3 scenarios (one per scenario card for the participant's role) | 35 min | ~10–12 min per scenario including think-aloud + observer probes |
| Post-test SUS questionnaire | 5 min | [sus-questionnaire.md](sus-questionnaire.md) — the standard Brooke 1986 10-item form |
| Free-form debrief — 3 questions | 8 min | "What surprised you?" / "Where did you slow down?" / "If you could change one thing, what?" |
| Thank you + €25 gift voucher | 4 min | |

## Scenario cards

Three canonical scenarios per role. Each card carries:

- Target SPA route(s) + starting URL
- Step-by-step user-narrative form ("you are seeing your first subject of the day…")
- Success criteria (the observable behaviour that means "task complete")
- Critical-error list (the behaviours that flip this scenario to a fail-the-role result)
- Notes the observer should write down

Cards live in [usability-scenarios.md](usability-scenarios.md).

## Scoring rubric

For each scenario the observer records:

| Field | Values | Notes |
|---|---|---|
| **Outcome** | Complete / Complete-with-help / Fail | Help = observer or moderator gives any task-specific hint |
| **Time-on-task** | seconds | Stopwatch starts at "you can begin" |
| **Critical errors** | count + free-text per occurrence | See per-scenario list on the card |
| **Severe findings** | 0–3 free-text | Patterns that prevented or nearly prevented success even when the outcome was Complete |
| **Minor findings** | open list | Anything worth fixing but not gate-blocking |

Per-role aggregation:

- **Task success rate** = `(Complete + 0.5 × Complete-with-help) / total attempts` — partial credit for assisted completions because the observer often gives a small nudge that wouldn't be available in production.
- **Critical errors** — sum across the panel; **1 critical error fails the role** per DR-019.
- **Severe findings** — cluster across the panel (de-duplicate via the analyst pass); ≤ 2 distinct patterns per role to pass.
- **SUS median** — sum of (positive-item score − 1) + (5 − negative-item score) × 2.5 per Brooke, then take the panel median.

## Observer / moderator pair

Each session has **two people on the test side**:

- **Moderator** — runs the conversation, gives the scenario card aloud, redirects if the participant freezes (`"What would you do next?"`). Doesn't take notes mid-conversation.
- **Observer** — fills the per-scenario [observer-template.md](observer-template.md), watches for the critical-error list, runs the stopwatch.

Both roles are familiar with the SPA before the first session. Both roles are NOT involved in the SPA implementation (they're DM + QM staff from the institutional team) — independence is required for the gate to mean anything.

## Recording

- **Screen recording** (Camtasia / OBS) — captures every session at 1440×900. Local-disk only; never uploaded to a cloud service per the institutional data-handling SOP.
- **Audio** — recorded with the participant's consent; transcribed inside the institutional network for the post-session analysis.
- **No video of the participant.** Audio + screen only.

Retention: 90 days post the gate decision, then secure-delete.

## Test environment

- SPA built from `feature/muw-phase-e-ux-mockups` tip, deployed under `app/` of a non-prod LibreClinica instance with the **mock backend** seeded by the Pinia stores' `loadMock()` calls (see [api-surface.md](../api-surface.md) for the gap inventory). This isolates the test from the production data; it also means tests don't depend on the B-category adapter PRs landing first.
- Network: institutional LAN to `https://test-libreclinica.meduniwien.ac.at/LibreClinica/app/` once the test deployment exists; for the first internal dry-run, the local Vite dev server is acceptable.
- Identity: each participant gets a clean mock identity matching their role (one of the dev role-switcher presets). The institutional SSO flow is not exercised in the usability sessions — that's covered by the SSO deployment cookbook tests.

## Result reporting

After the panel completes, the analyst writes one MD per role to `phase-e/usability/results-YYYY-MM-DD-<role>.md` using [results-template.md](results-template.md). Each report includes:

1. **Headline** — pass / fail per the four DR-019 dimensions.
2. **Aggregate scores** — task success rate, SUS median, critical-error count, severe-finding count.
3. **Severe-finding catalogue** — one paragraph per distinct pattern, with a fix proposal.
4. **Minor-finding backlog** — terse list for triage.
5. **Recordings index** — pointer to the on-disk archive.

The Phase E closure tag (`phase-e-closure`) cannot be applied until all three role reports show pass on every DR-019 dimension.

## Risks

| Risk | Mitigation |
|---|---|
| Mock backend behaves differently from the real backend ⇒ participants miss issues the real backend would surface | Run a second round on the wired-up backend once the E.4 B-category adapters land |
| Recruiting the panel takes longer than the test cycle | Schedule the test cycle on the same day as a clinical-trial team meeting so participants are already in the building |
| Observers drift in scoring across sessions | Calibration session before session 1 of each role; analyst spot-check pass at the half-way point |
| Sample size of 5 misses long-tail issues | DR-019 explicitly accepts the Nielsen / NIST minimum; long-tail issues land in the institutional GCP-validation cycle that follows E.10 |

## Reference

- DR-019 (this protocol's parent gate)
- ISO 9241-11 (usability definition)
- Brooke, J. (1986) — "SUS — A quick and dirty usability scale"
- NIST IR 7741 — Common Industry Specification for Usability — Requirements (CISU-R)
- Nielsen, J. (2000) — "Why You Only Need to Test with 5 Users"

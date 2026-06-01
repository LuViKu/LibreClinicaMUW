# Phase E.10 — Usability-test pack

All artefacts needed to run the Phase E.10 usability sessions and to document their results. Lands together so any institutional team member can pick up the binder and run a session without needing the implementing engineer in the room.

## Read order

1. **[DR-019 — Phase E usability-acceptance bar](../../decision-record.md#dr-019--phase-e-usability-acceptance-bar)** — the decision this whole pack operationalises. Four-dimension bar (task success ≥ 80 %, SUS median ≥ 70, 0 critical errors, ≤ 2 severe findings per role).
2. **[usability-test-protocol.md](usability-test-protocol.md)** — how to run a session. Panel composition, session format, scoring rubric, observer / moderator pair, recording rules, test environment, result-reporting hand-off.
3. **[usability-scenarios.md](usability-scenarios.md)** — nine scenario cards (three per role × three roles). Hand the participant the relevant section of this document, printed.

## Forms + templates

4. **[consent-template.md](consent-template.md)** — bilingual (DE primary, EN summary) participant consent. Read aloud + signed before every session.
5. **[sus-questionnaire.md](sus-questionnaire.md)** — bilingual standard Brooke 1986 SUS form + analyst scoring sheet.
6. **[observer-template.md](observer-template.md)** — one A4 sheet filled out by the observer per scenario × participant. Headers, outcome, critical-error / severe / minor catalogues, notable quotes, observer interpretation.
7. **[results-template.md](results-template.md)** — one MD per role authored by the analyst after the panel completes. The Phase E exit gate reads this artefact + nothing else; keep it decision-grade.

## After the panel

Results land at `results-YYYY-MM-DD-<role>.md` (sibling to this README), one per role. When all three roles' reports show pass on the DR-019 dimensions, the Phase E closure tag (`phase-e-closure`) can be applied.

## Sample-size scaling

DR-019 specifies ≥ 5 participants per role (Nielsen / NIST minimum). The protocol allows scaling up to 7 per role when scheduling permits; the four-dimension bar applies regardless. Scaling below 5 invalidates the gate.

## Provenance

This pack was authored 2026-05-30 alongside the Phase E.9 a11y harness. The pack is **a working draft** — updates land in this folder after the first round reveals what needs sharpening. Treat the first session of each role as the calibration run; results from the calibration count toward the gate only if the protocol applied cleanly.

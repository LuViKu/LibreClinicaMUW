# LibreClinica MUW · 1.5.0-beta.3-muw release notes

_Hotfix release. Successor to **1.5.0-beta.2-muw** (tag `1.5.0-beta.2-muw`, 2026-06-25)._

## Why this patch

Two issues were found in beta.2 between cutting the release and operator validation. Both were one-line drifts left over from yesterday's `layers`-task rollout — the matching SPA dropdowns + Liquibase CHECK constraint were updated, but two stale Java validators slipped through. beta.3 carries the fixes; no other functional changes vs beta.2.

For the full feature list landing in the 1.5.0 line, see [release-notes-1.5.0-beta.2-muw.md](release-notes-1.5.0-beta.2-muw.md).

## Fixes vs beta.2

### Enable `layers` on an event definition (hotfix)

Saving the per-event-definition default-tasks editor with `layers` selected surfaced:

```
HTTP 400 — Unknown task 'layers' — expected one of [onl, fluid, ga, pr]
```

`EventDefinitionsApiController.ALLOWED_RETINAL_TASKS` was missing `layers` despite:

- the matching Liquibase migration (`lc-muw-2026-06-24-event-def-retinal-tasks-layers`) already broadening the `event_definition_retinal_task` CHECK constraint;
- `EventDefinitionsView.RETINAL_TASK_OPTIONS` already listing `layers` in the dropdown;
- `RetinalResultsApiController.ALLOWED_RERUN_TASKS` already containing `layers` (so the rerun-as path worked).

This validator was the last stop where the PUT 400'd before reaching the DB; users saw `layers` in the dropdown, clicked save, and bounced off the guard. Comment on the field literally claimed it mirrors `ALLOWED_RERUN_TASKS` — fixed the drift + tightened the docstring with the rationale.

### Release-image workflow context (already shipped in beta.2's republish)

The DR-024 package split changed the retinal-inference Docker build context to the repo root so the Dockerfile can COPY both `retinal-inference/` AND the sibling `muw-e2e-converter/`. The release-image workflow's matrix entry was still set to `context: retinal-inference`, which made the buildx COPY steps look for files at `retinal-inference/retinal-inference/...`. Build failed.

Fixed by flipping the matrix entry to `context: .`. Shipped as part of beta.2's image republish; carried into beta.3 as part of the same lc-develop → main fast-forward.

## Beta testing checklist (delta vs beta.2)

Only the items that change because of these fixes; the full beta.2 checklist still applies.

- [ ] Open the EventDefinitionsView for any event definition, tick `layers` in the retinal-task multi-select, click save → 200 OK + persisted (no 400).
- [ ] Inspect `event_definition_retinal_task` for the event-definition row → contains a `layers` entry alongside `fluid` / `ga` / `onl` / `pr`.
- [ ] Upload a new OCT for an event definition that has `layers` enabled → a `layers` job auto-dispatches alongside the other configured tasks.
- [ ] Image stamp: LoginView + SideRail now read `v1.5.0-beta.3-muw · Build <yyyy-MM-dd> · <7-char-sha>`.

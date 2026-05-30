# Phase E — execution playbook

**Date:** 2026-05-30
**Status:** Draft. Approves entry once the [post-Phase-D UI validation](phase-e/post-phase-d-ui-validation.md)'s Phase E entry checklist closes.
**Owner:** Lead Developer (Lukas Kuchernig)
**Sibling playbooks:** [phase-b](phase-b-execution-playbook.md) · [phase-c](phase-c-execution-playbook.md) · [phase-d](phase-d-execution-playbook.md)

The Phase E SPA rewrite replaces the JSP + jQuery 1.9 + Prototype.js + GWT-compiled chrome with a modern single-page application built on the [MUW design system](phase-e/design-system/) and bound to the existing legacy servlet + Spring MVC controller surface (preserved verbatim by Phase C.11/14's `LegacyServletRegistry` and Phase D.6's `WebMvcConfig`). This playbook breaks the work into 12 sub-phases (E.0–E.11) with explicit verification gates per sub-phase.

---

## Decisions feeding this playbook

| Decision | Status | Notes |
|---|---|---|
| [DR-004](decision-record.md) — Phase E may overlap with first clinical use; admin screens stay JSP | Accepted | Constrains Phase E scope to high-traffic clinician screens |
| [DR-005](decision-record.md) — MUW Ophthalmology branding | Accepted | Applied via [muw-tailwind-config.js + muw-tokens.css](phase-e/design-system/project/) |
| [DR-008](decision-record.md) — UI framework (React / Vue 3 / Svelte) | **Open — required for E.1 gate** | Tentative recommendation: **React 19** for the size of the candidate-developer pool at MedUni Wien IT and the maturity of the React data-table / form ecosystem; final pick made at the E.1 framework-bake-off |
| [DR-014](decision-record.md) — Institution-agnostic SSO via reverse-proxy pre-auth | Accepted | Login screen must adopt the configurable "Sign in with Institutional Account" button (not the LDAP-specific button left in the static mockup) |
| DR-018 (new) — JSP retirement strategy | **Open — required for E.11 gate** | Choices: (a) retire JSPs as their SPA equivalent ships, JSP/SPA toggle behind a feature flag; (b) keep JSPs alive in parallel for a 6-month bake-in window; (c) hard-cutover at end of Phase E |
| DR-019 (new) — Acceptance gate: usability + accessibility | **Open — required for E.10 gate** | Defines what "ready for clinical use" means quantitatively (WCAG 2.2 AA + N-user usability tests with success criteria) |

---

## Pre-flight checklist

Before E.0 begins:

- [ ] Validation report's **Phase E entry checklist** closed (see [post-Phase-D UI validation §Phase E entry checklist](phase-e/post-phase-d-ui-validation.md#phase-e-entry-checklist)).
- [ ] **CI smoke-test step tightened** to also assert `curl -fL /pages/login/login` succeeds — i.e. that no future Phase E PR can silently regress the browser session entry point.
- [ ] Local environment baseline: `docker compose up --build` → 302 on `/`, **200** on `/pages/login/login`, `mvn test` green, `mvn verify -P integration-tests` green.
- [ ] Operator runbook for **seeding a Phase-D-complete password-reset / fresh admin user**, so Selenium smoke + manual QA can drive browser sessions.
- [ ] Phase E catalogue refresh PR merged (mark the 3 intentional retirements from validation §⚠️).
- [ ] The MUW design system reference bundle ([phase-e/design-system/](phase-e/design-system/)) reviewed by the lead developer; any rebrand changes that should land in the canonical mockup set lifted out of the bundle.

---

## Sub-phase ordering and verification gates

Each sub-phase ships its own PR (or PR train where noted) and is gated by:

1. **Build gate** — `mvn -DskipTests=false clean verify` green on the SPA module + the existing core/web modules.
2. **CI gate** — every job on `.github/workflows/build.yml` green on the PR HEAD; smoke step now asserts both `/` and `/pages/login/login` succeed.
3. **Visual gate** — for any sub-phase that ships a new SPA route, a Playwright screenshot of the new view checked in to `phase-e/screenshots/spa/`, manually compared against the corresponding mockup PNG in `phase-e/overview/images/` or `phase-e/design-system/project/`.
4. **Accessibility gate** — from E.4 onwards, axe-core run on the new SPA route reports **zero** WCAG 2.2 AA violations.
5. **Backend-contract gate** — for any sub-phase consuming a legacy URL, a new `web/src/test/.../<Feature>SmokeIT.java` Selenium test extends the existing harness (per [smoke-testing.md](../smoke-testing.md)) and asserts end-to-end render.

A sub-phase is **closed** when all five gates are green and the PR description's "Test plan" lists which smoke must pass.

---

## E.0 — Known-issues triage (entry gate to all other sub-phases)

**Goal:** close every entry-blocker surfaced by the validation report.

**Scope:**

1. Fix the **`/pages/login/login` + `/pages/sso/reauth` + `/pages/odmk/odm/v1/Studies` 404** (single shared root cause: the `pages` DispatcherServlet's child context isn't owning the `@Controller` and `BeanNameUrlHandlerMapping` registrations). Recommended fix: exclude the controller package from the root context's component-scan in [`LibreClinicaApplication`](../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/LibreClinicaApplication.java) via `@SpringBootApplication(scanBasePackages = …)` so the controllers register only in the `pages` child context. Surgical — single config change.
2. **Tighten the CI smoke step** so it asserts `curl -fL http://127.0.0.1:8080/LibreClinica/pages/login/login` returns 200, not just that the redirect-from-root happens.
3. **Refresh the Phase E feature catalogue** to mark the 3 intentional retirements identified by the validation report (per-subject Notes JSP, DataTables vendor bundle, jmesa dead endpoints).
4. **Operator runbook** for seeding a local admin account after Phase D.1's password-encoder migration.

**Verification gates:**

- A new `LoginPageRenderSmokeIT` extends `SmokeIT` and asserts `/pages/login/login` returns a parseable HTML body with a `<form action="/LibreClinica/j_spring_security_check">` and (when SSO is enabled) the `Sign in with Institutional Account` button.
- All three 404'd routes return 200 after the fix.

**Exit:** the validation report's "Phase E entry checklist" closes. Phase E.1 begins.

---

## E.1 — Vue 3 SPA scaffolding (bake-off waived)

**Status:** ✅ **Shipped 2026-05-30** ([web/src/spa/](../../web/src/spa/), wired into [web/pom.xml](../../web/pom.xml)).

**Goal:** stand up the SPA project structure, lock the build pipeline, and bind the bundle into the WAR.

**Scope (as shipped):**

1. **No bake-off.** DR-008 (Vue 3) was settled directly without comparing React/Vue/Svelte implementations. The differences are real but not catastrophic, and the team's prior is strong; the bake-off's risk-reduction value did not justify two weeks. See [DR-008 §Why no bake-off](decision-record.md#dr-008--ui-framework-for-phase-e-vue-3) for the full reasoning.
2. **Vue 3 SPA scaffold** under [`web/src/spa/`](../../web/src/spa/): package.json + vite.config.ts + tsconfig.json + index.html + src/main.ts + src/App.vue + src/router/ + src/locales/ (DE + EN) + src/style.css with Tailwind v4 + MUW `@theme` directives (per E.2 specification, shipped together).
3. **Frontend Maven Plugin** wired into [`web/pom.xml`](../../web/pom.xml). `mvn package` runs `pnpm install` + `pnpm build` automatically. Vite outputs to `web/src/main/webapp/app/`, maven-war-plugin packages it into the WAR. Skip during fast Java iteration via `mvn package -DskipSpa=true`.

**Verification gates (as shipped):**

- ✅ Vue 3 SPA renders a smoke-test landing view at `/LibreClinica/app/` after `mvn package + docker compose up`. The HomeView uses MUW Dunkelblau + Newsreader serif + coral accent + i18n switching between `de-AT` and `en`.
- ✅ DR-008 documented as **Accepted** ([decision-record.md](decision-record.md)).
- ✅ The SPA build runs alongside the Maven build via the Frontend Maven Plugin.
- ⏳ A Vue-specific CI job (`pnpm test` + `pnpm build`) is not yet wired — added in E.4 alongside the backend API surface review.

**Carry-overs into E.2:**

- Vendor the actual WOFF2 font files for Newsreader / Inter / JetBrains Mono into `web/src/spa/src/assets/fonts/` so institutional networks aren't reliant on Google Fonts.
- Set up the `pnpm check-tokens` script that fails the build if any SPA component uses a colour outside the locked MUW palette.

---

## E.2 — Tailwind production build and design-token lockdown

**Status:** 🟡 **Partial — shipped 2026-05-30 alongside E.1.** Token port complete; font vendoring + `check-tokens` script deferred.

**Goal:** lift the MUW design system from the CDN-driven mockup style into a real Tailwind build, locked behind a CI gate.

**Scope (as shipped 2026-05-30):**

1. ✅ **Tailwind v4 wired** in [web/src/spa/src/style.css](../../web/src/spa/src/style.css) with `@theme` directives covering the full MUW palette (muw-blue + muw-sky + muw-teal + muw-coral with 50–900 shade scales), Newsreader / Inter / JetBrains Mono font stacks, MUW radius (`--radius-muw`), and MUW shadows (`--shadow-muw-card` / `--shadow-muw-elev`). The new `@theme` block uses Tailwind v4's CSS custom-property convention (`--color-muw-blue-800`, etc.) so `bg-muw-blue-800` / `text-muw-coral-700` style classes work natively without a separate `tailwind.config.js`.
2. ⏳ **`design-tokens.json` Style Dictionary export** — deferred. Will land alongside the Figma handoff loop in E.5.
3. ⏳ **`pnpm check-tokens` CI gate** — wired as a script entry in [package.json](../../web/src/spa/package.json) but the implementation script (regex over `src/**/*.{vue,ts}` flagging non-MUW colour utilities) is not written yet. Land in E.3 once enough primitives exist to test against.
4. ⏳ **Vendor WOFF2 fonts** to `web/src/spa/src/assets/fonts/` — directory created, files not yet committed. Carry-over into E.2's second pass.

**Verification gates (carry-overs):**

- `pnpm build` produces a deterministic CSS bundle whose hash matches across two consecutive runs.
- `pnpm run check-tokens` is wired into CI and fails on a synthetic violation in the test suite.
- The vendored fonts are loaded with `font-display: swap` and `<link rel="preload">` headers for the two display-weight cuts used above the fold.

---

## E.3 — Shared component library extraction

**Status:** 🟢 **7/10 primitives shipped 2026-05-30** ([web/src/spa/src/components/](../../web/src/spa/src/components/)). Histoire wired; `pnpm check-tokens` guard green across 25 source files.

**Goal:** build the primitives every later sub-phase depends on, once.

**Scope (component → mockup it was extracted from):**

| Primitive | Source mockup | Use sites (sub-phases) | Status |
|---|---|---|---|
| `<TopBar>` (logo + breadcrumb + role chip) | every mockup | E.4–E.9 | ✅ shipped |
| `<SideRail>` (role-conditional nav) | every mockup | E.4–E.9 | ✅ shipped |
| `<StatusPill>` (dot + icon + label) | [investigator-subject-matrix](phase-e/ux-mockups/investigator-subject-matrix.html) | E.4–E.9 | ✅ shipped + Vitest + Histoire story |
| `<DenseTable>` (sticky header + status-bar + slot-based body) | [monitor-sdv](phase-e/ux-mockups/monitor-sdv.html) | E.5, E.6, E.7 | ✅ shipped + Histoire story |
| Form primitives (`FieldLabel`, `TextInput`, `SelectInput`, `HelperText`, `ErrorText`) | [investigator-add-subject](phase-e/ux-mockups/investigator-add-subject.html) | E.4, E.7, E.8 | ✅ shipped + Histoire story (with disabled/readonly/error/prefix-icon variants) |
| `<Modal>` (Teleport + Transition + scrim + Esc / scrim-click close + body-scroll lock) | [monitor-add-query](phase-e/ux-mockups/monitor-add-query.html) | E.6, E.7 | ✅ shipped + Histoire story + Vitest tests (a11y + keyboard + cleanup) |
| `<DiffCard>` (stacked + compact `before → after`) | [study-audit-log](phase-e/ux-mockups/study-audit-log.html), [dm-import-crf-data](phase-e/ux-mockups/dm-import-crf-data.html) | E.6, E.8 | ✅ shipped + Histoire story |
| `<Timeline>` + `<TimelineMarker>` + `<TimelineEvent>` (rail + per-variant bullet + slot-driven event card) | [study-audit-log](phase-e/ux-mockups/study-audit-log.html) | E.6 | ✅ shipped + Histoire story |
| `<Wizard>` (stepper, prev/next, preview-before-commit) | [dm-import-crf-data](phase-e/ux-mockups/dm-import-crf-data.html) | E.8 | ⏳ pending |
| `<E-SignatureBlock>` (re-auth + attestation) | [investigator-sign-subject](phase-e/ux-mockups/investigator-sign-subject.html) | E.5, E.9 | ⏳ pending |
| `<ConfirmationWithPreflight>` (pass/warn/info rows + casebook snapshot) | [investigator-sign-subject](phase-e/ux-mockups/investigator-sign-subject.html) | E.5, E.7 | ⏳ pending |

**Verification gates:**

- **Storybook** (or Histoire for Vue) covers every primitive with a default + accessibility-stress state. Stories double as the design-token integration test.
- axe-core run inside Storybook reports zero WCAG 2.2 AA violations on every story.

**Exit:** every later sub-phase can import a primitive instead of building inline.

---

## E.4 — Backend API surface review

**Goal:** before any SPA workflow ships, enumerate the JSON endpoints the SPA will consume and identify the gap between (a) what the legacy `@Controller`s already expose as JSON, (b) what the legacy servlets expose only as JSP-forwarding handlers, and (c) what the SPA needs that doesn't exist at all.

**Scope:**

1. Grep the 28 `@Controller` classes + 216 `LegacyServletRegistry` servlets for `@ResponseBody`, `@RestController`, `MappingJackson2*`, `application/json` produces — produces the **JSON-already-exposed inventory**.
2. Map each mockup to the legacy URL it replaces ([design-notes.md](phase-e/ux-mockups/design-notes.md) has the existing table); for each entry, flag the gap category.
3. **Gap closures** for category (b) go in dedicated `feature/phase-e.4-<endpoint>-json-adapter` branches: a tiny `@RestController` wraps the legacy servlet's domain layer and returns JSON; the servlet stays alive for the JSP UI in parallel until E.11.

**Verification gates:**

- A `phase-e-api-surface.md` table inventory shipped to `docs/development/modernization/phase-e/`.
- Every gap-closure adapter ships with a `*ControllerIT.java` MockMvc test.

---

## E.5 — Investigator workflow (POC → first clinical-rotation feedback)

**Goal:** ship the Investigator's three highest-traffic screens end-to-end as the first SPA POC; run an in-clinic walkthrough.

**Order:**

1. **Subject Matrix** ([investigator-subject-matrix.html](phase-e/ux-mockups/investigator-subject-matrix.html)) — replaces `/ListStudySubjects`.
2. **Add Subject** ([investigator-add-subject.html](phase-e/ux-mockups/investigator-add-subject.html)) — replaces `/AddNewSubject`.
3. **CRF Data Entry** ([investigator-crf-entry.html](phase-e/ux-mockups/investigator-crf-entry.html)) — replaces `/InitialDataEntry`. **Highest risk** — the JSP at [viewSectionDataEntry.jsp](../../web/src/main/webapp/WEB-INF/jsp/managestudy/viewSectionDataEntry.jsp) is 964 LOC of dynamic field generation, repetition groups, rule-driven show/hide, inline discrepancy modals, multi-stage workflow gates.

**Verification gates per sub-PR:**

- Backend gap-closure adapter passes its `*ControllerIT.java`.
- New SPA route renders end-to-end (Selenium smoke + visual diff against the design-system PNG).
- Feature-parity matrix entry for each replaced legacy URL ticked.

**Acceptance milestone after all three ship:**

- **Walk-through with 2–3 Augenklinik investigators** in the actual outpatient clinic, on a real laptop, on a sample protocol set up by the Data Manager. Findings → `phase-e-investigator-feedback.md`. Blocking issues fold into E.7.

---

## E.6 — Monitor workflow

**Order:**

1. **SDV Table** ([monitor-sdv.html](phase-e/ux-mockups/monitor-sdv.html)) — replaces `/pages/viewAllSubjectSDVtmp`.
2. **Read-only CRF View** ([monitor-crf-readonly.html](phase-e/ux-mockups/monitor-crf-readonly.html)) — replaces `/ViewSectionDataEntry`.
3. **Add Query modal** ([monitor-add-query.html](phase-e/ux-mockups/monitor-add-query.html)) — replaces the legacy popup-window discrepancy-note flow.
4. **Notes & Discrepancies** ([notes-discrepancies.html](phase-e/ux-mockups/notes-discrepancies.html)) — replaces `/ViewNotes` (with the per-subject `/ListSubjectDiscNote` retired per validation report).
5. **Study Audit Log** ([study-audit-log.html](phase-e/ux-mockups/study-audit-log.html)) — replaces `/ViewAuditLog`.

**Risk:** SDV is the regulator-facing surface. **Every status pill + filter combination must match the legacy view's set of legal states.** Cross-reference each pill state against [monitor-features.md](phase-e/monitor-features.md) before shipping.

---

## E.7 — Data Manager workflow

**Order:**

1. **View Subject** ([view-subject.html](phase-e/ux-mockups/view-subject.html)) + **View Events** ([view-events.html](phase-e/ux-mockups/view-events.html)) + **Schedule Event** ([schedule-event.html](phase-e/ux-mockups/schedule-event.html)) — cross-role read screens, lowest risk.
2. **Sign Subject** ([investigator-sign-subject.html](phase-e/ux-mockups/investigator-sign-subject.html)) — wires the **`<E-SignatureBlock>`** primitive. **§11.50 compliance:** the SPA's re-auth flow MUST gate on either local password challenge (default) or `/pages/sso/reauth` (only when legal/regulatory ratifies SSO proxy re-auth as §11.50-compliant). The SPA defaults to local-password-challenge and exposes the SSO path behind a `libreclinica.sso.reauth.signature.enabled` flag.
3. **Build Study** ([dm-build-study.html](phase-e/ux-mockups/dm-build-study.html)) — multi-task tracker.
4. **Update Event Definition** ([dm-update-event-definition.html](phase-e/ux-mockups/dm-update-event-definition.html)).
5. **Manage Users** ([dm-manage-users.html](phase-e/ux-mockups/dm-manage-users.html)).
6. **Create / Edit CRF** ([dm-create-crf.html](phase-e/ux-mockups/dm-create-crf.html)) — **the single most complex DM screen**. Ships behind a feature flag; legacy `/CreateCRF` + Excel upload stay as the fallback path until two full studies have been authored end-to-end on the SPA designer with zero data-entry-side regressions.
7. **Import CRF Data wizard** ([dm-import-crf-data.html](phase-e/ux-mockups/dm-import-crf-data.html)) — multi-step preview-before-commit.

---

## E.8 — Authentication integration

**Goal:** wire the SPA's auth surface to DR-014's institution-agnostic SSO + the legacy local-account fallback.

**Scope:**

1. **Login landing** ([login.html](phase-e/ux-mockups/login.html)) **reworked to match DR-014 §3** — the primary CTA is the configurable "Sign in with Institutional Account" button (label sourced from `libreclinica.sso.buttonLabel`, redirect URL from `libreclinica.sso.entryUrl`); the local username/password form is a collapsed disclosure below; the forced-password-change pane shrinks to a non-password first-login profile step (per the [`phase-e/ux-mockups/login.html`](phase-e/ux-mockups/login.html) Phase D update on `feature/muw-phase-e-ux-mockups` @ `8f7746079`). The static mockup's "Continue with MUW LDAP" wording **must not survive into the production SPA**.
2. **First-login profile completion** — display name, locale, timezone, signature-key opt-in. Fields populated from SSO attributes when present.
3. **Session expiry / re-auth UX** — when a session expires mid-CRF-entry, the SPA offers either (a) a local password re-challenge or (b) an SSO re-auth bounce (via `/pages/sso/reauth`), preserving the in-progress form state.
4. **Logout** — Spring Security `/Logout` works today; SPA wires to it.

**Verification gates:**

- Selenium smoke covers each of the 8 SSO deployment patterns in [sso-deployment-guide.md](../sso-deployment-guide.md) — at minimum, the **no-SSO** and **Pattern 1 (MUW Shibboleth via `mod_shib`)** patterns end-to-end in CI; the other 6 are tested by an operator on first deployment per pattern.

---

## E.9 — Accessibility + i18n

**Goal:** clear the WCAG 2.2 AA bar and the German/English i18n bar before clinical use.

**Scope:**

1. **axe-core CI gate** — every SPA route under `webapp/app/` reports zero WCAG 2.2 AA violations.
2. **Manual keyboard-only walkthrough** of the Investigator's three primary workflows. Findings recorded as test cases.
3. **Screen-reader walkthrough** (NVDA on Windows + VoiceOver on macOS) — same three workflows.
4. **i18n** — Deutsch (Österreich) primary, English secondary. Strings extracted via a Vue/React-i18n plugin; integrated with the existing `org.akaza.openclinica.i18n.words` resource bundles where possible so JSP and SPA share the institutional glossary.

**Verification gates:**

- A11y report shipped to `phase-e/a11y-audit-2026-XX-XX.md`.
- i18n string-coverage script confirms 100% of SPA strings are translated (no English fallback in DE mode).

---

## E.10 — Usability testing with clinical users

**Goal:** evidence-based "ready for clinical use" decision.

**Scope:**

1. **N ≥ 5** Augenklinik clinicians (mix of Prüfärzte + Studienassistenz). Each runs three canonical scenarios (enrol subject + complete first CRF + respond to a query) on a sample study.
2. Task success rate, time-on-task, error rate, SUS score per user, qualitative feedback.
3. DR-019 acceptance bar: ≥80% task success, SUS ≥ 70, zero critical errors. Any miss → backlog into a Phase E.10b cycle.

**Verification gate:** DR-019 written + accepted, ratifying the quantitative bar before the test.

---

## E.11 — Cutover and JSP retirement

**Goal:** decide DR-018, execute it.

**Strategy (recommended):** **option (a) feature-flag toggle, retire JSPs as their SPA equivalent ships**.

**Scope:**

1. Each SPA route ships behind a `libreclinica.spa.<feature>.enabled` flag (default off in the first PR, default on after the in-clinic walkthrough closes successfully).
2. When the flag flips to default-on, the **legacy JSP path stays reachable** for a **6-month bake-in window** via the explicit URL (`/legacy/<jsp-path>`). After that window, the JSP is deleted in a dedicated `chore(phase-e.11-retire-<feature>)` commit.
3. **Cross-reference each retirement against [DR-004](decision-record.md)**: admin / low-frequency screens (CRF upload, study config, user mgmt) may stay JSP indefinitely — Phase E does not force them into the SPA.

**Verification gates:**

- A `phase-e-retirement-log.md` records every retired JSP + the commit that removed it + the date the bake-in window opened and closed.
- CI smoke step asserts the legacy `/legacy/<jsp-path>` works as long as the JSP is still in tree.

---

## E.reconciliation

Closing sub-phase. The Phase D-Sec pattern. Cross-references every gap noted during E.0–E.11 in `phase-e-reconciliation.md`; any item that remains open at this stage is deferred to a documented Phase E.12 follow-up or closed as "won't fix".

---

## Risk register

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|------------|--------|------------|
| E-R1 | The `/pages/login/login` 404 root-cause fix has a wider blast radius than expected (root-context vs. child-context bean visibility breaks an unrelated endpoint) | M | H | The fix lands first as a no-op refactor (component-scan boundary change only), then a separate PR adds the smoke-test assertion; bisect surface is small |
| E-R2 | Picked framework (DR-008) shows ecosystem gaps for the CRF Designer's grid-with-properties-panel interaction model | M | H | Bake-off in E.1 explicitly probes this pattern; both candidates implement the same scenario before scoring |
| E-R3 | First clinical use begins mid-Phase-E ([DR-004](decision-record.md) §2) and freezes part of the SPA at v0 while other screens iterate | M | M | Feature-flag every SPA route per E.11; first clinical study can run on the frozen subset while the rest evolves |
| E-R4 | Tailwind v4 minor-version churn during a long Phase E timeline | L | L | Pin the patch version per major-PR train; upgrade explicitly between sub-phases |
| E-R5 | SSO proxy re-auth (DR-014 §4) is not ratified as §11.50-compliant by legal/regulatory in time for E.7's Sign Subject sub-phase | M | M | Sign Subject defaults to local-password re-challenge (works without SSO); SSO re-auth wires behind a flag |
| E-R6 | The MUW design system tokens drift from the institutional styleguide if the styleguide updates mid-Phase-E (e.g. 2026 refresh) | L | L | Re-pull the styleguide PDF at each major sub-phase exit; diff against `muw-tokens.css`; update tokens before the next sub-phase starts |
| E-R7 | Phase E developer turnover mid-flight | M | M | Storybook (E.3) + Phase E execution playbook (this doc) + per-PR design-system compliance gates document the work well enough for handoff; reference [DR-002 R4 §Risk register](decision-record.md) |
| E-R8 | The 6-month JSP bake-in window (E.11) blocks the institutional team from cleaning up Prototype.js / jQuery 1.9 in time for a hypothetical Phase F security pass | M | L | The bake-in is per-feature; cumulative cleanup happens piecewise, not as a single cutover event |

---

## Rollback strategy

Phase E is feature-flag-gated end to end. Any sub-phase can be **disabled via the per-feature `libreclinica.spa.<feature>.enabled` flag** without a deploy; the legacy JSP path becomes the live UI again. The institutional validation plan must list both the SPA path and the JSP path as accepted UIs for any feature whose flag is in "may be toggled" status.

For sub-phases without a flagged-legacy fallback (E.1's framework bake-off → wrong-pick rollback = throwaway), the rollback is a `git revert` of the merge commit + a CI green-bar reverification before the next sub-phase opens.

---

## Exit criteria for Phase E

Phase E is **complete** when:

1. Every workflow in the [Phase E feature catalogue](phase-e/README.md) has a feature-flagged SPA equivalent, gated to default-on after the in-clinic walkthrough closes.
2. WCAG 2.2 AA gate is green on every SPA route (E.9).
3. DR-019's quantitative usability bar is met (E.10).
4. JSP retirement log (E.11) records the date every retired JSP was deleted + the bake-in window outcomes.
5. CI smoke step covers both the legacy `/pages/login/login` entry point (until DR-018 retires it) and the SPA's `/app/` entry point.
6. Reconciliation closure-tag (`phase-e-closure`) on `lc-develop`, mirroring the Phase D-Sec closure pattern.

---

## Reference

- [Phase E feature catalogue](phase-e/README.md)
- [post-Phase-D UI validation report](phase-e/post-phase-d-ui-validation.md)
- [Phase E mockup set + design system](phase-e/ux-mockups/) + [phase-e/design-system/](phase-e/design-system/)
- [DR-014 — Institution-agnostic SSO](decision-record.md)
- [SSO deployment guide (D.8 cookbook)](sso-deployment-guide.md)
- [Phase D execution playbook](phase-d-execution-playbook.md) — sibling format
- Phase D-Sec closure tag: `phase-d-sec-closure` @ `lc-develop @ 5d4932481`

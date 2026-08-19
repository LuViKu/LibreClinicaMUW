# LibreClinica MUW · 1.5.0-beta.7-muw release notes

_Successor to **1.5.0-beta.6-muw** (tag `1.5.0-beta.6-muw`). This window is the **#26 CRF terminology-autocomplete** feature reaching end-to-end: an ICD-10-GM + medication **terminology store**, operator-defined **repeating tables** in the CRF builder with per-column terminology autocomplete and ophthalmology laterality, and full **binding persistence** so a coded pick round-trips author → save → fork → **auto-fills sibling cells at live data entry** — plus a broad accessibility / UX / permissions / test-hardening pass._

For older releases see [release-notes-1.5.0-beta.6-muw.md](release-notes-1.5.0-beta.6-muw.md), [release-notes-1.5.0-beta.5-muw.md](release-notes-1.5.0-beta.5-muw.md), [release-notes-1.5.0-beta.4-muw.md](release-notes-1.5.0-beta.4-muw.md), [release-notes-1.5.0-beta.3-muw.md](release-notes-1.5.0-beta.3-muw.md), [release-notes-1.5.0-beta.2-muw.md](release-notes-1.5.0-beta.2-muw.md), and [release-notes-1.5.0-beta.1-muw.md](release-notes-1.5.0-beta.1-muw.md).

_No deployment-breaking changes this release — the beta.6 clean-URL / nginx cutover still applies (see below)._

## Highlights

The dominant theme is **#26 — coded terminology (diagnoses + medications) across CRF authoring and data entry**, built on a new repeating-table model. Alongside it, a WCAG 2.2 AA accessibility pass, permissions fixes, CRF-builder resilience, and expanded end-to-end coverage.

### #26 — CRF terminology autocomplete + repeating tables

- **Terminology store (slices 1–2).** New schema + a streaming **ICD-10-GM** ingest and a **medication** catalogue, exposed via a `terminology/search` autocomplete API. First-boot medication load fires automatically when the catalogue is empty.
- **Repeating-table authoring.** The CRF builder gains operator-defined **repeating tables**: per-column type (text / number / date / **laterality OD·OS·OU**), opt-in terminology **autocomplete** per text column (medication or ICD-10-GM), and an explicit property→field **fill map**. Plus choice-option authoring for single/multi-select items.
- **Ophthalmology laterality gating.** A laterality column is gated to **eye diagnoses (ICD-10 chapter VII, H00–H59)** — enabled only when the row's diagnosis is an eye code (H60+ ear excluded), and always-on for per-eye medication tables (topical drugs still need OD/OS). Works even without a dedicated ICD-Code column: the picked concept's code is remembered per row.
- **Binding persistence** (`crf_item_terminology`). The full binding — code system **+ fill map** — persists per item, so a saved table **auto-fills sibling cells at live entry**: pick a diagnosis and the ICD-Code fills; pick a medication and Dosis/Einheit fill. Fill targets are stored as the generated item oc_oid so fork and entry resolve the sibling cell identically.
- **Fork-from-version recovery.** Forking a CRF to a new version now recovers its **repeating tables, terminology bindings (system + fill map), conditional-display (show-when) rules, and row bounds (min/max)** — all previously dropped by the fork endpoint.
- **Save-side correctness.** SPA-authored equality show-when rules now persist to `scd_item_metadata`; the builder flags **duplicate item OIDs** at Validieren (two items sharing an OID silently merged before); draft-restore reseeds the UID + column-key counters so a restored draft plus a newly-added item can't collide.

### Accessibility · UX · permissions · tests

- **WCAG 2.2 AA pass** — contrast, single `main` landmark, finalized EN locale.
- **Permissions** — Data Manager manages sites; physician can open Notes; demo admin bound to the Default Study.
- **UX truthfulness** — dead-link / silent-error / `alert()` / hardcoded-string cleanups; sites inline validation + add-subject action hierarchy.
- **CRF-builder resilience** — draft **autosave** guards against idle-logout data loss; **.xls export** works for in-app-authored CRFs.
- **Dependencies** — 13 Dependabot advisories patched via package-manager overrides.
- **e2e** — eCRF fill+save, real-login smoke + a11y suites, cross-role visual-capture journey, CI wiring.

## Migrations

Five new Liquibase changesets run on boot (back up the DB before deploy):

- `lc-muw-2026-08-12-terminology.xml` — the ICD-10-GM + medication terminology store
- `lc-muw-2026-08-12-crf-item-terminology.xml` — per-item terminology binding (system + fill map)
- `lc-muw-2026-08-12-grant-admin-default-study.xml`
- `lc-muw-2026-08-11-resync-seeded-sequences.xml`
- `lc-muw-2026-07-13-namd-visit-crf-prod-seed.xml`

## Beta-testing checklist

### Smoke

- [ ] `ghcr.io/luviku/libreclinicamuw:1.5.0-beta.7-muw` pulls cleanly + Tomcat reaches healthy.
- [ ] `LoginView` footer renders `v1.5.0-beta.7-muw · Build <yyyy-MM-dd> · <7-char-sha>`.
- [ ] First boot ingests the medication catalogue — terminology search returns hits.
- [ ] nginx sidecar healthy + cert valid (unchanged from beta.6 — the app is only reachable through the proxy).

### #26 CRF terminology (the headline)

- [ ] In the CRF builder, add a **repeating table**: a Medikament column (medication autocomplete), a Diagnose column (ICD-10-GM autocomplete) + an ICD-Code column, an Auge column (laterality), and dates. Add fills `code → ICD-Code` and (on the med column) `strength → Dosis`, `unit → Einheit`.
- [ ] **Save** the CRF → **fork** it to a new version → the tables, bindings, and fill map recover in the builder.
- [ ] Assign the new version to an event, enrol a subject, open the CRF at **data entry**:
  - pick a diagnosis (e.g. Glaukom / H40) → the Auge laterality **enables** and the ICD-Code cell **auto-fills**;
  - pick a medication → **Dosis + Einheit auto-fill**.
- [ ] **Validieren** flags a duplicate item OID when two items share one.

## Known issues

- **Repeating-table column types are limited** to text / number / date / laterality — controlled-list fields (route, dose unit, frequency, ongoing Y/N/U) are entered as free text, and the laterality column has no "not eye-specific" option (use OU or a text column). Follow-up: generic single-select columns + a not-eye-specific laterality value.
- **Forking a repeating table regenerates its column item OIDs** (authoring column keys aren't persisted). The table structure, bindings, and fill map recover correctly, but the forked version's column OIDs differ from the source version's.
- Carried from beta.6 — the clean-URL cutover means the app is unreachable if nginx isn't running or the cert is missing/mismatched; verify the proxy before announcing the URL. See [release-notes-1.5.0-beta.6-muw.md § Known issues](release-notes-1.5.0-beta.6-muw.md#known-issues).

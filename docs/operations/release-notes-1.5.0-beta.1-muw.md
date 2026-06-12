# LibreClinica MUW · 1.5.0-beta.1-muw release notes

_Released for beta testing. Successor to the **1.4.0rc1-muw** release candidate (tag `ga-cohort-hardening`, 2026-05-31)._

## Why a beta channel

The minor-version bump from 1.4.0rc1-muw reflects a substantial new authoring surface (the **ophthalmology field catalog**) and a redesigned CRF entry view (specialist widgets, bilateral pair detection, conditional reason inputs). Both are operator-facing UX additions, not just stabilisation — beta gives a controlled window to validate them in clinic before they reach the GA channel.

About 35 PRs landed on `lc-develop` between `ga-cohort-hardening` and this tag. The most user-visible changes are grouped below; full commit history is on the `lc-develop` branch.

## Headline changes

### Standardised ophthalmology field catalog (F1 – F4)

A curated registry of pre-built ophthalmology field types — operators drop them into a CRF section from a single picker instead of typing OIDs, units, German labels and modality bindings by hand.

- **Catalog seed** (#194) — 12 entries cover the design template's measurement set: BCVA Letters / LogMAR / Snellen, IOP, Spectralis-OCT done / reason, Refraction, CRT, Fundus done / reason, ACD, Lens status. Each row carries the canonical OID prefix, German + English label, widget hint, numeric range, conditional-display wiring and (where applicable) the modality registry binding.
- **SPA catalog binding** (#195) — `CrfItemWidget` resolves its widget chrome (number-stepper / Snellen / segmented Ja-Nein / conditional reason) through the catalog by matching the item OID's trailing tokens to a catalog code. Legacy CRFs that don't match the catalog still render via the old heuristic.
- **Wizard "Pick from catalog"** (#196) — the CRF Authoring Wizard's section step gains an **"+ Aus Katalog"** button. Picking an entry materialises 1 item (single-eye) or 2 items (paired OD + OS) in the section with canonical OIDs + German labels + units already filled in. On submit the backend adapter back-fills any blank fields from the matching catalog row.
- **OPHTH v2.0 hot-patch** (#199) — the existing OPHTH Visit v2.0 CRF (the one GA-001 already enters data into) is realigned: the four `SPECTRALIS_DONE` OIDs are renamed to `SPECTRALIS_OCT_DONE` / `SPECTRALIS_OCT_REASON` so the catalog resolver finds them, and all 14 v2.0 items get descriptive `left_item_text` + `right_item_text` instead of the bare "OD" / "OS" markers.
- **Modality OID aliases** (#202) — the modality registry gains nullable `item_oid_od_alias` / `item_oid_os_alias` columns, seeded with the v2.0 OIDs (`I_OPHTH_OD_BCVA_LETTERS`, `I_OPHTH_OD_IOP`, …). The baselines query OR's the primary + alias OIDs so the **Erstmessungen** panel reads GA-001's saved values without breaking the v1.0 integration test fixtures.

### CRF entry visual overhaul

- **Design polish** (#193) — section header gains the coral `muw-rule` accent and uppercase muw-blue title. Bilateral column headers show colour-coded R (teal) / L (sky) badges + "Rechtes Auge" / "Linkes Auge" subtitles. Row layout adopts the design's 17px padding + hover gradient. Specialist widgets land: rounded number-stepper with inline unit + vertical chevron buttons, Snellen fraction widget, segmented Ja / Nein pill control, refraction compound row that always renders the canonical SPHERE / TORUS / ANGLE / VISUS quartet.
- **Bilateral pair detector — infix laterality** (#190) — OIDs like `I_OPHTH_OD_BCVA_LETTERS` (eye token in the middle, not the leading position) now pair correctly into one row instead of stacking vertically.
- **Backend label-synthesis fallback** (#191) — the CRF Library wizard synthesises a fallback `left_item_text` when the operator leaves it blank or types only the laterality marker — so per-item rows never collapse to just "OD" / "OS" again.
- **Conditional reason styling** (#201) — the OPHTH "Spectralis-OCT — Grund" input renders three visual states driven by the parent _DONE item's value: **active-empty** (coral border + "Grund erforderlich" tag), **active-filled** (standard chrome), **inactive** (grey-out + "Aktiv, sobald „Nein" gewählt ist" hint).
- **Wizard preview compound refraction + picker consolidation** (#205) — the Authoring Wizard's live preview now routes compound sub-fields (refraction's Sphäre / Zylinder / Achse / Visus quartet) through `CrfItemWidget` so they render as the same 56×42 mini-inputs the runtime CRF entry uses, not as full-width number inputs that stacked vertically. The OPHTH catalog picker also collapses the four refraction sub-entries into one virtual compound row so the operator picks "Refraktion" once instead of ticking 4 boxes.

### Auth UX hardening

- **10-minute inactivity auto-logout** (#204) — clinical workstations left unattended now sign the operator out after 10 minutes of no activity (mousemove / mousedown / click / keydown / touchstart / touchmove / scroll / wheel). Configurable at build time via `VITE_INACTIVITY_TIMEOUT_MS` (clamped to ≥ 60 s).
- **Page-position preservation on re-auth** (#204) — when an anonymous user gets bounced to /login, the router captures the URL they were trying to reach. On successful login they land back there instead of /home. Hostile-URL defence: only relative paths are honoured; `/login*` values are rejected to prevent loops.

### Multi-role authorization + role-label clarity

- **Multi-binding gate** (#206) — every study-config write endpoint (event-definitions, sites, CRF library, response sets, rules, study identity, study parameters, group classes, rule expressions) now walks the caller's full active-binding set instead of trusting the single session-attribute role. A user with both `Prüfarzt/-ärztin` and `Studienleitung` bindings on the same study no longer gets a non-deterministic 403 whenever the active-study picker happened to surface the Investigator binding. Bindings are loaded via `UserAccountDAO.findAllRolesByUserName`; any RuntimeException from the DAO fails closed (denies the write). 10 new unit tests pin the contract: sysadmin shortcuts pass, soft-deleted bindings don't leak, wrong-study bindings stay scoped.
- **Highest-privilege active-study picker** (#206) — `MeApiController.setActiveStudy` ranks the user's matching bindings (`ADMIN > STUDYDIRECTOR > COORDINATOR > INVESTIGATOR > RA > RA2 > MONITOR`) and seats the strongest into the session attribute so audit metadata + display always reflect the user's broadest scope on the chosen study. Auth decisions stay independent of this pick (they walk the full set).
- **Role-label correction** (#206) — the SPA's role display labels are realigned with what the underlying legacy role actually does. Old labels confused operators because `RoleMapper` collapses 7 legacy roles into 5 SPA labels by renaming: legacy `STUDYDIRECTOR` was shown as "Datenmanager/-in", legacy `COORDINATOR` as "CRC". Now the displayed labels read `Datenmanager/-in → Studienleitung` and `CRC → Koordinator/-in` (DE); `Data Manager → Study Director` and `CRC → Coordinator` (EN). The wire token stays `Data Manager` / `CRC` (RoleMapper unchanged), so persisted bindings + RoleMapper translation are untouched.
- **TopBar role surface** (#206) — every role the user holds on the active study is listed in the profile popover (one swatch + i18n label per role). The trigger area itself only carries the avatar + username; chips were removed after operator feedback that they crowded the breadcrumb. `manageUsers.role.*` i18n keys are now the single source of truth for the displayed name across the assignment modal, the TopBar popover, and the role-filter dropdowns.

### Bug-report dialog with PII redaction

- **Bug report email** (#187) — operators report bugs via a dialog in the user menu; the report emails to `LIBRECLINICA_BUG_REPORT_RECIPIENT` with a per-report ticket id.
- **PII redaction + page URL + console capture** (#189) — operators can optionally attach the current page URL + the last 50 console error/warn/uncaught-Vue messages. Subject labels (M-001 / GA-001 etc.), DOB-shaped tokens (ISO / German / US) and email-shaped tokens are redacted at capture time so what the operator sees in the preview is what the email carries.

### Audit-trail closure

- **§11.10(e) gap coverage** (#186) — closes 11 confirmed audit-logging gaps with new event types 63–74 (CRF library lifecycle, user-lifecycle, EventDefinition.create, DN threading).
- **Audit-helper unification** (#188) — consolidates the legacy `audit_event` writers into the canonical `audit_log_event` table via a 5-slice migration sweep. Adds event types 75–108 + DDE / retinal-inference / study-creation types 109–114.

### Production seed gate

- **Demo data gating** (#203) — production installs (`LIQUIBASE_CONTEXTS=!demo`, the deploy default) now ship with only the **root** user and institutional config (modality registry, audit event types, ophthalmology field catalog). Demo subjects, test users, and the v1.0 OPHTH Visit seed CRF are gated behind the `demo` context — they still apply in `docker compose` dev environments (which default to `LIQUIBASE_CONTEXTS=demo`) and in CI integration tests.

## Known issues

- **GA-001 Erstmessungen panel for BCVA_LOGMAR + LENS_STATUS** — the modality registry's alias seeding only covers the four measurement types the OPHTH v2.0 CRF carries (BCVA_ETDRS, IOP, REFRACTION_SPH, REFRACTION_CYL). The LogMAR and Lens-status rows surface "—" on GA-001's panel until a future CRF version adds them.
- **Compound refraction sub-fields** — the OPHTH v2.0 CRF ships three of the canonical four sub-fields (SPHERE / TORUS / ANGLE; no VISUS). The compound row renders all 4 canonical slots; the VISUS sub-input shows a dashed-border disabled placeholder so the operator sees the design's expected quartet. Adding a real VISUS item to v2.0 is tracked as a follow-up.
- **Refraction TORUS labelled "Zylinder"** — the v2.0 seed authored the cylinder sub-field as `REFRACTION_TORUS`. The hot-patch labels it as "Zylinder" but the OID retains the legacy `TORUS` token. Future v3.0 CRF should use `CYLINDER` directly.
- **Modality baselines query** — the alias-column approach is correct but doesn't yet expose the alias via the SPA's modality admin DTO. The admin view shows only the primary OID; aliases are seeded via Liquibase + visible in psql. A follow-up PR will surface alias editing in the Modality Admin UI.
- **Conditional-reason display state machine** — the active/inactive chrome for the OPHTH "Spectralis-OCT — Grund" input (and similar conditional-reason items) does NOT correctly track its parent _DONE item's value in any surface (runtime CRF entry, wizard preview, wizard section editor). Symptoms: the row renders stuck in the inactive grey state regardless of whether the parent reads Ja / Nein, and the hint placeholder reads "Aktiv, sobald „" gewählt ist" with the trigger token missing. The underlying data persists + validates correctly — only the visual three-state chrome (active-empty / active-filled / inactive) is broken. Operators can still type into the Grund input when needed; they just don't get the coral-border / disabled-grey affordance to guide them. Deferred to a follow-up.

## Operator notes

- **Idle timeout** — at 10 minutes of inactivity the session signs you out. If you were mid-CRF entry, the form's unsaved changes are preserved in memory ONLY for the current browser tab — sign back in within the same tab to recover them; closing the tab discards them.
- **Catalog picker** — when authoring a CRF, "+ Aus Katalog" gives you 12 pre-built ophthalmology fields. Bilateral entries (BCVA Letters, IOP, Refraction) drop OD + OS items as one click. Edit the auto-filled labels / OIDs in the per-item editor if your study wants something different.
- **Bug reports** — click your username → "Fehler melden". The dialog defaults to attaching the current page URL + console output (PII-scrubbed). The report emails to the institutional sysadmin inbox.

## Deployment notes

- **Database wipe for dev** — the `ophth-subject-eye` and `ophth-visit-crf-seed` changesets gained `context="demo"` attributes. Liquibase MD5-sums include `context`, so existing dev compose volumes will report a checksum mismatch on first restart. Run `docker compose down -v` once after pulling this tag to reset the volume + reapply cleanly. Production gets a fresh DB so this step doesn't apply.
- **Modality alias columns** — the migration runs as part of normal Liquibase startup; no operator action needed.
- **Compose env** — for production installs that should NOT seed demo data, leave `LIQUIBASE_CONTEXTS` unset (it defaults to `!demo`). For local dev `compose.yaml` sets `LIQUIBASE_CONTEXTS=demo` so the demo users + seed CRFs apply.
- **Bug-report email** — production sets `LIBRECLINICA_BUG_REPORT_RECIPIENT=<sysadmin-mailbox>` in `/etc/libreclinica/env` or the Tomcat `JAVA_OPTS`. Without it, the dialog surfaces "Bug-report recipient is not configured" and the report does not send.
- **Idle timeout override** — set `VITE_INACTIVITY_TIMEOUT_MS` at SPA build time to a value other than 600000 (10 min). Minimum honoured value is 60000 (1 min).

## Versioning

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring | 6.1.18 |
| Spring Security | 6.3.6 |
| Hibernate | 5.6.15 (jakarta-namespaced) |
| Liquibase | 3.6.3 |
| Tomcat | 10 (jakarta servlet 6) |
| Vue (SPA) | 3.5.35 |
| PostgreSQL | 13 / 14 |

## Beta testing checklist

- [ ] Open GA-001 → Erstmessungen panel surfaces BCVA letters + IOP values from the saved CRF entries.
- [ ] Open an Ophthalmology Visit CRF → OD/OS pairs render side-by-side; Spectralis "Nein" lights up the Grund input coral.
- [ ] Author a new CRF section → "+ Aus Katalog" → pick BCVA Snellen → verify both OD + OS items appear with the Snellen widget.
- [ ] Leave the SPA idle for 10 min on any page → auto-logout fires; sign back in → land on the same page.
- [ ] Report a bug → email arrives at the configured recipient with the page URL + redacted console entries.
- [ ] Run `LIQUIBASE_CONTEXTS=!demo` against a fresh DB → only `root` user is created; institutional config (modality registry, audit event types, ophth field catalog) is still seeded.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

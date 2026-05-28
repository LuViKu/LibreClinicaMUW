# Decision Record — Backend Modernization

This document captures the strategic decisions that frame the modernization project. New decisions are appended; previous decisions are not edited (record-of-truth).

---

## DR-001 — Adopt LibreClinica as MUW Ophthalmology eCRF

**Date:** 2026-05-28
**Status:** Accepted
**Owner:** Department of Ophthalmology and Optometry, MUW

**Context.** The department needs an eCRF platform for in-house clinical trials with full audit trail, multi-site capability, GCP/ICH-E6 alignment, and institutional control of code and data. Options considered: REDCap, Castor EDC, Medrio, OpenClinica Enterprise, LibreClinica.

**Decision.** Adopt LibreClinica (the open-source community successor of OpenClinica 3.14, currently maintained by ReliaTec GmbH) as the institutional eCRF.

**Consequences.**
- Institution owns the source and the operational stack
- LGPL license; institutional use does not trigger source-disclosure
- Existing capabilities (audit trail, double DE, SDV, e-signature, discrepancy notes, CDISC ODM, LDAP/AD, multi-site) align with department needs
- Onus is on the institution to (a) write the GCP / 21 CFR Part 11 validation plan, (b) operate the stack, (c) maintain the codebase

---

## DR-002 — Full backend re-platform, not incremental modernization

**Date:** 2026-05-28
**Status:** Accepted
**Owner:** Lead Developer (initial: Lukas Kuchernig)

**Context.** The upstream stack (Spring 5.1, Hibernate 5.4, Java 11, `javax.*` namespace, Tomcat 9, plus abandoned libraries: Castor, Prototype.js, GWT, iText 2.1.2, Apache POI 3.0.1) reached EOL or carries known CVEs. Four modernization scopes were considered:

1. Spring Boot 3 + Java 21 (full target, ~9–12 months)
2. Spring 6 + Java 21, keep XML config + WAR (smaller refactor, ~6–9 months)
3. Phase A hardening only — bump within Spring 5 line (~2–3 weeks)
4. Full re-platform including library replacement — Castor, iText, POI, Quartz, GWT, Prototype (~12–18 months)

**Decision.** Option 4 — **full re-platform including library replacement**, phased across Phase 0 → E as described in [MIGRATION.md](../../../MIGRATION.md).

**Consequences.**
- 12–18 month, 2–3 developer (FTE) commitment
- Eliminates the long tail of abandoned dependencies in one project rather than re-opening it later
- Modernization completes before first clinical use (DR-004), so validation overhead is paid once
- Cannot be paused mid-Phase-B without leaving the codebase in an unstable state — phase gates with go/no-go reviews are mandatory

---

## DR-003 — Hard fork from upstream ReliaTec/LibreClinica

**Date:** 2026-05-28
**Status:** Accepted

**Context.** Three postures were considered:

1. Stay aligned — track upstream, Phase A only, wait for upstream Jakarta migration (no public signal of one)
2. Coordinate — Phase A locally, engage ReliaTec on Spring 6 / Jakarta plans
3. Hard fork — accept manual upstream merges, full independence

**Decision.** Hard fork (option 3).

**Consequences.**
- Future upstream patches require manual cherry-pick + Eclipse Transformer translation post Phase B (see [MIGRATION.md § Upstream merge protocol](../../../MIGRATION.md#upstream-merge-protocol-hard-fork))
- Freedom to restructure Maven `groupId`, package names (Phase B), module boundaries, and visible branding without upstream constraint
- We own the entire test pyramid (no upstream regression net)
- LGPL compliance unchanged — internal MUW use does not trigger source disclosure

---

## DR-004 — Clinical use deferred until modernization completes

**Date:** 2026-05-28
**Status:** Accepted

**Context.** A pre-Phase-D clinical trial would force the modernization project to freeze around it (production on a mid-migration codebase). The user confirmed there is no current trial deadline.

**Decision.** No clinical trial onboarding until at least the end of Phase D. Phase E (UI modernization) may overlap with first clinical use, but only the high-traffic SPA screens are within scope; admin screens remain on JSP.

**Consequences.**
- Validation cycles can batch at phase exits rather than per dep bump
- Modernization team can refactor freely without coordinating around live trial data
- Trade-off: department waits ≥12 months for first eCRF-enabled trial. Acceptable given current state of the stack.

---

## DR-005 — MUW Ophthalmology branding applied

**Date:** 2026-05-28
**Status:** Accepted

**Context.** Hard fork (DR-003) makes institutional branding both legally permissible (LGPL) and operationally appropriate.

**Decision.**
- Maven `groupId`: `org.libreclinica` → **`at.ac.meduniwien.ophthalmology.libreclinica`**
- Project displayed name: **"LibreClinica MUW Ophthalmology"** with the strapline **"Department of Ophthalmology and Optometry · Medical University of Vienna"**
- LibreClinica & OpenClinica heritage retained in README acknowledgements and license
- Java package names (`org.akaza.openclinica`, `org.libreclinica`): **unchanged in Phase 0/A**. Renaming Java packages is Phase B work — done while every file is being touched for the Jakarta migration anyway, to avoid two cycles of merge breakage.

**Consequences.**
- Artifacts are clearly identified as institutional builds, not redistribution of upstream
- README acknowledgements section preserves heritage and license attribution
- Branded chrome (login page, page titles, redirect HTML) makes deployments unambiguous

---

## Future decisions (open)

- DR-006 — Castor replacement choice: Jakarta JAXB vs. Jackson XML (decide before Phase B)
- DR-007 — iText 2.1.2 replacement: OpenPDF vs. Apache PDFBox (decide before Phase D)
- DR-008 — UI framework for Phase E: React vs. Vue 3 vs. Svelte (decide before Phase E)
- DR-009 — Spring Authorization Server adoption (replaces deprecated Spring Security OAuth2 — decide during Phase B)
- DR-010 — Java package rename target: keep `org.akaza.openclinica` / `org.libreclinica` or migrate to `at.ac.meduniwien.ophthalmology.*` (decide before Phase B)

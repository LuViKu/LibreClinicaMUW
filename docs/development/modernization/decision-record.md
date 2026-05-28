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

## DR-006 — Castor replacement: Jakarta JAXB

**Date:** 2026-05-28
**Status:** Accepted (pre-flight ratification for Phase B.3)
**Companion analysis:** [docs/development/modernization/phase-b-dependency-analysis.md](phase-b-dependency-analysis.md) (Castor row); [docs/development/modernization/phase-b-execution-playbook.md § B.3](phase-b-execution-playbook.md)

**Context.** Castor 1.4.1 (2014) has no Jakarta-namespace variant and must be removed before Phase B can complete. The CDISC ODM 1.3 import/export paths (`ImportCRFDataServlet`, `ODMMetadataRestResource`, `MetaDataCollector`, `AdminDataCollector`, plus rule-engine XSLT executions) all currently use Castor's mapping-driven marshaller/unmarshaller and `XmlSchemaValidationHelper`. Three replacement options were considered:

| Option | Pros | Cons |
|--------|------|------|
| **Jakarta JAXB 4 (`jakarta.xml.bind` + `org.glassfish.jaxb:jaxb-runtime`)** | XSD-schema-validated; `xjc` already used in the `odm` module to generate JAXB classes from ODM 1.3 XSD; annotation-driven (no separate mapping file); the most canonical output for schema-defined XML; native to Jakarta EE 9+ | Steeper learning curve for ad-hoc XML; `XmlAdapter` needed for some date / decimal lexical preservation |
| Jackson XML (`jackson-dataformat-xml`) | Familiar (Jackson is already in the dep tree for JSON); easier for one-off bean → XML | Produces non-canonical XML (attribute ordering, default-namespace handling differs from JAXB); harder to keep byte-equivalent against an XSD-validated baseline; would still need a schema-validation step for ODM |
| EclipseLink MOXy | Drop-in JAXB implementation with extensions (oxm.xml mapping file, dynamic typing) | One more dependency to track; institutional team has no MOXy experience; benefits over plain JAXB are minor for our schema-locked use case |

**Decision.** **Jakarta JAXB 4** for the Castor → modern XML binding swap.

**Rationale.**
1. The `odm/` module already contains JAXB-generated classes from the ODM 1.3 XSD — half the work is done.
2. ODM 1.3 is schema-locked; canonical, schema-validated XML output is the requirement of every downstream consumer (regulators, biostatistics pipelines, partner sites). JAXB matches that contract; Jackson XML does not.
3. Byte-equivalence against pre-Phase-B Castor output (the [B.0 characterisation tests](phase-b-execution-playbook.md#b0--castor-characterisation-tests-pre-flight) regime) is achievable with JAXB + targeted `XmlAdapter`s where lexical formats differ.
4. Plain JAXB has the smallest dependency footprint (no MOXy install).

**Consequences.**
- Phase B.3 (Castor → JAXB swap) becomes the highest-risk sub-phase of Phase B per the playbook risk register (`RB1`). Must not start until B.0 characterisation tests are green on the current stack.
- The `OdmJaxbContext` bean recommended in the playbook is the wiring landing — a single Spring-managed `JAXBContext` cached for the lifetime of the application context (instantiation is expensive).
- `XmlSchemaValidationHelper` (Castor) → `jakarta.xml.validation.Validator` (one-line swap per call site).
- A `dependency-check-suppressions.xml` entry may be needed for transitive `commons-beanutils` 1.x pulled by Castor; verify after removal.
- Open follow-ups: lexical preservation of `dateTime` / `decimal` / `Boolean` values may need per-field `XmlAdapter`s. The B.0 characterisation suite is the harness for catching those.

**Revisit triggers.**
- If a B.0 characterisation test cannot be made byte-equivalent on JAXB output even with `XmlAdapter`s, this DR is amended in favour of Jackson XML or MOXy. The cost of that pivot is the work to date on JAXB context wiring (low — a few hours).
- If the `odm/` module's existing JAXB-generated classes are discovered to be incomplete (we have not audited every ODM 1.3 element used at this scale), the gap is filled by re-running `xjc` against the upstream XSD before the swap. Not a DR amendment.

---

## DR-010 — Java package rename to MUW namespace, during Phase B.11

**Date:** 2026-05-28
**Status:** Accepted (pre-flight ratification for Phase B.11)
**Companion analysis:** [docs/development/modernization/phase-b-execution-playbook.md § B.11](phase-b-execution-playbook.md)

**Context.** The heritage Java packages are `org.akaza.openclinica.*` (legacy OpenClinica) and `org.libreclinica.*` (post-2019 LibreClinica additions). Three options were considered:

1. **Rename now, during Phase 0/A** — minimal-effort while CI is light, but every Java file change cascades through cherry-picks from upstream ReliaTec, and every Phase B `javax → jakarta` Eclipse Transformer run also has to deal with the rename. Doubles the merge-conflict surface.
2. **Rename during Phase B.11** — every file is being touched anyway for `javax → jakarta`; bundle the package rename into the same touch. Single review cycle. Single upstream-divergence event.
3. **Defer indefinitely** — accept `org.akaza.openclinica.*` as a permanent institutional inheritance. Lowest immediate effort but ongoing identity confusion (institutional Maven `groupId` says `at.ac.meduniwien.ophthalmology.libreclinica` while Java packages say `org.akaza.openclinica`).

**Decision.** **Rename during Phase B.11** to `at.ac.meduniwien.ophthalmology.libreclinica.*`.

**Rationale.**
1. Per [DR-003](#dr-003--hard-fork-from-upstream-reliateclibreclinica), we are committed to a hard fork. Heritage package names no longer serve a "stay close to upstream for easier merges" purpose post Phase B.
2. Per [DR-005](#dr-005--muw-ophthalmology-branding-applied), institutional identity is a stated goal. Java packages that say `org.akaza.openclinica` undermine that goal in IDEs, in stack traces, in dependency-tree output, and in error messages copied into support tickets.
3. The single-most-expensive event in renaming Java packages is updating every Spring XML `<bean class="...">`, every JSP `<jsp:useBean class="...">`, every DBUnit `getTestDataFilePath()` (encodes package as path), every `component-scan base-package`, and every `web.xml` listener / filter class reference. Phase B.11 is when every one of those files is already being touched for the Jakarta migration anyway — there is no cheaper time.
4. The rename runs as an IntelliJ "structural search and replace" on the Phase B integration branch, immediately after the JSP taglib URI updates (B.7) land, and immediately before the reconciliation sweep (B.12). Branch name per playbook: `feature/phase-b-jakarta-cliff-package-rename` (off `feature/phase-b-jakarta-cliff`).

**Mapping.**

| Old prefix | New prefix | Notes |
|------------|-----------|-------|
| `org.akaza.openclinica.*` | `at.ac.meduniwien.ophthalmology.libreclinica.*` | Bulk |
| `org.libreclinica.*` (LibreClinica community additions) | `at.ac.meduniwien.ophthalmology.libreclinica.*` | Merge into the same MUW namespace |
| `org.akaza.openclinica.gwt.GwtMenu` | (removed per Phase D) | Do not rename; just delete during the GWT removal sub-phase |

**Consequences.**
- DBUnit `getTestDataFilePath()` in `HibernateOcDbTestCase` and `OcDbTestCase` derives the path from `getClass().getPackage().getName()`. After the rename, all `core/src/test/resources/org/akaza/openclinica/.../testdata/*.xml` files must move to `core/src/test/resources/at/ac/meduniwien/ophthalmology/libreclinica/.../testdata/*.xml`. Mechanical but voluminous.
- Spring XML `<bean class="..">` references (~70 distinct classes referenced by Spring) need rewriting.
- `web.xml` servlet classes (~295 entries) need rewriting.
- JSP `<jsp:useBean class="...">` and `<jsp:scriptlet>` directives across 413 JSPs need rewriting. Eclipse Transformer alone cannot do this since it targets `javax → jakarta`, not package renames; pair with an IntelliJ structural-replace run on the same branch.
- Liquibase changelog references: column-comment text strings that contain `org.akaza.openclinica` (e.g. in audit_log_event entries) are NOT rewritten — those are historical data, not class references.
- Logback `<logger name="...">` declarations in `logback.xml` must be rewritten.
- An entry in `upstream-merges.md` (the cherry-pick log per [DR-003](#dr-003--hard-fork-from-upstream-reliateclibreclinica)) documenting the rename event with the script used.

**Revisit triggers.**
- If during Phase B.11 the rename touches more than ~5000 files (Eclipse Transformer + structural-replace baseline), the cost-benefit shifts. Pause + reassess on a sub-phase go/no-go review.

---

## Future decisions (open)

- DR-007 — iText 2.1.2 replacement: OpenPDF vs. Apache PDFBox (decide before Phase D)
- DR-008 — UI framework for Phase E: React vs. Vue 3 vs. Svelte (decide before Phase E)
- DR-009 — Spring Authorization Server adoption (replaces deprecated Spring Security OAuth2 — decide during Phase B)
- DR-011 — Database connection pool: HikariCP vs. DBCP2 (recommend HikariCP; decide during Phase C)
- DR-012 — Date/time API: Joda-Time → `java.time` (recommend `java.time`; decide during Phase B)
- DR-013 — L2 cache: EhCache 3 vs. Caffeine + JCache (recommend Caffeine + JCache for Spring Boot 3 default; decide during Phase B)

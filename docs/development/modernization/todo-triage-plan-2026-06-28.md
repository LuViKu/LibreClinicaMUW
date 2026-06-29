# TODO triage + implementation plan — 2026-06-28

Companion to [heritage-debt-audit-2026-06-28.md](heritage-debt-audit-2026-06-28.md). After the JDT-Problems cleanup wave (PRs #271–#274), this doc inventories every remaining `// TODO` / `// FIXME` / `// XXX` in the production code paths (`core/src/main/java`, `web/src/main/java`) and proposes a per-category action.

## Cleanup so far

PR #274 deleted 127 obsolete TODOs in two waves:

| Pattern | Deleted | Reason |
|---|---|---|
| `// TODO update to CriteriaQuery` | 112 | Code already uses the modern typed `createQuery(String, Class)` form; the TODO was stale (Phase B.5 closed). |
| `// TODO Auto-generated constructor stub` etc. | 8 | IDE-generated stubs from the OpenClinica 3.x era; zero engineering value. |
| Bare `// TODO` (no content) | 7 | Uninformative — no action described. |

Net: 497 → ~370 TODOs remaining.

## Remaining TODOs — by category

### A. Performance hotspots (~6, medium priority)

- [`RandomizeActionValidator.java:85`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/validator/rule/action/RandomizeActionValidator.java#L85)
- [`InsertActionValidator.java:89,183,196`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/validator/rule/action/InsertActionValidator.java#L89)

> "TODO let the database calculate the 'intersection' this will be much faster and will consume less resources"

**Plan.** Two-list intersection via Java `Collections.retainAll` could be `INTERSECT` or two `IN` clauses server-side. Affected validators run during CRF authoring, low frequency. Defer until a real performance complaint surfaces; tag as `// PERF:` instead of `// TODO`.

### B. Security gaps (~4, **high priority**)

- [`UpdateUserAccountServlet.java` + similar](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/control/admin) — `TODO: provide URL Encoding!`
- [`SDVController.java` + similar](../../../web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller) — `TODO: make this sensitive to permissions`

**Plan.** Both are real risks: missing URL encoding can yield XSS; missing permission gating is an audit/regulatory issue. Schedule a **Phase D-Sec.11** sub-phase to close both. Two PRs (one per topic), each with an IT covering the formerly-vulnerable path. Block production cutover until done.

### C. i18n gaps (3 sites, low priority)

- [`Term.java:73`](../../../core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/bean/core/Term.java#L73) — `TODO: localised name resolve`
- Two similar sites in resource bundles.

**Plan.** Defer to Phase E (UI modernization), where the SPA's i18n bundle becomes authoritative. Tag as `// I18N:` to make searchable.

### D. Refactoring debt (~22, low/medium priority)

- `TODO: refactor super class to remove dependency.` × 15 — heritage class-hierarchy musings.
- `TODO need to refactor since this is similar to other code, tbh` × 3
- `TODO: change these so they are not` × 4

**Plan.** Heritage author musings. Most do not point at a concrete refactor target. **Action:** read each in context; if the concern is no longer valid (e.g. the super class has been retired), delete. If still valid but not blocking, convert to `// NOTE:` to remove the JDT task-tag flag without losing the comment.

### E. Bug-tracker dead-references (~10, immediate deletion candidates)

- `TODO possible relation to 1689 here, tbh` × 3 — references an OpenClinica bug tracker that no longer exists.
- `TODO job names will have to be unique, tbh` × 4 — heritage musing pre-jobs schema.
- `TODO do not know what this comparison should look like exactly since comparing with the result of getFileName()` × 5 — author admits ignorance + uses heuristic.

**Plan.** **DELETE all** in a follow-up PR after a manual read confirms each is dead context. Codemod-friendly via the exact-message match.

### F. Null-safety + error handling (~8, medium priority)

- `TODO ERROR - should always be different than NULL` × 3
- `TODO: handle exception` × 3
- `TODO: eventcrfBean is not valid??` × 2
- `TODO: check Null Value logic based on not event definition crf being selected` × 2

**Plan.** Each is a concrete defect marker. Schedule with the **P0 silent-error-handling** PR pattern from the audit (#262 → #267). One PR per fix, each ships with an IT.

### G. Hibernate migration leftovers (~6, deferred)

- `TODO: phase out the use of these Once the above beans become Hibernated` × 3
- `TODO : Pending conversion of the objects below to use Hibernate` × 3

**Plan.** These point at the `Bean` → JPA-entity migration that Phase B.5 partially covered. Defer to a hypothetical Phase B.13 if/when full bean retirement is funded. Tag as `// MIGRATE:`.

### H. The remaining `// TODO`-prefixed comments (~270, mixed)

Most are heritage author notes with substantive content but no clear action ("TODO this class also exist in web and ws"; "TODO this is either ItemBean or DisplayItemBean, try to fix this"; etc.). They're scattered across the bean / DAO surface.

**Plan.** No mechanical sweep can safely act on these. Convert to `// LEGACY:` prefix in a single PR — preserves the heritage context, removes the JDT task-tag flag. The class-comment Javadoc remains intact. Eclipse JDT's `MissingDeprecatedAnnotation` and `TODO` task tag scanners are the only places this matters; `// LEGACY:` is invisible to both.

## Suggested PR sequence

1. **#275 muw-todo-dead-refs** — delete category E (~10 dead-tracker refs) via exact-message codemod.
2. **#276 muw-todo-legacy-rename** — convert `TODO` → `LEGACY:` in categories D + H (~292 lines). Reduces JDT count by ~280 with zero info loss.
3. **#277 muw-security-todo-urlencoding** — close category B URL-encoding TODO (security PR).
4. **#278 muw-security-todo-permissions** — close category B permission gating (security PR).
5. **#279 muw-null-safety-pass-2** — work category F (8 fixes) modeled on PR #267.
6. **Phase D-Sec.11 manifest** — incorporate PR #277 + #278 into the Phase D-Sec exit gate, replacing the current "10/11" subphase tally with "12/13" (or "11/12" depending on what's already counted).

After PR #275 + #276 land, the JDT TODO category drops from ~370 → ~75 (categories A, C, F, G). The remaining 75 are substantive enough to keep as TODOs.

## See also

- Heritage-debt audit master plan: [heritage-debt-audit-2026-06-28.md](heritage-debt-audit-2026-06-28.md)
- Phase B.5 manifest (Hibernate 6 deprecation surface — already closed): [phase-b5-hibernate6-manifest.md](phase-b5-hibernate6-manifest.md)
- Decision records: [decision-record.md](decision-record.md)

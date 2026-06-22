# Phase B.4 — jmesa replacement scope analysis

> Captured 2026-05-29 evening when the B.4 Spring 6 + jakarta cliff (WIP at `feature/phase-b4-spring6-jakarta`) hit the jmesa wall.
> Inputs: `grep -rln "import org\.jmesa" web/src/main/java` (53 files), `find … -name '*.jsp' | xargs grep -l jmesa` (38 JSPs).
> Owner: Lukas Kuchernig. See [[jmesa_blocker]] auto-memory for the decision context. **User pick: replace jmesa entirely.**

This document is the **pre-flight inventory** for the jmesa replacement
work. Read it before running any sed sweeps or rewriting any
TableFactory class. The replacement is multi-day; the breakdown below
splits it into 6 reviewable PRs.

## Top-line numbers

| Surface | Count | Notes |
|---|---:|---|
| Java files importing `org.jmesa.*` | 53 | TableFactory + ViewExporter + servlet/controller + util classes |
| JSPs using `jmesa` | 38 | render the tables + page-level filter controls |
| Concrete `*TableFactory.java` classes | 14 | plus 1 abstract base (`AbstractTableFactory`) |
| Unique `org.jmesa.*` classes imported | 76 | API surface to replace |
| Custom `.tld` files | 1 | `jmesa.tld` ships with the lib — gone after replacement |
| `*ViewExporter` classes | 4 | CSV / XML / OC custom exporters |

## API surface (top 20 imports by usage)

```
25  org.jmesa.view.html.HtmlBuilder
20  org.jmesa.facade.TableFacade
15  org.jmesa.limit.Limit
14  org.jmesa.view.editor.CellEditor
14  org.jmesa.view.component.Row
14  org.jmesa.core.filter.MatcherKey
13  org.jmesa.core.filter.FilterMatcher
12  org.jmesa.view.html.editor.DroplistFilterEditor
11  org.jmesa.limit.SortSet / Sort / FilterSet / Filter
11  org.jmesa.core.CoreContext
 9  org.jmesa.view.html.toolbar.ToolbarItemType / AbstractItem
 8  org.jmesa.view.html.toolbar.ToolbarItemRenderer / ToolbarItem
 6  org.jmesa.view.editor.DateCellEditor
 6  org.jmesa.core.filter.DateFilterMatcher
 5  org.jmesa.view.html.HtmlSnippets
 5  org.jmesa.view.editor.BasicCellEditor
```

This is a **column/row builder + filter/sort/paginate engine + HTML
renderer + export adapter**. Every concern stays in the replacement;
only the API moves.

## The 14 concrete tables

Grouped by area for cohort-based replacement:

### Cohort 1 — admin statistics (4 tables, simplest)
- `web/.../control/admin/StudyStatisticsTableFactory.java`
- `web/.../control/admin/SiteStatisticsTableFactory.java`
- `web/.../control/admin/StudySubjectStatusStatisticsTableFactory.java`
- `web/.../control/admin/EventStatusStatisticsTableFactory.java`
- JSPs: `studymodule.jsp` etc.
- Why first: small fixed datasets (no pagination), aggregations only, low blast radius.

### Cohort 2 — list + search (3 tables)
- `web/.../control/admin/ListSubjectTableFactory.java`
- `web/.../control/admin/AuditUserLoginTableFactory.java`
- `web/.../control/submit/ListStudySubjectTableFactory.java`
- JSPs: `viewSubjectAggregate.jsp`, audit log JSP, study-subject list
- Why second: standard CRUD list pattern, biggest user-visible UI surface, validates the replacement against real usage.

### Cohort 3 — discrepancy / SDV / notes (4 tables)
- `web/.../control/submit/ListDiscNotesSubjectTableFactory.java`
- `web/.../control/submit/ListNotesTableFactory.java`
- `web/.../control/submit/ListDiscNotesForCRFTableFactory.java`
- `web/.../web/table/sdv/SubjectIdSDVFactory.java` (already in `web/table/sdv/`)
- JSPs: `viewSubjectSDV.jsp`, `viewAllSubjectSDV.jsp`, `viewAllSubjectSDVform.jsp`, `viewAllSubjectSDVtmp.jsp`, `viewSubjectAggregateSDV.jsp`, `viewDiscrepancyNote.jsp`, `listDNotesForCRF.jsp`
- Why third: clinical-data-critical, multi-state filtering, needs careful smoke test.

### Cohort 4 — study events + rule sets (3 tables)
- `web/.../control/managestudy/ListEventsForSubjectTableFactory.java`
- `web/.../control/managestudy/StudyAuditLogTableFactory.java`
- `web/.../control/submit/ViewRuleAssignmentTableFactory.java`
- JSPs: `listEventsForSubjects.jsp`, `listRuleSetsDesigner.jsp`, study audit log JSP
- Why fourth: event-scheduling-critical, integration tests cover this path (`StudyEventScheduleIT`).

### Cohort 5 — system tables (1 table)
- `web/.../web/table/scheduledjobs/ScheduledJobTableFactory.java`
- JSP: `listCurrentScheduledJobs.jsp`
- Why fifth: admin-only, isolated from clinical data flow.

### Cohort 6 — infrastructure cleanup
- `AbstractTableFactory.java` deleted
- `OCTableFacadeImpl.java`, `XmlViewExporter.java`, `OCCsvViewExporter.java`, `XmlView.java` deleted
- `jmesa.tld` deleted
- `jmesa-2.4.2-oc` dependency dropped from poms
- 38 JSPs cleaned up: remove `<%@ taglib uri="http://www.jmesa.org/" %>` declarations + any orphaned wrapper markup

## Proposed replacement stack

| Concern | Today (jmesa) | Replacement |
|---|---|---|
| Backend rendering | `TableFacade.createTableFacade(coid, request)` → server-side HTML | Spring MVC `@RestController` returning JSON; one endpoint per table |
| Column definitions | `Row` + `HtmlColumn` Java DSL | Hand-written `<th>` in JSP, columns + types declared in DataTables JS init |
| Sorting | jmesa `Sort` / `SortSet` server-side | DataTables server-side `order` array parsed in controller |
| Filtering | jmesa `Filter` / `MatcherKey` / `FilterMatcher` | DataTables column-search `search[value]` parsed in controller |
| Pagination | jmesa `Limit` (page-size + page-number) | DataTables `start` + `length` |
| Exports (CSV/XML) | jmesa `ViewExporter` | DataTables Buttons extension (CSV client-side), drop XML (Phase B noted as scheduled for removal anyway) |
| HTML rendering | `HtmlBuilder` Java | DataTables JS table init, hand-written empty `<table>` skeleton |
| Custom `.tld` tags | `jmesa.tld` | none — DataTables JS init replaces them |

**Recommended JS library:** [DataTables.net](https://datatables.net/) 2.x (already widely adopted, MIT-licensed, supports server-side processing protocol that maps cleanly to our existing pagination).

**Wiring decision:** server-side processing for cohorts 2–4 (large lists), client-side for cohort 1 (small fixed datasets) and cohort 5 (admin lists with rarely > 100 rows).

## Proposed PR breakdown

1. **jmesa PR 1** (this doc) — scope analysis, no code.
2. **jmesa PR 2** — Replacement primitives: `DataTableRequest` DTO (parses DataTables JSON request format), `DataTableResponse<T>` DTO (the protocol response shape), a Thymeleaf or JSP partial fragment for the empty `<table>` skeleton + DataTables init script, a base `AbstractDataTableController` for shared parsing/error handling. Two-page reference doc on how to add a new table.
3. **jmesa PR 3** — Replace cohort 1 (4 admin-stats tables). Smallest blast radius; validates the primitives end-to-end.
4. **jmesa PR 4** — Replace cohort 2 (3 list+search tables). Most-visible UI; validates filter/sort UX vs. jmesa.
5. **jmesa PR 5** — Replace cohorts 3 + 4 (7 clinical / rule tables). Highest-risk; integration tests gate (`StudyEventScheduleIT`, `SubjectEnrolmentIT`).
6. **jmesa PR 6** — Replace cohort 5 + delete infrastructure (`AbstractTableFactory`, the 4 ViewExporter classes, `jmesa.tld`); drop jmesa dep from poms; 38 JSPs cleaned of `jmesa` taglib declarations.

Each PR gates on `mvn -P integration-tests` green + a manual smoke
of the affected tables before merge.

## Risk areas

1. **Jmesa exports (CSV/XML).** The current XML export is consumed by
   integration tests + possibly downstream tooling. DataTables Buttons
   CSV is client-side — won't match XML byte-for-byte. **Action:** scan
   for callers of the XML export URL before PR 5 lands; if there's a
   non-test consumer, build a separate one-shot XML endpoint to
   preserve compat.
2. **Server-side filter UX differences.** jmesa's `DroplistFilterEditor`
   renders dropdown filters in the table header from server-rendered
   HTML. DataTables can do dropdowns via the `searchPanes` extension
   but the styling/markup will differ. **Action:** capture screenshots
   of every filtered table before PR 4, compare after. Some admin user
   muscle-memory will need an internal heads-up.
3. **Pagination state in URLs.** jmesa encodes filter/sort state in
   query params (`tableId_a_studyId=...`). Existing bookmarks /
   external links from emails / shared URLs will break with DataTables
   client-state. **Action:** PR 6 includes a redirect map for the most
   common legacy URLs or accepts the URL break as a one-time event.
4. **Column-level access control.** Some columns are hidden per role
   (institutional-data investigator vs. monitor). jmesa allowed
   server-side conditional column rendering. DataTables column
   visibility is client-side — easily bypassed by a curl request to
   the JSON endpoint. **Action:** server-side endpoint must enforce
   role-based field redaction in the JSON response, not rely on JS to
   hide columns.

## What this PR is NOT

- It is not the actual replacement work.
- It is not a commitment to DataTables — if the reviewer prefers a
  different stack (HTMX + server-rendered partials; Tabulator; Vaadin
  Crud; jQuery DataGrid; etc.) the cohort breakdown still applies, but
  the primitives PR (#2) needs the alternate stack's DTOs.

## Recommended next step

Reviewer reads this + the [decision record](decision-record.md), then
authorizes jmesa PR 2 (replacement primitives). Cohort PRs 3–6 follow
in order, each gating on tests + manual smoke.

The Spring 6 + jakarta cliff WIP (`feature/phase-b4-spring6-jakarta` @
`1fd6d7cef`) reopens once jmesa is gone — the sed sweep there is
preserved verbatim, just needs the cohort PRs in front of it before
it merges.

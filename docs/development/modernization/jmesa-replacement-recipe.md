# Adding a new DataTables-backed table — recipe

> Phase B.4 jmesa replacement reference doc, jmesa PR 2.
> Audience: any developer (or autonomous agent) replacing a jmesa
> `TableFactory` with the new stack.

## Two modes

The replacement stack supports both:

| Mode | When to use | Backend pattern |
|---|---|---|
| **Client-side** | Datasets ≤ 100 rows, no live updates | Render the data as a JSON literal in the page; DataTables filters/sorts/paginates in the browser |
| **Server-side** | Datasets > 100 rows, frequent updates, or sensitive fields that need row-level access checks | One `@RestController` endpoint returns the page; DataTables AJAX-polls on each filter/sort/page change |

Cohort 1 + cohort 5 (admin stats, scheduled jobs) use client-side.
Cohorts 2–4 (list+search, discrepancy, study events) use server-side.

## Building blocks

| Type | Location | Role |
|---|---|---|
| [`DataTableRequest`](../../../core/src/main/java/org/akaza/openclinica/web/datatable/DataTableRequest.java) | core | Parses a DataTables AJAX request (server-side mode only) |
| [`DataTableResponse<T>`](../../../core/src/main/java/org/akaza/openclinica/web/datatable/DataTableResponse.java) | core | The protocol response shape — `draw`, `recordsTotal`, `recordsFiltered`, `data` |
| [`include/datatable.jsp`](../../../web/src/main/webapp/WEB-INF/jsp/include/datatable.jsp) | web | The init partial — wires the DataTables JS to an empty `<table>` skeleton |
| [`includes/js/datatables/`](../../../web/src/main/webapp/includes/js/datatables/) | web | The vendored DataTables.net 2.x JS + CSS bundle |

## Client-side recipe

Use when the dataset is small + fixed (cohort 1, 5).

### 1. Build the row list in the servlet/controller

```java
List<Map<String, Object>> rows = new ArrayList<>();
for (StudyBean s : studyDao.findAll()) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name",       s.getName());
    row.put("enrolled",   studySubjectDao.getCountofStudySubjectsAtStudyOrSite(s));
    row.put("percentage", percentage(s));
    rows.add(row);
}
String dataJson = new ObjectMapper().writeValueAsString(rows);
request.setAttribute("datatable_idJson",      "\"#studyStatistics\"");
request.setAttribute("datatable_columnsJson", "["
        + "{\"data\":\"name\",\"title\":\"" + reswords.getString("study") + "\"},"
        + "{\"data\":\"enrolled\",\"title\":\"" + reswords.getString("enrolled") + "\"},"
        + "{\"data\":\"percentage\",\"title\":\"" + reswords.getString("percentage") + "\"}"
        + "]");
request.setAttribute("datatable_dataJson", dataJson);
```

### 2. JSP markup

```jsp
<table id="studyStatistics" class="datatable display"></table>
<%@ include file="/WEB-INF/jsp/include/datatable.jsp" %>
```

That's it — DataTables hydrates the `<table>` element on `DOMContentLoaded`.

## Server-side recipe

Use when the dataset is large or access-controlled (cohorts 2–4).

### 1. Build the JSON AJAX endpoint

```java
@RestController
@RequestMapping("/admin/auditUserLogin")
public class AuditUserLoginController {

    @Autowired private AuditUserLoginDao dao;

    @GetMapping("/data")
    public DataTableResponse<AuditUserLoginRow> data(HttpServletRequest req) {
        DataTableRequest r = DataTableRequest.from(req);
        long total    = dao.countAll();
        long filtered = dao.countFiltered(r.getGlobalSearch());
        List<AuditUserLoginRow> rows = dao.findPage(
                r.getStart(), r.getLength(),
                r.getSortColumnName(), r.getSortDirection(),
                r.getGlobalSearch());
        return DataTableResponse.success(r.getDraw(), total, filtered, rows);
    }
}
```

### 2. JSP markup

```jsp
<table id="auditUserLogin" class="datatable display"></table>
<c:set var="datatable_idJson"      value="\"#auditUserLogin\""/>
<c:set var="datatable_ajaxUrl"     value="\"${pageContext.request.contextPath}/admin/auditUserLogin/data\""/>
<c:set var="datatable_columnsJson" value="[...]"/>
<%@ include file="/WEB-INF/jsp/include/datatable.jsp" %>
```

When `datatable_ajaxUrl` is set, the partial enables `serverSide: true`
and ignores `datatable_dataJson`.

## Mandatory security checks (server-side mode)

DataTables column visibility is client-side and easily bypassed. The
server-side endpoint MUST:

- Enforce role-based field redaction in the JSON response. Do not
  rely on JS to hide columns from unauthorised users — strip the
  sensitive fields from each row server-side.
- Validate `length` upper bound (cap at e.g. 500) to prevent
  10-million-row DoS pulls.
- Use the column `data` key as a *whitelist* for sorting — do not
  pass it as raw SQL ORDER BY. Map known keys to known column names
  in the DAO method.

## Exports

DataTables Buttons (CSV/Excel/PDF) is client-side and only sees the
current page. For full-dataset exports, add a separate `@RequestMapping`
endpoint that streams the whole result set (no DataTables involvement).
Cohort 6 (jmesa PR 6) consolidates the existing jmesa CSV/XML
exporters into this pattern.

## What jmesa concepts map to what here

| jmesa | new stack |
|---|---|
| `TableFacade.createTableFacade(coid, request)` | `DataTableRequest.from(request)` (server-side only) |
| `Row.getColumn("x")` + `configureColumn` Java DSL | `datatable_columnsJson` request attribute |
| `Limit` (`startRow` / `endRow` / sort / filter) | `start`, `length`, `order[0][*]`, `search[value]` parsed by `DataTableRequest` |
| `setItems()` + `setTotalRows()` + `setMaxRows()` | `DataTableResponse.success(draw, recordsTotal, recordsFiltered, rows)` |
| `CellEditor` (e.g. `PercentageCellEditor`) | client-side `columnDefs[*].render` function in the columns JSON |
| `ViewExporter` (CSV/XML) | separate `@RequestMapping` endpoint that streams the export |
| `jmesa.tld` taglib | replaced by `include/datatable.jsp` + a static `<table>` skeleton |

## Forbidden patterns

- Don't generate HTML server-side and stuff it into a request
  attribute (`request.setAttribute("studyStatistics", tableHtml)`).
  That was the jmesa pattern; it tightly couples view to backend and
  blocks any client-side improvement later.
- Don't use the `data:` request attribute for server-side mode. The
  whole point of server-side mode is to *not* ship the full dataset
  on first render.
- Don't load DataTables JS from CDN — vendor it under
  `includes/js/datatables/`. Clinical-data deployments at MedUni Wien
  run behind a firewall that blocks egress to public CDNs.

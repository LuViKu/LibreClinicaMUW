# Vendor slot for a future client-side table library

> Phase B.4 jmesa replacement — historical artifact, currently unused.

The original PR #37 / #38 plan dropped a DataTables.net 2.x bundle
here and built a JSP partial (`WEB-INF/jsp/include/datatable.jsp`) to
init it. **It did not work** in practice — DataTables 2.x crashes
during init with `Cannot read properties of undefined (reading
'ariaTitle')` on every page that also loads LibreClinica's
`includes/prototype.js`. Prototype.js monkey-patches
`Element.prototype` in ways DataTables 2.x's column-metadata walk
doesn't tolerate. The bundle was removed; cohort 2a
(`AuditUserActivity`) ships with a vanilla-JS `fetch` + DOM render
pattern instead (see `WEB-INF/jsp/admin/auditUserActivity.jsp` for the
reference).

The Java side of the original plan stays:
[`DataTableRequest`](../../../core/src/main/java/org/akaza/openclinica/web/datatable/DataTableRequest.java)
parses the standard DataTables AJAX-protocol query params, and
[`DataTableResponse`](../../../core/src/main/java/org/akaza/openclinica/web/datatable/DataTableResponse.java)
emits the standard `{draw, recordsTotal, recordsFiltered, data}`
shape. Future cohorts can keep using those even if the client side
isn't DataTables.

## If a future cohort needs DataTables.net (or similar)

Likely options that won't conflict with Prototype.js:

- DataTables in an iframe (isolates the global pollution; ugly).
- Drop Prototype.js from the affected pages (large blast radius —
  many JSPs depend on its `$()` and `Form.serialize`).
- Use a different client lib that doesn't use jQuery (Tabulator,
  AG Grid, native `<table>` + a small render function — which is
  what cohort 2a does).

Whichever path, drop the chosen JS+CSS bundle here and update the
`<script>`/`<link>` references in the cohort JSP.

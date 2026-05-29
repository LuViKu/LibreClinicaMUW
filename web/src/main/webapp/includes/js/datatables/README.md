# DataTables.net 2.x vendor bundle

> Phase B.4 — jmesa replacement (jmesa PR 2 introduces the slot, jmesa PR 3 fills it).

This directory hosts the DataTables.net 2.x distribution. The JSP
partial at [`WEB-INF/jsp/include/datatable.jsp`](../../../WEB-INF/jsp/include/datatable.jsp)
references the following files via `${pageContext.request.contextPath}/includes/js/datatables/`:

- `datatables.min.css`
- `datatables.min.js`

## How to drop in the bundle

1. Open <https://datatables.net/download/>
2. Select: DataTables 2.x, plus the styling framework that matches the
   rest of the LibreClinica admin UI (use the **Default** styling pack
   unless the cohort PR cover sheet says otherwise).
3. Generate a single concatenated file pair (`datatables.min.css` +
   `datatables.min.js`); place both here.
4. Commit them — they are tracked in git like any other vendor asset
   (see the existing `includes/js/jquery/` precedent).

## Why not load from CDN

Clinical-data deployments at MedUni Wien run behind a firewall that
blocks egress to public CDNs. The bundle must be served from the same
host as the app.

## Cohort 1 (admin statistics) usage

The four small admin-stats tables that ship in jmesa PR 3 will use
client-side mode — DataTables loads the full row set on first render.
No AJAX endpoint required.

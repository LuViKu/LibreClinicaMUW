<%--
  LibreClinica is distributed under the
  GNU Lesser General Public License (GNU LGPL).
  copyright (C) 2026 Department of Ophthalmology and Optometry,
                     Medical University of Vienna

  DataTables.net 2.x init partial — Phase B.4 jmesa replacement.

  Inputs (request attributes):
    - datatable.id        : DOM id of the <table> element (required)
    - datatable.columns   : JSON array of column descriptors, each
                            {"data": "fieldName", "title": "Header"};
                            (required)
    - datatable.data      : JSON array of row objects keyed by column 'data'
                            (required for client-side mode)
    - datatable.ajaxUrl   : URL of the server-side endpoint
                            (required for server-side mode; if set,
                             datatable.data is ignored)
    - datatable.pageLength: optional, defaults to 25
    - datatable.options   : optional JSON object literal, merged into the
                            DataTables init object (for table-specific
                            extensions: orderFixed, columnDefs, ...)

  Caller must <%@ include file="..." %> this partial INSIDE the <body>,
  AFTER the empty <table id="${datatable.id}"></table> skeleton.

  The DataTables.net 2.x JS + CSS bundle must be available at
  /includes/js/datatables/ (vendored in jmesa PR 3 alongside cohort 1).
--%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<link rel="stylesheet" type="text/css"
      href="${pageContext.request.contextPath}/includes/js/datatables/datatables.min.css"/>
<script type="text/javascript"
        src="${pageContext.request.contextPath}/includes/js/datatables/datatables.min.js"></script>
<script type="text/javascript">
  (function() {
    var $tableId = ${empty datatable_idJson ? '"#datatable"' : datatable_idJson};
    var config = {
      columns: ${datatable_columnsJson},
      pageLength: ${empty datatable_pageLength ? 25 : datatable_pageLength},
      lengthMenu: [10, 25, 50, 100],
      stateSave: true,
      autoWidth: false,
      language: { search: '' }
    };
    <c:if test="${not empty datatable_ajaxUrl}">
      config.serverSide = true;
      config.processing = true;
      config.ajax = ${datatable_ajaxUrl};
    </c:if>
    <c:if test="${empty datatable_ajaxUrl}">
      config.data = ${datatable_dataJson};
    </c:if>
    <c:if test="${not empty datatable_options}">
      var extra = ${datatable_options};
      for (var k in extra) { if (extra.hasOwnProperty(k)) { config[k] = extra[k]; } }
    </c:if>
    document.addEventListener('DOMContentLoaded', function() {
      new DataTable($tableId, config);
    });
  })();
</script>

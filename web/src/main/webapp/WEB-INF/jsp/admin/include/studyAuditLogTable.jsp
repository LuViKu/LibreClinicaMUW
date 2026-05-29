<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>

<%-- Phase B.4 jmesa PR 6a (cohort 4a): study audit-log subject table
     rendered via vanilla-JS fetch + DOM render against /StudyAuditLogData.
     Pure 1-to-1 row layout, 8 static columns, view-only actions. --%>
<div id="studyAuditLogWrap">
    <table id="studyAuditLogTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="studyAuditLogHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" colspan="8" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="studyAuditLogBody">
            <tr><td colspan="8" style="text-align:center; padding:8px;">
                <fmt:message key="loading" bundle="${resword}"/>&hellip;
            </td></tr>
        </tbody>
    </table>
</div>

<script type="text/javascript">
(function () {
    var ctx = '${pageContext.request.contextPath}';
    var iLabel = {
        view:   '<fmt:message key="view" bundle="${resword}"/>',
        noData: '<fmt:message key="no_data" bundle="${resword}"/>'
    };

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function actionsCell(row) {
        var acts = row.availableActions || [];
        var id = encodeURIComponent(row.id || '');
        var html = '';
        for (var i = 0; i < acts.length; i++) {
            if (acts[i] === 'view') {
                html += '<a href="' + ctx + '/ViewStudySubjectAuditLog?id=' + id + '"'
                      + ' title="' + esc(iLabel.view) + '">'
                      + '<img src="images/bt_View.gif" border="0" alt="'
                      + esc(iLabel.view) + '" hspace="4"/></a>';
            }
        }
        return html;
    }

    function renderHead(columns) {
        var head = document.getElementById('studyAuditLogHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('studyAuditLogBody');
        if (!rows || rows.length === 0) {
            body.innerHTML = '<tr><td colspan="' + columns.length + '" style="text-align:center; padding:8px;">'
                + esc(iLabel.noData) + '</td></tr>';
            return;
        }
        var html = '';
        for (var r = 0; r < rows.length; r++) {
            var row = rows[r];
            html += '<tr>';
            for (var c = 0; c < columns.length; c++) {
                var col = columns[c];
                if (col.type === 'actions') {
                    html += '<td>' + actionsCell(row) + '</td>';
                } else if (col.key === 'studySubject.label') {
                    var id = encodeURIComponent(row.id || '');
                    html += '<td><a href="' + ctx + '/ViewStudySubjectAuditLog?id=' + id + '">'
                          + esc(row[col.key]) + '</a></td>';
                } else {
                    html += '<td>' + esc(row[col.key]) + '</td>';
                }
            }
            html += '</tr>';
        }
        body.innerHTML = html;
    }

    function load() {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', ctx + '/StudyAuditLogData?draw=1&start=0&length=500', true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('studyAuditLogBody');
            if (xhr.status !== 200) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error loading audit log: HTTP ' + xhr.status + '</td></tr>';
                return;
            }
            var payload;
            try { payload = JSON.parse(xhr.responseText); }
            catch (e) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error parsing audit-log JSON</td></tr>';
                return;
            }
            var columns = (payload && payload.columns) || [];
            var data    = (payload && payload.data) || [];
            renderHead(columns);
            renderBody(columns, data);
        };
        xhr.onerror = function () {
            document.getElementById('studyAuditLogBody').innerHTML =
                '<tr><td style="color:red; text-align:center; padding:8px;">Network error loading audit log</td></tr>';
        };
        xhr.send();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', load);
    } else {
        load();
    }
})();
</script>

<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>

<%-- Phase B.4 jmesa PR 5a (cohort 3a): notes table rendered via
     vanilla-JS fetch + DOM render against /ViewNotesData. Same
     pattern as cohorts 2a / 2b / 2c — kept off DataTables.net
     because of the prototype.js incompatibility documented in
     includes/js/datatables/README.md. --%>
<div id="viewNotesTableWrap">
    <table id="viewNotesTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="viewNotesTableHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="viewNotesTableBody">
            <tr><td style="text-align:center; padding:8px;">
                <fmt:message key="loading" bundle="${resword}"/>&hellip;
            </td></tr>
        </tbody>
    </table>
</div>

<script type="text/javascript">
(function () {
    var ctx = '${pageContext.request.contextPath}';
    var iLabel = {
        view:    '<fmt:message key="view" bundle="${resword}"/>',
        resolve: '<fmt:message key="view_within_crf" bundle="${resword}"/>',
        noData:  '<fmt:message key="no_data" bundle="${resword}"/>'
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
            switch (acts[i]) {
                case 'view':
                    html += '<a href="javascript:openDNWindow(\'' + ctx
                        + '/CreateDiscrepancyNote?noteId=' + id + '&viewAction=1\');"'
                        + ' title="' + esc(iLabel.view) + '">'
                        + '<img src="images/bt_View_d.gif" border="0" alt="'
                        + esc(iLabel.view) + '" hspace="4"/></a>';
                    break;
                case 'resolve':
                    html += '<a href="' + ctx + '/ResolveDiscrepancy?noteId=' + id + '"'
                        + ' title="' + esc(iLabel.resolve) + '">'
                        + '<img src="images/bt_Reassign_d.gif" border="0" alt="'
                        + esc(iLabel.resolve) + '" hspace="4"/></a>';
                    break;
                default:
                    break;
            }
        }
        return html;
    }

    function resolutionCell(cell) {
        if (!cell) return '';
        var icon = cell.iconFilePath
            ? '<img src="' + esc(cell.iconFilePath) + '" border="0" align="left"/>&nbsp;&nbsp;'
            : '';
        return icon + esc(cell.name);
    }

    function renderHead(columns) {
        var head = document.getElementById('viewNotesTableHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('viewNotesTableBody');
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
                var key = col.key;
                if (col.type === 'actions') {
                    html += '<td>' + actionsCell(row) + '</td>';
                } else if (col.type === 'resolutionStatus') {
                    html += '<td>' + resolutionCell(row[key]) + '</td>';
                } else {
                    html += '<td>' + esc(row[key]) + '</td>';
                }
            }
            html += '</tr>';
        }
        body.innerHTML = html;
    }

    function load() {
        var xhr = new XMLHttpRequest();
        // Pass through the module so the data servlet sees the same
        // context the legacy factory did.
        var moduleParam = '${param.module != null ? param.module : "manage"}';
        xhr.open('GET', ctx + '/ViewNotesData?draw=1&start=0&length=500&module='
            + encodeURIComponent(moduleParam), true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('viewNotesTableBody');
            if (xhr.status !== 200) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error loading notes: HTTP ' + xhr.status + '</td></tr>';
                return;
            }
            var payload;
            try { payload = JSON.parse(xhr.responseText); }
            catch (e) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error parsing notes JSON</td></tr>';
                return;
            }
            var columns = (payload && payload.columns) || [];
            var data    = (payload && payload.data) || [];
            renderHead(columns);
            renderBody(columns, data);
        };
        xhr.onerror = function () {
            document.getElementById('viewNotesTableBody').innerHTML =
                '<tr><td style="color:red; text-align:center; padding:8px;">Network error loading notes</td></tr>';
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

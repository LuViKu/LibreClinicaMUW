<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>

<%-- Phase B.4 jmesa PR 4c: study-subject matrix rendered via
     vanilla-JS fetch + DOM render against /FindSubjectsData. The
     response carries both a `columns` metadata block AND a `data`
     array, since the column set depends on the active study's
     dynamic group classes + event definitions. Same incompatibility
     with includes/prototype.js as cohort 2a/2b — DataTables.net is
     intentionally NOT used here. --%>
<div id="findSubjectsTableWrap">
    <table id="findSubjectsTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="findSubjectsTableHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="findSubjectsTableBody">
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
        view:        '<fmt:message key="view" bundle="${resword}"/>',
        edit:        '<fmt:message key="edit" bundle="${resword}"/>',
        remove:      '<fmt:message key="remove" bundle="${resword}"/>',
        restore:     '<fmt:message key="restore" bundle="${resword}"/>',
        reassign:    '<fmt:message key="reassign" bundle="${resword}"/>',
        sign:        '<fmt:message key="sign" bundle="${resword}"/>',
        noData:      '<fmt:message key="no_data" bundle="${resword}"/>'
    };

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function actionHtml(name, row) {
        var id = encodeURIComponent(row.id);
        switch (name) {
            case 'view':
                return '<a href="' + ctx + '/ViewStudySubject?action=view&id=' + id + '"'
                    + ' title="' + esc(iLabel.view) + '">'
                    + '<img src="images/bt_View.gif" border="0" alt="' + esc(iLabel.view) + '" hspace="4"/></a>';
            case 'edit':
                return '<a href="' + ctx + '/UpdateStudySubject?action=show&id=' + id + '"'
                    + ' title="' + esc(iLabel.edit) + '">'
                    + '<img src="images/bt_Edit.gif" border="0" alt="' + esc(iLabel.edit) + '" hspace="4"/></a>';
            case 'remove':
                return '<a href="' + ctx + '/RemoveStudySubject?action=confirm&id=' + id + '"'
                    + ' title="' + esc(iLabel.remove) + '">'
                    + '<img src="images/bt_Remove.gif" border="0" alt="' + esc(iLabel.remove) + '" hspace="4"/></a>';
            case 'restore':
                return '<a href="' + ctx + '/RestoreStudySubject?action=confirm&id=' + id + '"'
                    + ' title="' + esc(iLabel.restore) + '">'
                    + '<img src="images/bt_Restore.gif" border="0" alt="' + esc(iLabel.restore) + '" hspace="4"/></a>';
            case 'reassign':
                return '<a href="' + ctx + '/ReassignStudySubject?action=confirm&id=' + id + '"'
                    + ' title="' + esc(iLabel.reassign) + '">'
                    + '<img src="images/bt_Reassign.gif" border="0" alt="' + esc(iLabel.reassign) + '" hspace="4"/></a>';
            case 'sign':
                return '<a href="' + ctx + '/SignStudySubject?action=confirm&id=' + id + '"'
                    + ' title="' + esc(iLabel.sign) + '">'
                    + '<img src="images/bt_Sign.gif" border="0" alt="' + esc(iLabel.sign) + '" hspace="4"/></a>';
            default:
                return '';
        }
    }

    function actionsCell(row) {
        var acts = row.availableActions || [];
        var html = '';
        for (var i = 0; i < acts.length; i++) html += actionHtml(acts[i], row);
        return html;
    }

    // Event-status icon paths mirror what the legacy
    // ListStudySubjectTableFactory rendered: status 1..8 → an icon.
    var statusIcon = {
        1: 'icon_NotScheduled.gif',
        2: 'icon_Scheduled.gif',
        3: 'icon_NotStarted.gif',
        4: 'icon_InitialDE.gif',
        5: 'icon_DEcomplete.gif',
        6: 'icon_Stopped.gif',
        7: 'icon_Skipped.gif',
        8: 'icon_Locked.gif'
    };

    function eventCell(cell) {
        if (!cell) return '';
        var icon = statusIcon[cell.statusId] || 'icon_NotScheduled.gif';
        var alt = esc(cell.statusName || '');
        var count = cell.count > 1 ? (' &times;' + cell.count) : '';
        return '<img src="images/' + icon + '" border="0" alt="' + alt + '" title="' + alt + '"/>' + count;
    }

    function renderHead(columns) {
        var head = document.getElementById('findSubjectsTableHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('findSubjectsTableBody');
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
                } else if (col.type === 'event') {
                    html += '<td>' + eventCell(row[key]) + '</td>';
                } else if (key === 'studySubject.label') {
                    var id = encodeURIComponent(row.id || '');
                    html += '<td><a href="' + ctx + '/ViewStudySubject?action=view&id=' + id + '">'
                        + esc(row[key]) + '</a></td>';
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
        xhr.open('GET', ctx + '/FindSubjectsData?draw=1&start=0&length=500', true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('findSubjectsTableBody');
            if (xhr.status !== 200) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error loading subject matrix: HTTP ' + xhr.status + '</td></tr>';
                return;
            }
            var payload;
            try { payload = JSON.parse(xhr.responseText); }
            catch (e) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error parsing subject matrix JSON</td></tr>';
                return;
            }
            var columns = (payload && payload.columns) || [];
            var data    = (payload && payload.data) || [];
            renderHead(columns);
            renderBody(columns, data);
        };
        xhr.onerror = function () {
            document.getElementById('findSubjectsTableBody').innerHTML =
                '<tr><td style="color:red; text-align:center; padding:8px;">Network error loading subject matrix</td></tr>';
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

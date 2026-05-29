<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>

<%-- Phase B.4 jmesa PR 6b (cohort 4b): events-for-subjects matrix
     rendered via vanilla-JS fetch + DOM render against
     /ListEventsForSubjectsData. Static columns + dynamic
     sgc_<groupClassId> + dynamic crf_<crfId> (per selected event
     definition) + actions. Each row carries an events[] array; the
     JS renderer iterates inside the event-status / event-date / CRF
     cells, mirroring the legacy cell editors' multi-event HTML. --%>
<div id="listEventsForSubjectsWrap">
    <table id="listEventsForSubjectsTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="listEventsForSubjectsHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="listEventsForSubjectsBody">
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
        view:     '<fmt:message key="view" bundle="${resword}"/>',
        edit:     '<fmt:message key="edit" bundle="${resword}"/>',
        remove:   '<fmt:message key="remove" bundle="${resword}"/>',
        restore:  '<fmt:message key="restore" bundle="${resword}"/>',
        reassign: '<fmt:message key="reassign" bundle="${resword}"/>',
        noData:   '<fmt:message key="no_data" bundle="${resword}"/>'
    };

    var eventStatusIcon = {
        1: 'icon_Scheduled.gif',
        2: 'icon_NotStarted.gif',
        3: 'icon_InitialDE.gif',
        4: 'icon_DEcomplete.gif',
        5: 'icon_Stopped.gif',
        6: 'icon_Skipped.gif',
        7: 'icon_Locked.gif',
        8: 'icon_Signed.gif'
    };

    var crfStageIcon = {
        0: 'icon_Invalid.gif',
        1: 'icon_NotStarted.gif',
        2: 'icon_InitialDE.gif',
        3: 'icon_DEcomplete.gif',
        4: 'icon_DDE.gif',
        5: 'icon_DEcomplete.gif',
        6: 'icon_DEcomplete.gif',
        7: 'icon_Locked.gif'
    };

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function eventStatusCell(events) {
        if (!events || !events.length) return '';
        var html = '';
        for (var i = 0; i < events.length; i++) {
            var icon = eventStatusIcon[events[i].statusId] || 'icon_NotScheduled.gif';
            var alt = esc(events[i].statusName || '');
            html += '<table><tr><td><img src="images/' + icon + '" border="0" alt="' + alt
                  + '" title="' + alt + '"/></td></tr></table>';
        }
        return html;
    }

    function eventDateCell(events) {
        if (!events || !events.length) return '';
        var html = '';
        for (var i = 0; i < events.length; i++) {
            html += '<table border="0" cellpadding="0" cellspacing="0">'
                  + '<tr valign="top"><td>' + esc(events[i].startDate || '') + '</td></tr></table>';
        }
        return html;
    }

    function eventCrfCell(events, crfKey) {
        if (!events || !events.length) return '';
        var html = '';
        for (var i = 0; i < events.length; i++) {
            var crfCells = events[i].crfs || {};
            var cell = crfCells[crfKey];
            var stageId = cell ? cell.stageId : 1;
            var icon = crfStageIcon[stageId] || 'icon_NotStarted.gif';
            html += '<table><tr><td><img src="images/' + icon + '" border="0"/></td></tr></table>';
        }
        return html;
    }

    function actionsCell(row) {
        var acts = row.availableActions || [];
        var id = encodeURIComponent(row.id || '');
        var html = '';
        for (var i = 0; i < acts.length; i++) {
            switch (acts[i]) {
                case 'view':
                    html += '<a href="' + ctx + '/ViewStudySubject?action=view&id=' + id + '"'
                          + ' title="' + esc(iLabel.view) + '">'
                          + '<img src="images/bt_View.gif" border="0" alt="' + esc(iLabel.view) + '" hspace="4"/></a>';
                    break;
                case 'remove':
                    html += '<a href="' + ctx + '/RemoveStudySubject?action=confirm&id=' + id + '"'
                          + ' title="' + esc(iLabel.remove) + '">'
                          + '<img src="images/bt_Remove.gif" border="0" alt="' + esc(iLabel.remove) + '" hspace="4"/></a>';
                    break;
                case 'restore':
                    html += '<a href="' + ctx + '/RestoreStudySubject?action=confirm&id=' + id + '"'
                          + ' title="' + esc(iLabel.restore) + '">'
                          + '<img src="images/bt_Restore.gif" border="0" alt="' + esc(iLabel.restore) + '" hspace="4"/></a>';
                    break;
                case 'reassign':
                    html += '<a href="' + ctx + '/ReassignStudySubject?action=confirm&id=' + id + '"'
                          + ' title="' + esc(iLabel.reassign) + '">'
                          + '<img src="images/bt_Reassign.gif" border="0" alt="' + esc(iLabel.reassign) + '" hspace="4"/></a>';
                    break;
                default:
                    break;
            }
        }
        return html;
    }

    function renderHead(columns) {
        var head = document.getElementById('listEventsForSubjectsHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('listEventsForSubjectsBody');
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
                } else if (col.type === 'eventStatus') {
                    html += '<td>' + eventStatusCell(row.events) + '</td>';
                } else if (col.type === 'eventDate') {
                    html += '<td>' + eventDateCell(row.events) + '</td>';
                } else if (col.type === 'eventCrf') {
                    html += '<td>' + eventCrfCell(row.events, col.key) + '</td>';
                } else if (col.key === 'studySubject.label') {
                    var id = encodeURIComponent(row.id || '');
                    html += '<td><a href="' + ctx + '/ViewStudySubject?action=view&id=' + id + '">'
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
        var qs = window.location.search.replace(/^\?/, '');
        var url = ctx + '/ListEventsForSubjectsData?draw=1&start=0&length=500';
        if (qs.length > 0) url += '&' + qs;
        xhr.open('GET', url, true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('listEventsForSubjectsBody');
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
            document.getElementById('listEventsForSubjectsBody').innerHTML =
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

<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="org.akaza.openclinica.i18n.terms" var="resterm"/>

<%-- Phase B.4 jmesa PR 5c (cohort 3c): per-event-CRF discrepancy-note
     matrix rendered via vanilla-JS fetch + DOM render against
     /ListDiscNotesForCRFData. Each row represents one study subject;
     the event-status / event-date / per-CRF cells iterate over the
     row's `events[]` array and render one sub-table per event. --%>
<div id="listDiscNotesForCRFWrap">
    <table id="listDiscNotesForCRFTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="listDiscNotesForCRFHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="listDiscNotesForCRFBody">
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
        download: '<fmt:message key="download_discrepancy_notes" bundle="${resword}"/>',
        noData:   '<fmt:message key="no_data" bundle="${resword}"/>'
    };

    // Per-event status icon (subject_event_status_id 1..8) — mirrors the
    // legacy ListDiscNotesForCRFTableFactory.imageIconPaths map.
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

    // CRF data-entry stage icon (0..7) — legacy crfColumnImageIconPaths.
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

    var discNoteStatusIcon = {
        1: { src: 'icon_Note.gif',       alt: '<fmt:message key="Open" bundle="${resterm}"/>' },
        2: { src: 'icon_flagYellow.gif', alt: '<fmt:message key="Updated" bundle="${resterm}"/>' },
        3: { src: 'icon_flagGreen.gif',  alt: '<fmt:message key="Resolved" bundle="${resterm}"/>' },
        4: { src: 'icon_flagBlack.gif',  alt: '<fmt:message key="Closed" bundle="${resterm}"/>' },
        5: { src: 'icon_flagWhite.gif',  alt: '<fmt:message key="Not_Applicable" bundle="${resterm}"/>' }
    };

    var payloadDefId         = null;
    var payloadModule        = 'manage';
    var payloadDiscNoteType  = -1;
    var payloadResolution    = -1;

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function eventStatusCell(events) {
        if (!events || !events.length) return '';
        var html = '';
        for (var i = 0; i < events.length; i++) {
            var e = events[i];
            var icon = eventStatusIcon[e.statusId] || 'icon_NotScheduled.gif';
            var alt = esc(e.statusName || '');
            html += '<table><tr><td><img src="images/' + icon
                  + '" border="0" alt="' + alt + '" title="' + alt
                  + '" style="position: relative; left: 7px;"/></td></tr></table>';
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
            if (!cell) {
                html += '<table><tr><td></td></tr></table>';
                continue;
            }
            var stageIcon = crfStageIcon[cell.stageId] || 'icon_NotStarted.gif';
            html += '<table><tr><td>'
                  + '<img src="images/' + stageIcon + '" border="0"/>';
            var dc = cell.discCounts || {};
            var dcKeys = Object.keys(dc);
            for (var k = 0; k < dcKeys.length; k++) {
                var sId = dcKeys[k];
                var meta = discNoteStatusIcon[sId];
                if (!meta) continue;
                html += '<img name="dn_' + sId + '" src="images/' + meta.src
                      + '" border="0" alt="' + esc(meta.alt) + '" title="'
                      + esc(meta.alt) + '"/>(' + dc[sId] + ') ';
            }
            html += '</td></tr></table>';
        }
        return html;
    }

    function actionsCell(row) {
        var acts = row.availableActions || [];
        var id = encodeURIComponent(row.id || '');
        var resQuery = (payloadResolution >= 1 && payloadResolution <= 5)
            ? '&resolutionStatus=' + payloadResolution : '';
        var typeQuery = '&discNoteType=' + payloadDiscNoteType;
        var html = '';
        for (var i = 0; i < acts.length; i++) {
            switch (acts[i]) {
                case 'view':
                    html += '<a href="' + ctx + '/ViewNotes?viewForOne=y&id=' + id
                          + resQuery + typeQuery
                          + '&module=' + encodeURIComponent(payloadModule) + '"'
                          + ' title="' + esc(iLabel.view) + '">'
                          + '<img src="images/bt_View.gif" border="0" alt="'
                          + esc(iLabel.view) + '" hspace="4"/></a>';
                    break;
                case 'download':
                    var dlExtra = (payloadResolution >= 1 && payloadResolution <= 5)
                        ? '&resolutionStatus=' + payloadResolution
                        : '&module=' + encodeURIComponent(payloadModule);
                    html += '<a href="javascript:openDocWindow(\'' + ctx
                          + '/ChooseDownloadFormat?subjectId=' + id
                          + typeQuery + dlExtra + '\');"'
                          + ' title="' + esc(iLabel.download) + '">'
                          + '<img src="images/bt_Download.gif" border="0" alt="'
                          + esc(iLabel.download) + '" hspace="4"/></a>';
                    break;
                default:
                    break;
            }
        }
        return html;
    }

    function renderHead(columns) {
        var head = document.getElementById('listDiscNotesForCRFHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('listDiscNotesForCRFBody');
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
        var url = ctx + '/ListDiscNotesForCRFData?draw=1&start=0&length=500';
        if (qs.length > 0) url += '&' + qs;
        xhr.open('GET', url, true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('listDiscNotesForCRFBody');
            if (xhr.status !== 200) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error loading discrepancy notes: HTTP ' + xhr.status + '</td></tr>';
                return;
            }
            var payload;
            try { payload = JSON.parse(xhr.responseText); }
            catch (e) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error parsing discrepancy-note JSON</td></tr>';
                return;
            }
            payloadDefId        = payload.defId;
            payloadModule       = payload.module || 'manage';
            payloadDiscNoteType = (typeof payload.discNoteType === 'number') ? payload.discNoteType : -1;
            payloadResolution   = (typeof payload.resolutionStatus === 'number') ? payload.resolutionStatus : -1;
            var columns = (payload && payload.columns) || [];
            var data    = (payload && payload.data) || [];
            renderHead(columns);
            renderBody(columns, data);
        };
        xhr.onerror = function () {
            document.getElementById('listDiscNotesForCRFBody').innerHTML =
                '<tr><td style="color:red; text-align:center; padding:8px;">Network error loading discrepancy notes</td></tr>';
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

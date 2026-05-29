<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="org.akaza.openclinica.i18n.page_messages" var="resmessages"/>

<%-- Phase B.4 jmesa PR 9 (cohort 7): per-event-CRF SDV table rendered
     via vanilla-JS fetch + DOM render against /pages/viewAllSubjectSdvData.
     The form's hidden inputs (crfId, redirection) are populated by
     per-row event handlers, then the form posts to the existing
     /pages/handleSDVGet / /pages/handleSDVRemove handlers. --%>
<div id="viewAllSubjectSdvWrap">
    <table id="viewAllSubjectSdvTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="viewAllSubjectSdvHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" colspan="16" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="viewAllSubjectSdvBody">
            <tr><td colspan="16" style="text-align:center; padding:8px;">
                <fmt:message key="loading" bundle="${resword}"/>&hellip;
            </td></tr>
        </tbody>
    </table>
</div>

<script type="text/javascript">
(function () {
    var ctx = '${pageContext.request.contextPath}';
    var studyId = '${studyId}';
    var studySubjectIdFilter = '${studySubjectId}';
    var pathPrefix = '${not empty imagePathPrefix ? imagePathPrefix : "../"}';

    var iLabel = {
        view:   'View',
        sdv:    'SDV',
        noData: '<fmt:message key="no_data" bundle="${resword}"/>'
    };
    var uncheckSdvConfirm = '<fmt:message key="uncheck_sdv" bundle="${resmessages}"/>';

    // Mirrors SDVUtil.CRF_STATUS_ICONS (statusId -> icon suffix).
    var crfStatusIconName = {
        0: 'Invalid',
        1: 'NotStarted',
        2: 'InitialDE',
        3: 'InitialDEComplete',
        4: 'DDE',
        5: 'DEcomplete',
        6: 'InitialDE',
        7: 'Locked'
    };

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function sdvStatusCell(row) {
        if (row.sdvVerified) {
            var lockedClick = row.studyLocked
                ? ''
                : ' href="javascript:void(0)" onclick="sdvFormUncheckRow(' + (row.id || 0) + ');"';
            return '<center><a' + lockedClick + '>'
                 + '<img hspace="2" border="0" title="SDV Complete" alt="SDV Complete"'
                 + ' src="' + pathPrefix + 'images/icon_DoubleCheck.gif"/></a></center>';
        }
        return '<center><input style="margin-right: 5px" type="checkbox" class="sdvCheck"'
             + ' name="sdvCheck_' + esc(row.id) + '"/></center>';
    }

    function crfStatusCell(row) {
        var statusId = row.crfStageId || 0;
        var iconName = crfStatusIconName[statusId] || 'Invalid';
        var ssid = encodeURIComponent(row.studySubjectInternalId || 0);
        var edcId = encodeURIComponent(row.eventDefinitionCRFId || 0);
        var crfVerId = encodeURIComponent(row.crfVersionId || 0);
        var href = "document.location.href='" + ctx
                 + "/ViewSectionDataEntry?eventDefinitionCRFId=" + edcId
                 + "&crfVersionId=" + crfVerId + "&tabId=1&studySubjectId=" + ssid + "'";
        return '<a href="#" onclick="' + esc(href) + '">'
             + '<img hspace="2" border="0" title="Event CRF Status" alt="Event CRF Status"'
             + ' src="' + pathPrefix + 'images/icon_' + esc(iconName) + '.gif"/></a>';
    }

    function actionsCell(row) {
        var acts = row.availableActions || [];
        var html = '';
        for (var i = 0; i < acts.length; i++) {
            if (acts[i] === 'sdv') {
                html += '<input type="submit" class="button_medium" value="' + esc(iLabel.sdv)
                     + '" name="sdvSubmit"'
                     + ' onclick="sdvFormSubmitRow(' + (row.id || 0) + ');"/>';
            }
        }
        return html;
    }

    window.sdvFormSubmitRow = function (crfId) {
        var f = document.forms['sdvForm'];
        f.method = 'GET';
        f.action = ctx + '/pages/handleSDVGet';
        f.crfId.value = crfId;
        f.submit();
    };
    window.sdvFormUncheckRow = function (crfId) {
        if (!confirm(uncheckSdvConfirm)) return;
        var f = document.forms['sdvForm'];
        f.method = 'GET';
        f.action = ctx + '/pages/handleSDVRemove';
        f.crfId.value = crfId;
        f.submit();
    };

    function renderHead(columns) {
        var head = document.getElementById('viewAllSubjectSdvHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('viewAllSubjectSdvBody');
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
                switch (col.key) {
                    case 'sdvStatus':         html += '<td>' + sdvStatusCell(row)  + '</td>'; break;
                    case 'crfStatus':         html += '<td>' + crfStatusCell(row)  + '</td>'; break;
                    case 'sdvStatusActions':  html += '<td>' + actionsCell(row)    + '</td>'; break;
                    default:                  html += '<td>' + esc(row[col.key])    + '</td>'; break;
                }
            }
            html += '</tr>';
        }
        body.innerHTML = html;
    }

    function load() {
        var xhr = new XMLHttpRequest();
        var url = ctx + '/pages/viewAllSubjectSdvData?draw=1&studyId='
            + encodeURIComponent(studyId);
        if (studySubjectIdFilter && studySubjectIdFilter !== '0' && studySubjectIdFilter !== '') {
            url += '&studySubjectId=' + encodeURIComponent(studySubjectIdFilter);
        }
        xhr.open('GET', url, true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('viewAllSubjectSdvBody');
            if (xhr.status !== 200) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error loading SDV table: HTTP ' + xhr.status + '</td></tr>';
                return;
            }
            var payload;
            try { payload = JSON.parse(xhr.responseText); }
            catch (e) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error parsing SDV JSON</td></tr>';
                return;
            }
            var columns = (payload && payload.columns) || [];
            var data    = (payload && payload.data) || [];
            renderHead(columns);
            renderBody(columns, data);
        };
        xhr.onerror = function () {
            document.getElementById('viewAllSubjectSdvBody').innerHTML =
                '<tr><td style="color:red; text-align:center; padding:8px;">Network error loading SDV table</td></tr>';
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

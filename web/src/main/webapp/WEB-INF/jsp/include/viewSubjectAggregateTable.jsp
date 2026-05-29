<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="org.akaza.openclinica.i18n.page_messages" var="resmessages"/>

<%-- Phase B.4 jmesa PR 7b (cohort 5b): per-subject SDV table rendered
     via vanilla-JS fetch + DOM render against /pages/viewSubjectAggregateData.
     The form's hidden inputs (theStudySubjectId, redirection) are
     populated by per-row event handlers, then the form posts to
     /pages/sdvStudySubject or /pages/unSdvStudySubject (legacy). --%>
<div id="viewSubjectAggregateWrap">
    <table id="viewSubjectAggregateTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="viewSubjectAggregateHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" colspan="10" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="viewSubjectAggregateBody">
            <tr><td colspan="10" style="text-align:center; padding:8px;">
                <fmt:message key="loading" bundle="${resword}"/>&hellip;
            </td></tr>
        </tbody>
    </table>
</div>

<script type="text/javascript">
(function () {
    var ctx = '${pageContext.request.contextPath}';
    var studyId = '${studyId}';
    var iLabel = {
        view:   '<fmt:message key="view" bundle="${resword}"/>',
        sdv:    'SDV',
        nA:     'SDV N/A',
        noData: '<fmt:message key="no_data" bundle="${resword}"/>'
    };
    var uncheckSdvConfirm = '<fmt:message key="uncheck_sdv" bundle="${resmessages}"/>';

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function sdvStatusCell(row) {
        var id = encodeURIComponent(row.id || '');
        switch (row.sdvState) {
            case 'complete':
                return '<center><a href="javascript:void(0)" onclick="sdvAggregateUncheck(' + (row.id || 0) + ');">'
                     + '<img hspace="2" border="0" title="SDV Complete" alt="SDV Status"'
                     + ' src="../images/icon_DoubleCheck.gif"/></a></center>';
            case 'needsSdv':
                return '<center><input style="margin-right: 5px" type="checkbox" class="sdvCheck"'
                     + ' name="sdvCheck_' + esc(row.id) + '"/></center>';
            case 'notApplicable':
            default:
                return '';
        }
    }

    function actionsCell(row) {
        var id = encodeURIComponent(row.id || '');
        var acts = row.availableActions || [];
        var subjectLabel = encodeURIComponent(row.studySubjectId || '');
        var html = '<table><tr>';
        for (var i = 0; i < acts.length; i++) {
            switch (acts[i]) {
                case 'view':
                    html += '<td><a href="' + ctx + '/pages/viewAllSubjectSDVtmp?studyId=' + encodeURIComponent(studyId)
                          + '&sdv_f_studySubjectId=' + subjectLabel + '">'
                          + '<img hspace="2" border="0" alt="' + esc(iLabel.view)
                          + '" src="../images/bt_View.gif"/></a></td>';
                    break;
                case 'sdv':
                    html += '<td><input type="submit" class="button" value="' + esc(iLabel.sdv) + '"'
                          + ' name="sdvSubmit"'
                          + ' onclick="sdvAggregateSubmit(' + (row.id || 0) + ');"/></td>';
                    break;
                default:
                    break;
            }
        }
        if (acts.indexOf('sdv') === -1 && acts.indexOf('view') !== -1 && row.sdvState === 'needsSdv') {
            html += '<td>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;' + esc(iLabel.nA) + '</td>';
        }
        html += '</tr></table>';
        return html;
    }

    // Form-submit helpers exposed for inline onclick handlers.
    window.sdvAggregateSubmit = function (studySubjectId) {
        var f = document.forms['sdvForm'];
        f.method = 'GET';
        f.action = ctx + '/pages/sdvStudySubject';
        f.theStudySubjectId.value = studySubjectId;
        f.submit();
    };
    window.sdvAggregateUncheck = function (studySubjectId) {
        if (!confirm(uncheckSdvConfirm)) return;
        var f = document.forms['sdvForm'];
        f.method = 'GET';
        f.action = ctx + '/pages/unSdvStudySubject';
        f.theStudySubjectId.value = studySubjectId;
        f.submit();
    };

    function renderHead(columns) {
        var head = document.getElementById('viewSubjectAggregateHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('viewSubjectAggregateBody');
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
                    case 'sdvStatus': html += '<td>' + sdvStatusCell(row) + '</td>'; break;
                    case 'actions':   html += '<td>' + actionsCell(row)   + '</td>'; break;
                    default:          html += '<td>' + esc(row[col.key])  + '</td>'; break;
                }
            }
            html += '</tr>';
        }
        body.innerHTML = html;
    }

    function load() {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', ctx + '/pages/viewSubjectAggregateData?draw=1&studyId='
            + encodeURIComponent(studyId), true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('viewSubjectAggregateBody');
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
            document.getElementById('viewSubjectAggregateBody').innerHTML =
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

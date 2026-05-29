<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>

<%-- Phase B.4 jmesa PR 7a (cohort 5a): rule-assignment table rendered
     via vanilla-JS fetch + DOM render against /ViewRuleAssignmentData.
     Simple text columns + per-row availableActions. --%>
<div id="viewRuleAssignmentWrap">
    <table id="viewRuleAssignmentTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="viewRuleAssignmentHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" colspan="18" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="viewRuleAssignmentBody">
            <tr><td colspan="18" style="text-align:center; padding:8px;">
                <fmt:message key="loading" bundle="${resword}"/>&hellip;
            </td></tr>
        </tbody>
    </table>
</div>

<script type="text/javascript">
(function () {
    var ctx = '${pageContext.request.contextPath}';
    var iLabel = {
        view:    'View',
        execute: 'Run',
        remove:  'Remove',
        restore: 'Restore',
        extract: 'Download XML',
        test:    'Test',
        noData:  '<fmt:message key="no_data" bundle="${resword}"/>'
    };
    var confirmRemove  = '<fmt:message key="rule_if_you_remove_this" bundle="${resword}"/>';
    var confirmRestore = '<fmt:message key="rule_if_you_restore_this" bundle="${resword}"/>';

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function actionsCell(row) {
        var acts = row.availableActions || [];
        var rsId  = encodeURIComponent(row.ruleSetId || '');
        var rsrId = encodeURIComponent(row.id || '');
        var rId   = encodeURIComponent(row.ruleId || '');
        var html = '';
        for (var i = 0; i < acts.length; i++) {
            switch (acts[i]) {
                case 'view':
                    html += '<a href="ViewRuleSet?ruleSetId=' + rsId + '" title="' + esc(iLabel.view) + '">'
                          + '<img src="images/bt_View.gif" border="0" alt="' + esc(iLabel.view) + '" hspace="2"/></a>&nbsp;';
                    break;
                case 'execute':
                    html += '<a href="RunRuleSet?ruleSetId=' + rsId + '&ruleId=' + rId
                          + '" title="' + esc(iLabel.execute) + '">'
                          + '<img src="images/bt_ExexuteRules.gif" border="0" alt="' + esc(iLabel.execute) + '" hspace="2"/></a>&nbsp;';
                    break;
                case 'remove':
                    html += '<a href="UpdateRuleSetRule?action=remove&ruleSetRuleId=' + rsrId
                          + '&ruleSetId=' + rsId + '" onclick="return confirm(\'' + esc(confirmRemove) + '\');"'
                          + ' title="' + esc(iLabel.remove) + '">'
                          + '<img src="images/bt_Remove.gif" border="0" alt="' + esc(iLabel.remove) + '" hspace="2"/></a>&nbsp;';
                    break;
                case 'restore':
                    html += '<a href="UpdateRuleSetRule?action=restore&ruleSetRuleId=' + rsrId
                          + '&ruleSetId=' + rsId + '" onclick="return confirm(\'' + esc(confirmRestore) + '\');"'
                          + ' title="' + esc(iLabel.restore) + '">'
                          + '<img src="images/bt_Restore.gif" border="0" alt="' + esc(iLabel.restore) + '" hspace="2"/></a>&nbsp;';
                    break;
                case 'extract':
                    html += '<a href="DownloadRuleSetXml?ruleSetRuleIds=' + rsrId
                          + '" title="' + esc(iLabel.extract) + '">'
                          + '<img src="images/bt_Download.gif" border="0" alt="' + esc(iLabel.extract) + '" hspace="2"/></a>&nbsp;';
                    break;
                case 'test':
                    html += '<a href="TestRule?ruleSetRuleId=' + rsrId
                          + '" title="' + esc(iLabel.test) + '">'
                          + '<img src="images/bt_Reassign_d.gif" border="0" alt="' + esc(iLabel.test) + '" hspace="2"/></a>&nbsp;';
                    break;
                default:
                    break;
            }
        }
        return html;
    }

    function renderHead(columns) {
        var head = document.getElementById('viewRuleAssignmentHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('viewRuleAssignmentBody');
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
                if (col.key === 'actions') {
                    html += '<td>' + actionsCell(row) + '</td>';
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
        xhr.open('GET', ctx + '/ViewRuleAssignmentData?draw=1&start=0&length=500', true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('viewRuleAssignmentBody');
            if (xhr.status !== 200) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error loading rule assignments: HTTP ' + xhr.status + '</td></tr>';
                return;
            }
            var payload;
            try { payload = JSON.parse(xhr.responseText); }
            catch (e) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error parsing rule-assignment JSON</td></tr>';
                return;
            }
            var columns = (payload && payload.columns) || [];
            var data    = (payload && payload.data) || [];
            renderHead(columns);
            renderBody(columns, data);
        };
        xhr.onerror = function () {
            document.getElementById('viewRuleAssignmentBody').innerHTML =
                '<tr><td style="color:red; text-align:center; padding:8px;">Network error loading rule assignments</td></tr>';
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

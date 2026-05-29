<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>

<%-- Phase B.4 jmesa PR 7c (cohort 5c): Quartz scheduled-jobs table
     rendered via vanilla-JS fetch + DOM render against
     /pages/listCurrentScheduledJobsData. Per-row "Cancel Job" button
     populates the hidden inputs in the wrapping form and submits the
     existing /pages/cancelScheduledJob handler — no controller change
     needed for cancellation. --%>
<div id="scheduledJobsWrap">
    <table id="scheduledJobsTable" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead id="scheduledJobsHead">
            <tr class="aka_table_header_row">
                <th class="aka_table_header" colspan="5" style="text-align:center; padding:8px;">
                    <fmt:message key="loading" bundle="${resword}"/>&hellip;
                </th>
            </tr>
        </thead>
        <tbody id="scheduledJobsBody">
            <tr><td colspan="5" style="text-align:center; padding:8px;">
                <fmt:message key="loading" bundle="${resword}"/>&hellip;
            </td></tr>
        </tbody>
    </table>
</div>

<script type="text/javascript">
(function () {
    var ctx = '${pageContext.request.contextPath}';
    var iLabel = {
        noData: '<fmt:message key="no_data" bundle="${resword}"/>'
    };

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function cancelJob(jobName, jobGroup, triggerName, triggerGroup) {
        var form = document.forms['scheduledJobsForm'];
        form.theJobName.value = jobName;
        form.theJobGroupName.value = jobGroup;
        form.theTriggerName.value = triggerName;
        form.theTriggerGroupName.value = triggerGroup;
        form.submit();
    }
    // Expose so the inline onclick handler below can reach us.
    window.scheduledJobsCancel = cancelJob;

    function actionCell(row) {
        if (row.isExecuting) return '&nbsp;';
        return '<input type="button" class="button" value="Cancel Job" name="cancelJob"'
             + ' onclick="scheduledJobsCancel('
             + "'" + esc(row.jobName) + "',"
             + "'" + esc(row.jobGroup) + "',"
             + "'" + esc(row.triggerName) + "',"
             + "'" + esc(row.triggerGroup) + "'"
             + ');"/>';
    }

    function renderHead(columns) {
        var head = document.getElementById('scheduledJobsHead');
        var html = '<tr class="aka_table_header_row">';
        for (var i = 0; i < columns.length; i++) {
            html += '<th class="aka_table_header">' + esc(columns[i].title) + '</th>';
        }
        html += '</tr>';
        head.innerHTML = html;
    }

    function renderBody(columns, rows) {
        var body = document.getElementById('scheduledJobsBody');
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
                if (col.key === 'action') {
                    html += '<td>' + actionCell(row) + '</td>';
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
        xhr.open('GET', ctx + '/pages/listCurrentScheduledJobsData?draw=1', true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.onload = function () {
            var body = document.getElementById('scheduledJobsBody');
            if (xhr.status !== 200) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error loading scheduled jobs: HTTP ' + xhr.status + '</td></tr>';
                return;
            }
            var payload;
            try { payload = JSON.parse(xhr.responseText); }
            catch (e) {
                body.innerHTML = '<tr><td style="color:red; text-align:center; padding:8px;">'
                    + 'Error parsing scheduled-jobs JSON</td></tr>';
                return;
            }
            var columns = (payload && payload.columns) || [];
            var data    = (payload && payload.data) || [];
            renderHead(columns);
            renderBody(columns, data);
        };
        xhr.onerror = function () {
            document.getElementById('scheduledJobsBody').innerHTML =
                '<tr><td style="color:red; text-align:center; padding:8px;">Network error loading scheduled jobs</td></tr>';
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

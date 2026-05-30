<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>


<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>


<jsp:include page="../include/admin-header.jsp"/>


<!-- move the alert message to the sidebar-->
<jsp:include page="../include/sideAlert.jsp"/>


<%-- Phase B.4 jmesa PR 4a: jmesa-rendered HTML blob replaced with
     vanilla-JS fetch + DOM render. The Audit User Activity table is
     admin-only, small (typically < 100 rows), and has no
     sort / filter / paginate needs that justify DataTables.net.
     Rows arrive as JSON from /AuditUserActivityData (the
     AuditUserActivityDataServlet endpoint preserved from PR #36). --%>


<!-- then instructions-->
<tr id="sidebar_Instructions_open" style="display: none">
    <td class="sidebar_tab">

        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_collapse.gif" border="0" align="right" hspace="10"></a>

        <b><fmt:message key="instructions" bundle="${resword}"/></b>

        <div class="sidebar_tab_content">

        </div>

    </td>

</tr>
<tr id="sidebar_Instructions_closed" style="display: all">
    <td class="sidebar_tab">

        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_expand.gif" border="0" align="right" hspace="10"></a>

        <b><fmt:message key="instructions" bundle="${resword}"/></b>

    </td>
</tr>
<jsp:include page="../include/sideInfo.jsp"/>

<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='request' id='crf' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean'/>

<h1><span class="title_Manage"><fmt:message key="audit_user_activity" bundle="${resword}"/></span></h1>

<jsp:useBean id="now" class="java.util.Date" />
<P><I><fmt:message key="server_time_info" bundle="${resword}"/> <fmt:formatDate value="${now}" pattern="yyyy-MM-dd hh:mm"/>.</I></P>

<div id="auditUserLoginDiv">
    <table id="auditUserLogin" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead>
            <tr class="aka_table_header_row">
                <th class="aka_table_header"><fmt:message key="user_name" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="attempt_date" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="status" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="details" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="actions" bundle="${resword}"/></th>
            </tr>
        </thead>
        <tbody id="auditUserLoginBody">
            <tr><td colspan="5" style="text-align:center; padding:8px;">Loading&hellip;</td></tr>
        </tbody>
    </table>
</div>

<script type="text/javascript">
  (function () {
    var ctx = '${pageContext.request.contextPath}';
    function esc(s) {
      if (s == null) return '';
      return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }
    function load() {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', ctx + '/AuditUserActivityData?draw=1&start=0&length=500', true);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.onload = function () {
        var tbody = document.getElementById('auditUserLoginBody');
        if (xhr.status !== 200) {
          tbody.innerHTML = '<tr><td colspan="5" style="color:red; text-align:center; padding:8px;">Error loading audit log: HTTP ' + xhr.status + '</td></tr>';
          return;
        }
        var payload;
        try { payload = JSON.parse(xhr.responseText); }
        catch (e) {
          tbody.innerHTML = '<tr><td colspan="5" style="color:red; text-align:center; padding:8px;">Error parsing audit log JSON</td></tr>';
          return;
        }
        var rows = (payload && payload.data) || [];
        if (rows.length === 0) {
          tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding:8px;"><fmt:message key="no_data" bundle="${resword}"/></td></tr>';
          return;
        }
        var html = '';
        for (var i = 0; i < rows.length; i++) {
          var r = rows[i];
          var actionHtml = '';
          if (r.userAccountId != null) {
            var url = ctx + '/ViewUserAccount?userId=' + encodeURIComponent(r.userAccountId) + '&viewFull=yes';
            actionHtml = '<a href="' + url + '" title="View"><img src="images/bt_View.gif" border="0" alt="View" hspace="6"/></a>';
          }
          html += '<tr>'
                + '<td>' + esc(r.userName) + '</td>'
                + '<td>' + esc(r.loginAttemptDate) + '</td>'
                + '<td>' + esc(r.loginStatus) + '</td>'
                + '<td>' + esc(r.details) + '</td>'
                + '<td>' + actionHtml + '</td>'
                + '</tr>';
        }
        tbody.innerHTML = html;
      };
      xhr.onerror = function () {
        document.getElementById('auditUserLoginBody').innerHTML =
            '<tr><td colspan="5" style="color:red; text-align:center; padding:8px;">Network error loading audit log</td></tr>';
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


<br>
<input type="button" onclick="confirmExit('ListUserAccounts');"  name="exit" value="<fmt:message key="exit" bundle="${resword}"/>   " class="button_medium"/>

<jsp:include page="../include/footer.jsp"/>

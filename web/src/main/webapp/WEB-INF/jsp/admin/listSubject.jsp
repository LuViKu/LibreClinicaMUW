<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes" var="restext"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.workflow" var="resworkflow"/>

<jsp:include page="../include/admin-header.jsp"/>

<%-- Phase B.4 jmesa PR 4b: jmesa HTML blob replaced with vanilla-JS
     fetch + DOM render. Rows arrive as JSON from /ListSubjectData
     (see ListSubjectDataServlet). Same pattern as cohort 2a
     (auditUserActivity.jsp); DataTables.net was attempted and
     dropped due to a fatal init crash with LibreClinica's
     includes/prototype.js — see
     web/src/main/webapp/includes/js/datatables/README.md. --%>

<!-- move the alert message to the sidebar-->
<jsp:include page="../include/sideAlert.jsp"/>
<!-- then instructions-->
<tr id="sidebar_Instructions_open" style="display: none">
        <td class="sidebar_tab">

        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_collapse.gif" border="0" align="right" hspace="10"></a>

        <b><fmt:message key="instructions" bundle="${restext}"/></b>

        <div class="sidebar_tab_content">

        </div>

        </td>

    </tr>
    <tr id="sidebar_Instructions_closed" style="display: all">
        <td class="sidebar_tab">

        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_expand.gif" border="0" align="right" hspace="10"></a>

        <b><fmt:message key="instructions" bundle="${restext}"/></b>

        </td>
  </tr>
<jsp:include page="../include/sideInfo.jsp"/>

<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='session' id='userRole' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean' />
<jsp:useBean scope='request' id='table' class='at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable'/>
<h1><span class="title_manage"><fmt:message key="administer_subjects" bundle="${resworkflow}"/></span></h1>

<div id="listSubjectsDiv">
    <table id="listSubjects" class="aka_form" style="width:100%; border-collapse: collapse;">
        <thead>
            <tr class="aka_table_header_row">
                <th class="aka_table_header"><fmt:message key="person_ID" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="Protocol_Study_subject_IDs" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="gender" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="date_created" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="owner" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="date_updated" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="last_updated_by" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="status" bundle="${resword}"/></th>
                <th class="aka_table_header"><fmt:message key="actions" bundle="${resword}"/></th>
            </tr>
        </thead>
        <tbody id="listSubjectsBody">
            <tr><td colspan="9" style="text-align:center; padding:8px;">Loading&hellip;</td></tr>
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
    function actionsHtml(row) {
      if (row.id == null) return '';
      var html = '<a href="' + ctx + '/ViewSubject?action=show&id=' + encodeURIComponent(row.id) + '" title="View">'
               + '<img src="images/bt_View.gif" border="0" alt="View" hspace="6"/></a>';
      if (row.statusDeleted) {
        html += '<a href="' + ctx + '/RestoreSubject?action=confirm&id=' + encodeURIComponent(row.id) + '" title="Restore">'
              + '<img src="images/bt_Restore.gif" border="0" alt="Restore" hspace="6"/></a>';
      } else {
        html += '<a href="' + ctx + '/UpdateSubject?action=show&id=' + encodeURIComponent(row.id) + '" title="Edit">'
              + '<img src="images/bt_Edit.gif" border="0" alt="Edit" hspace="6"/></a>'
              + '<a href="' + ctx + '/RemoveSubject?action=confirm&id=' + encodeURIComponent(row.id) + '" title="Remove">'
              + '<img src="images/bt_Remove.gif" border="0" alt="Remove" hspace="2"/></a>';
      }
      return html;
    }
    function load() {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', ctx + '/ListSubjectData?draw=1&start=0&length=500', true);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.onload = function () {
        var tbody = document.getElementById('listSubjectsBody');
        if (xhr.status !== 200) {
          tbody.innerHTML = '<tr><td colspan="9" style="color:red; text-align:center; padding:8px;">Error loading subject list: HTTP ' + xhr.status + '</td></tr>';
          return;
        }
        var payload;
        try { payload = JSON.parse(xhr.responseText); }
        catch (e) {
          tbody.innerHTML = '<tr><td colspan="9" style="color:red; text-align:center; padding:8px;">Error parsing subject list JSON</td></tr>';
          return;
        }
        var rows = (payload && payload.data) || [];
        if (rows.length === 0) {
          tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding:8px;"><fmt:message key="no_data" bundle="${resword}"/></td></tr>';
          return;
        }
        var html = '';
        for (var i = 0; i < rows.length; i++) {
          var r = rows[i];
          html += '<tr>'
                + '<td>' + esc(r.uniqueIdentifier) + '</td>'
                + '<td>' + esc(r.studySubjectIdAndStudy) + '</td>'
                + '<td>' + esc(r.gender) + '</td>'
                + '<td>' + esc(r.createdDate) + '</td>'
                + '<td>' + esc(r.owner) + '</td>'
                + '<td>' + esc(r.updatedDate) + '</td>'
                + '<td>' + esc(r.updater) + '</td>'
                + '<td>' + esc(r.status) + '</td>'
                + '<td>' + actionsHtml(r) + '</td>'
                + '</tr>';
        }
        tbody.innerHTML = html;
      };
      xhr.onerror = function () {
        document.getElementById('listSubjectsBody').innerHTML =
            '<tr><td colspan="9" style="color:red; text-align:center; padding:8px;">Network error loading subject list</td></tr>';
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

<jsp:include page="../include/footer.jsp"/>

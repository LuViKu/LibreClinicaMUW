<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>


<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>


<jsp:include page="../include/admin-header.jsp"/>


<!-- move the alert message to the sidebar-->
<jsp:include page="../include/sideAlert.jsp"/>


<%-- Phase B.4 jmesa PR 4a: jmesa-rendered HTML blob replaced with
     a DataTables.net 2.x server-side-processing init. The rows are
     fetched via AJAX from /AuditUserActivityData (see
     AuditUserActivityDataServlet). Operator drops the DataTables JS+CSS
     bundle at /includes/js/datatables/ — see the README in that dir. --%>
<link rel="stylesheet" type="text/css"
      href="${pageContext.request.contextPath}/includes/js/datatables/datatables.min.css"/>
<script type="text/javascript"
        src="${pageContext.request.contextPath}/includes/js/datatables/datatables.min.js"></script>


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

<jsp:useBean scope='session' id='userBean' class='org.akaza.openclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='request' id='crf' class='org.akaza.openclinica.bean.admin.CRFBean'/>

<h1><span class="title_Manage"><fmt:message key="audit_user_activity" bundle="${resword}"/></span></h1>

<jsp:useBean id="now" class="java.util.Date" />
<P><I><fmt:message key="server_time_info" bundle="${resword}"/> <fmt:formatDate value="${now}" pattern="yyyy-MM-dd hh:mm"/>.</I></P>

<div id="auditUserLoginDiv">
    <table id="auditUserLogin" class="datatable display" style="width:100%"></table>
</div>

<script type="text/javascript">
  document.addEventListener('DOMContentLoaded', function() {
    new DataTable('#auditUserLogin', {
      serverSide: true,
      processing: true,
      ajax: '${pageContext.request.contextPath}/AuditUserActivityData',
      pageLength: 25,
      lengthMenu: [10, 25, 50, 100],
      stateSave: true,
      order: [[1, 'desc']],
      columns: [
        { data: 'userName',         title: '<fmt:message key="user_name" bundle="${resword}"/>' },
        { data: 'loginAttemptDate', title: '<fmt:message key="attempt_date" bundle="${resword}"/>' },
        { data: 'loginStatus',      title: '<fmt:message key="status" bundle="${resword}"/>' },
        { data: 'details',          title: '<fmt:message key="details" bundle="${resword}"/>' },
        {
          data: 'userAccountId',
          title: '<fmt:message key="actions" bundle="${resword}"/>',
          orderable: false,
          searchable: false,
          render: function(userAccountId) {
            if (userAccountId == null) { return ''; }
            var url = '${pageContext.request.contextPath}/ViewUserAccount?userId=' + encodeURIComponent(userAccountId) + '&viewFull=yes';
            return '<a href="' + url + '" title="View"><img hspace="6" border="0" align="left" alt="View" src="images/bt_View.gif"/></a>';
          }
        }
      ]
    });
  });
</script>


<br>
<input type="button" onclick="confirmExit('ListUserAccounts');"  name="exit" value="<fmt:message key="exit" bundle="${resword}"/>   " class="button_medium"/>

<jsp:include page="../include/footer.jsp"/>

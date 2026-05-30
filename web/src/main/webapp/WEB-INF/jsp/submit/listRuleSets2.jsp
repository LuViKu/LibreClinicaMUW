<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes" var="restext"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.workflow" var="resworkflow"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.format" var="resformat"/>

<c:choose>
<c:when test="${userBean.sysAdmin && module=='admin'}">
 <c:import url="../include/admin-header.jsp"/>
</c:when>
<c:otherwise>
 <c:import url="../include/managestudy-header.jsp"/>
</c:otherwise>
</c:choose>


<!-- move the alert message to the sidebar-->
<jsp:include page="../include/sideAlert.jsp"/>

<%-- Phase B.4 jmesa PR 7a (cohort 5a): jmesa scripts removed —
     table rendered client-side by ../include/viewRuleAssignmentTable.jsp. --%>


<!-- then instructions-->
<tr id="sidebar_Instructions_open" style="display: all">
        <td class="sidebar_tab">
        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_collapse.gif" border="0" align="right" hspace="10"></a>
        <b><fmt:message key="instructions" bundle="${restext}"/></b>
        <div class="sidebar_tab_content">
        <fmt:message key="manage_execute_rule_assignments" bundle="${restext}"/>
        </div>
        </td>
    </tr>
    <tr id="sidebar_Instructions_closed" style="display: none">
        <td class="sidebar_tab">
        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_expand.gif" border="0" align="right" hspace="10"></a>
        <b><fmt:message key="instructions" bundle="${restext}"/></b>
        </td>
  </tr>
<jsp:include page="../include/viewRuleAssignmentSideInfo.jsp"/>

<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='session' id='userRole' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean' />
<jsp:useBean scope='session' id='study' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean'/>
<jsp:useBean scope='request' id='table' class='at.ac.meduniwien.ophthalmology.libreclinica.web.domain.EntityBeanTable'/>

<h1><span class="title_manage"><fmt:message key="rule_manage_rule_assignment" bundle="${resworkflow}"/> <c:out value="${study.name}" /></span></h1>



<div id="ruleAssignmentsDiv">
    <jsp:include page="../include/viewRuleAssignmentTable.jsp"/>
</div>


<br>
<jsp:include page="../include/footer.jsp"/>
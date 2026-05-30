<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes" var="restext"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.format" var="resformat"/>


<jsp:include page="../include/managestudy-header.jsp"/>
<jsp:include page="../include/sideAlert.jsp"/>
<%--<jsp:include page="../include/sidebar.jsp"/>--%>

<%-- Phase B.4 jmesa PR 6a (cohort 4a): jmesa scripts removed — the
     table is rendered client-side by the include/studyAuditLogTable.jsp
     fragment. --%>

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


<h1><span class="title_manage">
<fmt:message key="view_study_log_for" bundle="${resword}"/> <c:out value="${study.name}"/>
</span></h1>

<div id="findSubjectsDiv">
    <jsp:include page="include/studyAuditLogTable.jsp"/>
</div>


<jsp:include page="../include/footer.jsp"/>

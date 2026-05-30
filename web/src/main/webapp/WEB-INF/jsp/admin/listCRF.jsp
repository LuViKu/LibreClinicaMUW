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
<!-- then instructions-->
<tr id="sidebar_Instructions_open" style="display: all">
		<td class="sidebar_tab">

		<a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_collapse.gif" border="0" align="right" hspace="10"></a>

		<b><fmt:message key="instructions" bundle="${restext}"/></b>

		<div class="sidebar_tab_content">
        <fmt:message key="CRF_library_shows_all_CRFs" bundle="${restext}"/>

		</div>

		</td>

	</tr>
	<tr id="sidebar_Instructions_closed" style="display: none">
		<td class="sidebar_tab">

		<a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_expand.gif" border="0" align="right" hspace="10"></a>

		<b><fmt:message key="instructions" bundle="${restext}"/></b>

		</td>
  </tr>
<jsp:include page="../include/sideInfo.jsp"/>

<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='session' id='userRole' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean' />
<jsp:useBean scope='request' id='table' class='at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable'/>
<c:choose>
<c:when test="${userBean.sysAdmin && module=='admin'}">
	<h1><span class="title_manage"><fmt:message key="administer_CRFs2" bundle="${resworkflow}"/></span></h1>
</c:when>
<c:otherwise>
	<h1><span class="title_manage"><fmt:message key="manage_CRFs2" bundle="${resworkflow}"/></span></h1>
</c:otherwise>
</c:choose>

<!-- <p><fmt:message key="can_download_blank_CRF_excel" bundle="${restext}"/> <a href="DownloadVersionSpreadSheet?template=1"><b><fmt:message key="here" bundle="${resword}"/></b></a>.</p> -->

<c:import url="../include/showTable.jsp"><c:param name="rowURL" value="showCRFRow.jsp" /></c:import>


<jsp:include page="../include/footer.jsp"/>

<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>

<jsp:include page="../include/managestudy-header.jsp"/>


<jsp:include page="../include/sidebar.jsp"/>

<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='session' id='study' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean' />

<jsp:useBean scope='request' id='table' class='at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable'/>

<h1><span class="title_manage">
<fmt:message key="view_study_log_for" bundle="${resword}">
	<fmt:param value="${study.name}"/>
</fmt:message> 
</span></h1>

<jsp:include page="../include/alertbox.jsp" />
<c:import url="../include/showTable.jsp"><c:param name="rowURL" value="showAuditEventStudyRow.jsp" /></c:import>

<jsp:include page="../include/footer.jsp"/>

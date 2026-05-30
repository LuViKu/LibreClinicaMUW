<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes" var="restext"/>
<jsp:include page="../include/tech-admin-header.jsp"/>


<jsp:include page="../include/sidebar.jsp"/>


<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>

<h1><span class="title_manage">
<fmt:message key="view_scheduled_task" bundle="${resword}"/>
</span></h1>
<a href="ViewScheduler?action=create"><fmt:message key="click_here_to_start_up" bundle="${restext}"/></a>

<jsp:include page="../include/alertbox.jsp" />



<jsp:include page="../include/footer.jsp"/>
